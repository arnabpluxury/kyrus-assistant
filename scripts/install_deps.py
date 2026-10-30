#!/usr/bin/env python3
"""
Resilient dependency installer for Kyrus.

- Installs packages one-by-one so a single failure does not abort the rest
- Retries with backoff
- Applies known compatibility fixes (pip/setuptools/wheel, binary-only first, etc.)
- Re-attempts failed packages after fixes, then reports what still failed
"""
from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
import time
from pathlib import Path
from typing import Iterable, List, Sequence, Tuple

ROOT = Path(__file__).resolve().parents[1]

# Optional / flaky on Termux / some hosts — try but never hard-fail the whole install.
OPTIONAL_HINTS = {
    "llama-cpp-python",
    "torch",
    "transformers",
    "peft",
    "bitsandbytes",
    "accelerate",
    "trl",
    "datasets",
}


def run(cmd: Sequence[str], *, check: bool = False) -> subprocess.CompletedProcess:
    print("+", " ".join(cmd), flush=True)
    return subprocess.run(list(cmd), check=check)


def pip(*args: str, check: bool = False) -> subprocess.CompletedProcess:
    return run([sys.executable, "-m", "pip", *args], check=check)


def parse_requirements(path: Path) -> List[str]:
    specs: List[str] = []
    if not path.is_file():
        return specs
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith("-r ") or line.startswith("--requirement"):
            nested = line.split(maxsplit=1)[1].strip()
            specs.extend(parse_requirements((path.parent / nested).resolve()))
            continue
        specs.append(line)
    return specs


def pkg_name(spec: str) -> str:
    # Strip extras / version / URL markers for optional-set matching.
    base = re.split(r"[<>=!~;\[]", spec, maxsplit=1)[0].strip()
    if base.startswith(("git+", "http://", "https://", "file:")):
        return base
    return base.lower().replace("_", "-")


def bootstrap_pip() -> None:
    """Upgrade install tooling; ignore failures so installs can still proceed."""
    for attempt in range(1, 4):
        print(f"[bootstrap] upgrading pip/setuptools/wheel (try {attempt}/3)", flush=True)
        r = pip(
            "install",
            "--upgrade",
            "pip",
            "setuptools",
            "wheel",
            "packaging",
        )
        if r.returncode == 0:
            return
        time.sleep(2 * attempt)
    print("[bootstrap] warning: could not upgrade pip tooling; continuing", flush=True)


def apply_compat_fixes(failed: Iterable[str]) -> None:
    """Best-effort fixes for common install failures before retrying failed pkgs."""
    names = {pkg_name(s) for s in failed}
    print("[fix] applying compatibility remedies…", flush=True)

    # Always refresh tooling again after failures.
    pip("install", "--upgrade", "pip", "setuptools", "wheel", "packaging")

    # Build helpers often missing on Termux / slim images.
    if names & {"llama-cpp-python", "cryptography", "lxml", "numpy", "pandas"}:
        pip("install", "--upgrade", "cmake", "ninja", "cython")

    # Prefer binary wheels where possible next round.
    os.environ.setdefault("PIP_ONLY_BINARY", ":all:")

    # llama-cpp-python often needs CMAKE_ARGS on Android/Termux.
    if "llama-cpp-python" in names:
        os.environ.setdefault("CMAKE_ARGS", "-DGGML_OPENMP=ON")
        # Allow source build on retry for this one package.
        only = os.environ.get("PIP_ONLY_BINARY", "")
        if only == ":all:":
            os.environ["PIP_ONLY_BINARY"] = ":none:"

    # huggingface_hub / requests SSL quirks — ensure certifi present.
    if names & {"huggingface-hub", "requests", "urllib3"}:
        pip("install", "--upgrade", "certifi", "urllib3", "charset-normalizer", "idna")


def _use_user_flag() -> bool:
    """--user only outside venvs; Termux system python benefits from it."""
    env = os.environ.get("KYRUS_PIP_USER", "auto").lower()
    if env in {"0", "false", "no"}:
        return False
    if env in {"1", "true", "yes"}:
        return True
    # auto: skip --user inside virtualenv / conda
    if sys.prefix != getattr(sys, "base_prefix", sys.prefix):
        return False
    if os.environ.get("VIRTUAL_ENV") or os.environ.get("CONDA_PREFIX"):
        return False
    return True


