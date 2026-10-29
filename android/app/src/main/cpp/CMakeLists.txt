cmake_minimum_required(VERSION 3.22.1)
project(kyrus_llama)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Prefer vendored llama.cpp from the monorepo (.deps); else FetchContent.
set(KYRUS_LLAMA_DIR "${CMAKE_CURRENT_LIST_DIR}/../../../../../.deps/llama.cpp")
if(NOT EXISTS "${KYRUS_LLAMA_DIR}/CMakeLists.txt")
    include(FetchContent)
    message(STATUS "llama.cpp not in .deps — fetching ggml-org/llama.cpp")
    FetchContent_Declare(
        llama
        GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
        GIT_TAG        master
        GIT_SHALLOW    TRUE
    )
    set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
    set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
    set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
    set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
    set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
    FetchContent_MakeAvailable(llama)
    set(LLAMA_SRC ${llama_SOURCE_DIR})
else()
    message(STATUS "Using llama.cpp at ${KYRUS_LLAMA_DIR}")
    set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
    set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
    set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
    set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
    set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
    add_subdirectory(${KYRUS_LLAMA_DIR} build-llama EXCLUDE_FROM_ALL)
    set(LLAMA_SRC ${KYRUS_LLAMA_DIR})
endif()

add_library(kyrus_llama SHARED kyrus_llama_jni.cpp)

target_include_directories(kyrus_llama PRIVATE
    ${LLAMA_SRC}/include
    ${LLAMA_SRC}/ggml/include
)

target_link_libraries(kyrus_llama
    llama
    android
    log
)
