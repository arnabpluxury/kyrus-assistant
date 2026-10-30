#include <android/log.h>
#include <jni.h>
#include <unistd.h>

#include <algorithm>
#include <atomic>
#include <cstring>
#include <string>
#include <vector>

#include "llama.h"

#define LOG_TAG "KyrusLlama"
#define LOGi(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGe(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

llama_model * g_model = nullptr;
llama_context * g_ctx = nullptr;
const llama_vocab * g_vocab = nullptr;
bool g_ready = false;
std::atomic<bool> g_stop{false};
std::atomic<bool> g_has_history{false};

void free_ctx_model() {
    if (g_ctx) {
        llama_free(g_ctx);
        g_ctx = nullptr;
    }
    if (g_model) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
    g_vocab = nullptr;
    g_ready = false;
    g_has_history = false;
}

int n_threads() {
    long n = sysconf(_SC_NPROCESSORS_ONLN);
    if (n < 2) n = 2;
    // Mid/high RAM phones: use more cores (still capped for thermal)
    if (n > 8) n = 8;
    return (int) n;
}

std::string apply_chat(const std::string & system, const std::string & user) {
    std::vector<llama_chat_message> msgs;
    if (!system.empty()) {
        msgs.push_back({"system", system.c_str()});
    }
    msgs.push_back({"user", user.c_str()});

    const char * tmpl = llama_model_chat_template(g_model, /*name*/ nullptr);
    int32_t need = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true, nullptr, 0);
    if (need <= 0) {
        return system.empty()
            ? ("User: " + user + "\nAssistant:")
            : (system + "\n\nUser: " + user + "\nAssistant:");
    }
    std::string buf((size_t) need + 1, '\0');
    need = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true, buf.data(), (int32_t) buf.size());
    if (need <= 0) {
        return "User: " + user + "\nAssistant:";
    }
    buf.resize((size_t) need);
    return buf;
}

bool emit_token(JNIEnv * env, jobject cb, jmethodID mid, const std::string & piece) {
    if (!cb || !mid) return true;
    jstring jpiece = env->NewStringUTF(piece.c_str());
    jboolean cont = env->CallBooleanMethod(cb, mid, jpiece);
    env->DeleteLocalRef(jpiece);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return false;
    }
    return cont == JNI_TRUE;
}

