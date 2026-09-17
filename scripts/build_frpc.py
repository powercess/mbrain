"""Build the pinned upstream frpc for every supported Android ABI (Go + NDK)."""
import argparse
import hashlib
import os
from pathlib import Path
import platform
import subprocess
import urllib.request
import zipfile

VERSION = "0.71.0"
SHA256 = "35a19bb92d3b6ce20d9bceb2057ca88b4dfd56b15adf521c2110ddcc8e53e4e8"
TARGETS = {
    "arm64-v8a": ("arm64", "aarch64-linux-android"),
    "armeabi-v7a": ("arm", "armv7a-linux-androideabi"),
    "x86_64": ("amd64", "x86_64-linux-android"),
    "x86": ("386", "i686-linux-android"),
}


def build(ndk, output, cache):
    cache.mkdir(parents=True, exist_ok=True)
    archive = cache / f"frp-{VERSION}.zip"
    if not archive.exists():
        with urllib.request.urlopen(f"https://codeload.github.com/fatedier/frp/zip/refs/tags/v{VERSION}", timeout=120) as response:
            archive.write_bytes(response.read())
    if hashlib.sha256(archive.read_bytes()).hexdigest() != SHA256:
        raise ValueError("frp source checksum mismatch; remove the cached archive and retry")
    source = cache / f"frp-{VERSION}"
    if not source.exists():
        with zipfile.ZipFile(archive) as package:
            for item in package.infolist():
                target = (cache / item.filename).resolve()
                if not target.is_relative_to(cache.resolve()):
                    raise ValueError("Unsafe archive path")
            package.extractall(cache)
    # MBrain never enables frpc's web admin listener. Satisfy its embed directive
    # without shipping an unused web dashboard or requiring a Node toolchain.
    dashboard = source / "web" / "frpc" / "dist"
    dashboard.mkdir(parents=True, exist_ok=True)
    (dashboard / "index.html").write_text("<!doctype html><title>Not enabled</title>", encoding="utf-8")
    host = {"Windows": "windows-x86_64", "Linux": "linux-x86_64", "Darwin": "darwin-x86_64"}[platform.system()]
    toolchain = ndk / "toolchains" / "llvm" / "prebuilt" / host / "bin"
    for abi, (arch, triple) in TARGETS.items():
        compiler = toolchain / (triple + "28-clang" + (".cmd" if os.name == "nt" else ""))
        if not compiler.exists():
            raise FileNotFoundError(f"Install NDK 28.2.13676358: missing {compiler.name}")
        target = output / abi / "libfrpc.so"
        target.parent.mkdir(parents=True, exist_ok=True)
        env = dict(os.environ, GOOS="android", GOARCH=arch, CGO_ENABLED="1", CC=str(compiler), GOARM="7")
        subprocess.run(["go", "build", "-trimpath", "-buildvcs=false", "-ldflags=-s -w -checklinkname=0 -extldflags=-Wl,-z,max-page-size=16384",
                        "-tags=frpc", "-o", str(target.resolve()), "./cmd/frpc"], cwd=source, env=env, check=True)
        print(f"Built frpc {VERSION}: {abi}", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--ndk", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--cache", type=Path, required=True)
    args = parser.parse_args()
    build(args.ndk.resolve(), args.output.resolve(), args.cache.resolve())