def install_one(spec: str, *, retries: int, prefer_binary: bool) -> Tuple[bool, str]:
    last_err = ""
    for attempt in range(1, retries + 1):
        args = ["install", "--upgrade"]
        if prefer_binary:
            args += ["--only-binary=:all:"]
        if _use_user_flag():
            args.append("--user")
        args.append(spec)
        print(f"[install] {spec} (try {attempt}/{retries})", flush=True)
        r = pip(*args)
        if r.returncode == 0:
            return True, ""
        last_err = f"exit {r.returncode}"
        # Second attempt without --only-binary / without --user
        if attempt == 1 and prefer_binary:
            print(f"[install] retry {spec} allowing source builds…", flush=True)
            args2 = ["install", "--upgrade", spec]
            r2 = pip(*args2)
            if r2.returncode == 0:
                return True, ""
            last_err = f"exit {r2.returncode}"
        time.sleep(min(8, 2 * attempt))
    return False, last_err


def install_specs(
    specs: Sequence[str],
    *,
    retries: int = 3,
    prefer_binary: bool = True,
) -> Tuple[List[str], List[Tuple[str, str]]]:
    ok: List[str] = []
    failed: List[Tuple[str, str]] = []

    # Pass 1 — install everything we can
    pending = list(specs)
    still: List[str] = []
    for spec in pending:
        success, err = install_one(spec, retries=retries, prefer_binary=prefer_binary)
        if success:
            ok.append(spec)
        else:
            still.append(spec)
            failed.append((spec, err))

    if not still:
        return ok, []

    # Pass 2 — compatibility fixes, then retry only failures
    apply_compat_fixes(still)
    retry_failed: List[Tuple[str, str]] = []
    for spec in still:
        success, err = install_one(spec, retries=retries, prefer_binary=False)
        if success:
            ok.append(spec)
            print(f"[fixed] {spec}", flush=True)
        else:
            retry_failed.append((spec, err or "unknown"))

    return ok, retry_failed


def main() -> int:
    ap = argparse.ArgumentParser(description="Resilient Kyrus dependency installer")
    ap.add_argument(
        "-r",
        "--requirement",
        action="append",
        default=[],
        help="requirements file (repeatable). Default: requirements.txt",
    )
    ap.add_argument(
        "--extra",
        action="append",
        default=[],
        help="extra package specs (repeatable)",
    )
    ap.add_argument(
        "--editable",
        action="store_true",
        help="also pip install -e . (best-effort)",
    )
    ap.add_argument("--retries", type=int, default=3)
    ap.add_argument(
        "--strict",
        action="store_true",
        help="exit non-zero if any non-optional package failed",
    )
    args = ap.parse_args()

    req_files = [Path(p) for p in args.requirement] or [ROOT / "requirements.txt"]
    specs: List[str] = []
    for rf in req_files:
        specs.extend(parse_requirements(rf if rf.is_absolute() else ROOT / rf))
    specs.extend(args.extra)

    # De-dupe preserving order
    seen = set()
    unique: List[str] = []
    for s in specs:
        key = pkg_name(s)
        if key in seen:
            continue
        seen.add(key)
        unique.append(s)

    print(f"=== Kyrus deps: {len(unique)} package(s) ===", flush=True)
    bootstrap_pip()
    ok, failed = install_specs(unique, retries=max(1, args.retries))

    if args.editable:
        print("[install] editable package (best-effort)", flush=True)
        r = pip("install", "-e", str(ROOT))
        if r.returncode != 0:
            # Common on Termux — install without build isolation / as regular package
            pip("install", "--upgrade", "build")
            r2 = pip("install", "-e", str(ROOT), "--no-build-isolation")
            if r2.returncode != 0:
                failed.append((".", f"editable exit {r2.returncode}"))
            else:
                ok.append(".")
        else:
            ok.append(".")

    print(flush=True)
    print(f"Installed OK ({len(ok)}):", flush=True)
    for s in ok:
        print(f"  ✓ {s}", flush=True)

    hard_fail = []
    soft_fail = []
    for spec, err in failed:
        name = pkg_name(spec)
        bucket = soft_fail if name in OPTIONAL_HINTS else hard_fail
        bucket.append((spec, err))

    if soft_fail:
        print(f"\nOptional / skipped after retries ({len(soft_fail)}):", flush=True)
        for spec, err in soft_fail:
            print(f"  ~ {spec} ({err})", flush=True)

    if hard_fail:
        print(f"\nFailed after fixes ({len(hard_fail)}):", flush=True)
        for spec, err in hard_fail:
            print(f"  ✗ {spec} ({err})", flush=True)
        print(
            "\nTip: re-run  python scripts/install_deps.py  — it continues from what's left.",
            flush=True,
        )
        if args.strict:
            return 1
        # Non-strict: still succeed if core set partially installed (user asked continue-on-fail)
        return 0 if ok else 1

    print("\nAll requested dependencies installed.", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