std::string generate(
    JNIEnv * env,
    jobject tokenCb,
    const std::string & prompt,
    int max_tokens,
    float temperature,
    bool clear_kv) {
    if (!g_ready || !g_ctx || !g_model || !g_vocab) {
        return "";
    }
    g_stop = false;

    if (clear_kv || !g_has_history) {
        llama_memory_clear(llama_get_memory(g_ctx), true);
        g_has_history = false;
    }

    const int n_prompt = -llama_tokenize(g_vocab, prompt.c_str(), (int32_t) prompt.size(), nullptr, 0, true, true);
    if (n_prompt <= 0) {
        return "";
    }
    std::vector<llama_token> prompt_tokens((size_t) n_prompt);
    if (llama_tokenize(g_vocab, prompt.c_str(), (int32_t) prompt.size(),
                      prompt_tokens.data(), (int32_t) prompt_tokens.size(), true, true) < 0) {
        return "";
    }

    // Fit into context: keep a tail of the prompt if oversized
    const int n_ctx = (int) llama_n_ctx(g_ctx);
    int start = 0;
    if (n_prompt >= n_ctx - 64) {
        start = n_prompt - (n_ctx - 64);
        llama_memory_clear(llama_get_memory(g_ctx), true);
    }

    llama_batch batch = llama_batch_get_one(prompt_tokens.data() + start, n_prompt - start);
    if (llama_decode(g_ctx, batch) != 0) {
        return "";
    }

    auto sparams = llama_sampler_chain_default_params();
    llama_sampler * smpl = llama_sampler_chain_init(sparams);
    if (temperature <= 0.05f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(42));
    }

    jmethodID mid = nullptr;
    if (tokenCb) {
        jclass cls = env->GetObjectClass(tokenCb);
        mid = env->GetMethodID(cls, "onToken", "(Ljava/lang/String;)Z");
        env->DeleteLocalRef(cls);
    }

    std::string out;
    const int n_predict = std::max(16, max_tokens);
    for (int i = 0; i < n_predict; ++i) {
        if (g_stop.load()) break;
        llama_token id = llama_sampler_sample(smpl, g_ctx, -1);
        if (llama_vocab_is_eog(g_vocab, id)) break;

        char buf[256];
        int nl = llama_token_to_piece(g_vocab, id, buf, sizeof(buf), 0, true);
        if (nl > 0) {
            std::string piece(buf, nl);
            out.append(piece);
            if (!emit_token(env, tokenCb, mid, piece)) {
                g_stop = true;
                break;
            }
        }

        batch = llama_batch_get_one(&id, 1);
        if (llama_decode(g_ctx, batch) != 0) break;
    }
    llama_sampler_free(smpl);
    g_has_history = true;
    return out;
}

} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_kyrus_assistant_runtime_llm_LlamaNative_nativeInit(JNIEnv *, jclass) {
    llama_backend_init();
    llama_log_set([](enum ggml_log_level level, const char * text, void *) {
        if (level >= GGML_LOG_LEVEL_ERROR) {
            LOGe("%s", text);
        }
    }, nullptr);
    LOGi("llama backend ready");
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_kyrus_assistant_runtime_llm_LlamaNative_nativeLoad(
    JNIEnv * env, jclass, jstring jpath, jint n_ctx) {
    free_ctx_model();
    const char * path = env->GetStringUTFChars(jpath, nullptr);
    LOGi("loading model %s ctx=%d", path, (int) n_ctx);

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    // mmap is default-on in llama.cpp; keep CPU-only for broad device support
    g_model = llama_model_load_from_file(path, mp);
    env->ReleaseStringUTFChars(jpath, path);
    if (!g_model) {
        LOGe("model load failed");
        return JNI_FALSE;
    }
    g_vocab = llama_model_get_vocab(g_model);

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = (uint32_t) std::max(512, (int) n_ctx);
    cp.n_batch = 512;
    cp.n_threads = n_threads();
    cp.n_threads_batch = n_threads();
    g_ctx = llama_init_from_model(g_model, cp);
    if (!g_ctx) {
        LOGe("context init failed");
        free_ctx_model();
        return JNI_FALSE;
    }
    g_ready = true;
    LOGi("model ready threads=%d mmap=1", n_threads());
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_kyrus_assistant_runtime_llm_LlamaNative_nativeUnload(JNIEnv *, jclass) {
    free_ctx_model();
    llama_backend_free();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_kyrus_assistant_runtime_llm_LlamaNative_nativeIsReady(JNIEnv *, jclass) {
    return g_ready ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_kyrus_assistant_runtime_llm_LlamaNative_nativeStop(JNIEnv *, jclass) {
    g_stop = true;
}

extern "C" JNIEXPORT void JNICALL
Java_com_kyrus_assistant_runtime_llm_LlamaNative_nativeClearHistory(JNIEnv *, jclass) {
    if (g_ctx) {
        llama_memory_clear(llama_get_memory(g_ctx), true);
    }
    g_has_history = false;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_kyrus_assistant_runtime_llm_LlamaNative_nativeComplete(
    JNIEnv * env,
    jclass,
    jstring jsystem,
    jstring juser,
    jint max_tokens,
    jfloat temperature) {
    if (!g_ready) {
        return env->NewStringUTF("");
    }
    const char * sys_c = env->GetStringUTFChars(jsystem, nullptr);
    const char * usr_c = env->GetStringUTFChars(juser, nullptr);
    std::string prompt = apply_chat(sys_c ? sys_c : "", usr_c ? usr_c : "");
    env->ReleaseStringUTFChars(jsystem, sys_c);
    env->ReleaseStringUTFChars(juser, usr_c);
    // Keep KV when possible for multi-turn speed; clear when prompt is a fresh chat turn template
    std::string out = generate(env, nullptr, prompt, (int) max_tokens, temperature, /*clear_kv*/ true);
    return env->NewStringUTF(out.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_kyrus_assistant_runtime_llm_LlamaNative_nativeCompleteStream(
    JNIEnv * env,
    jclass,
    jstring jsystem,
    jstring juser,
    jint max_tokens,
    jfloat temperature,
    jboolean clear_kv,
    jobject tokenCb) {
    if (!g_ready) {
        return env->NewStringUTF("");
    }
    const char * sys_c = env->GetStringUTFChars(jsystem, nullptr);
    const char * usr_c = env->GetStringUTFChars(juser, nullptr);
    std::string prompt = apply_chat(sys_c ? sys_c : "", usr_c ? usr_c : "");
    env->ReleaseStringUTFChars(jsystem, sys_c);
    env->ReleaseStringUTFChars(juser, usr_c);
    std::string out = generate(env, tokenCb, prompt, (int) max_tokens, temperature, clear_kv == JNI_TRUE);
    return env->NewStringUTF(out.c_str());
}
