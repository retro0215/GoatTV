"""Rebuilds libplayer.so, the JNI bridge of dev.jdtech.mpv:libmpv, from the sources in src/ (upstream v1.0.0 with
the logging change described in README.md) for every ABI the AAR carries, and writes them to app/src/main/jniLibs.

    python third_party/mpv-bridge/build.py --ndk <ndk dir> --aar <libmpv-1.0.0.aar> [--check]

The bridge links against libmpv.so and libavcodec.so from the published AAR, so the AAR's mpv/FFmpeg build is the
one the bridge calls. --check rebuilds into a temporary directory and fails if the result differs from the
committed libraries (the build is deterministic for a given NDK)."""
import argparse, hashlib, os, shutil, subprocess, sys, tempfile, zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))
OUT = os.path.join(REPO, "app", "src", "main", "jniLibs")
NDK_VERSION = "29.0.14206865"  # the NDK upstream v1.0.0 was built with
API = 26  # upstream minSdk
ABIS = {
    "arm64-v8a": "aarch64-linux-android", "armeabi-v7a": "armv7a-linux-androideabi",
    "x86": "i686-linux-android", "x86_64": "x86_64-linux-android",
}  # every ABI the AAR carries, so none can fall back to its copy
SOURCES = ["main.cpp", "render.cpp", "log.cpp", "jni_utils.cpp", "property.cpp", "event.cpp"]


def host_bin(ndk):
    for host in ("windows-x86_64", "linux-x86_64", "darwin-x86_64"):
        p = os.path.join(ndk, "toolchains", "llvm", "prebuilt", host, "bin")
        if os.path.isdir(p): return p
    sys.exit(f"no LLVM toolchain under {ndk}")


def tool(bindir, name):
    for ext in (".cmd", ".exe", ""):
        p = os.path.join(bindir, name + ext)
        if os.path.exists(p): return p
    sys.exit(f"missing {name} in {bindir}")


def build(ndk, aar, outdir):
    bindir = host_bin(ndk)
    work = tempfile.mkdtemp(prefix="mpv-bridge-")
    with zipfile.ZipFile(aar) as z:
        for abi in ABIS:
            for lib in ("libmpv.so", "libavcodec.so"):
                z.extract(f"jni/{abi}/{lib}", work)
    results = {}
    for abi, triple in ABIS.items():
        libdir = os.path.join(work, "jni", abi)
        objs = []
        for src in SOURCES:
            obj = os.path.join(work, f"{abi}-{src}.o")
            subprocess.run([tool(bindir, f"{triple}{API}-clang++"), "-c", os.path.join(HERE, "src", src), "-o", obj,
                            "-std=c++11", "-Werror", "-O2", "-fPIC", "-DNDEBUG", "-ffile-prefix-map=" + HERE + "=.",
                            # the NDK CMake toolchain's defaults, which upstream's build got implicitly
                            "-fstack-protector-strong", "-D_FORTIFY_SOURCE=2", "-Wformat", "-Werror=format-security",
                            "-ffunction-sections", "-fdata-sections", "-funwind-tables",
                            "-I", os.path.join(HERE, "include")], check=True)
            objs.append(obj)
        so = os.path.join(outdir, abi, "libplayer.so")
        os.makedirs(os.path.dirname(so), exist_ok=True)
        subprocess.run([tool(bindir, f"{triple}{API}-clang++"), "-shared", "-o", so, *objs, "-L", libdir,
                        "-lmpv", "-lavcodec", "-llog", "-Wl,-soname,libplayer.so", "-Wl,-z,max-page-size=16384",
                        "-Wl,--build-id=sha1", "-Wl,--no-undefined", "-Wl,--gc-sections", "-Wl,-z,relro",
                        "-Wl,-z,now", "-Wl,-z,noexecstack"], check=True)
        subprocess.run([tool(bindir, "llvm-strip"), "--strip-unneeded", so], check=True)
        results[abi] = hashlib.sha256(open(so, "rb").read()).hexdigest()
    shutil.rmtree(work, ignore_errors=True)
    return results


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--ndk", required=True, help=f"Android NDK {NDK_VERSION}")
    ap.add_argument("--aar", required=True, help="dev.jdtech.mpv:libmpv:1.0.0 AAR")
    ap.add_argument("--check", action="store_true", help="verify the committed libraries instead of writing them")
    a = ap.parse_args()
    if os.path.basename(os.path.normpath(a.ndk)) != NDK_VERSION:
        print(f"warning: expected NDK {NDK_VERSION}, got {a.ndk}", file=sys.stderr)
    out = tempfile.mkdtemp(prefix="mpv-bridge-out-") if a.check else OUT
    hashes = build(a.ndk, a.aar, out)
    bad = 0
    for abi, h in hashes.items():
        if a.check:
            committed = hashlib.sha256(open(os.path.join(OUT, abi, "libplayer.so"), "rb").read()).hexdigest()
            print(f"{abi}: built {h} committed {committed} {'OK' if h == committed else 'DIFFERENT'}")
            bad += h != committed
        else:
            print(f"{abi}: {h}")
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
