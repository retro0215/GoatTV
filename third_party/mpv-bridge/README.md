# libmpv JNI bridge (rebuilt)

`app/src/main/jniLibs/<abi>/libplayer.so` replaces the `libplayer.so` inside `dev.jdtech.mpv:libmpv:1.0.0`.
Everything else from that AAR (libmpv, FFmpeg, libc++_shared and the Kotlin `MPVLib` class) is used unchanged.

## Why

Upstream's bridge hardcodes `#define DEBUG 1` in `log.h`, and `event.cpp` prints every mpv log message
with `__android_log_print(ANDROID_LOG_VERBOSE, "mpv", "[%s:%s] %s", ...)`. The bridge also requests mpv's
messages at level `v`, which mpv's `msg-level` option does not limit. So in every build, release included,
logcat received lines such as `[cplayer:v] Opening http://host/live/<user>/<pass>/<id>.ts`: provider
usernames, passwords and tokens. 1.0.0 is the latest release, and its Kotlin API has no way to change
either setting.

## The change

`src/log.h` sets `DEBUG 0` (commented in place). That is the only change to the upstream sources:

- no mpv log message and no event name is printed by the bridge any more;
- mpv's messages still reach `MPVLib.LogObserver`, where the app keeps errors and real failures and redacts
  them with `SensitiveText` before they are logged, stored or shown;
- the `ALOGE` lines (property names and mpv error strings only, no values) are unchanged.

## Sources

- `src/`: `libmpv/src/main/cpp` of https://github.com/jarnedemeulemeester/libmpv-android at tag `v1.0.0`
  (commit `fcf6745703`), MIT, see `LICENSE`.
- `include/mpv/`: mpv 0.41.0 client API headers (ISC, notice in each header), the mpv version the AAR uses.
- `include/libavcodec/jni.h`: FFmpeg 8.1 (LGPL-2.1-or-later, notice in the header), declarations only.

## Rebuilding

Needs Android NDK `29.0.14206865` (the version upstream used) and the published AAR from the Gradle cache:

```
python third_party/mpv-bridge/build.py --ndk <sdk>/ndk/29.0.14206865 --aar <gradle cache>/libmpv-1.0.0.aar
python third_party/mpv-bridge/build.py --ndk ... --aar ... --check   # verifies the committed libraries
```

The build is deterministic: `--check` rebuilds into a temporary directory and compares byte for byte.
All four ABIs the AAR carries are rebuilt (arm64-v8a, armeabi-v7a, x86, x86_64), so none can fall back to
upstream's copy. The libraries export the same 16 `Java_dev_jdtech_mpv_MPVLib_*` functions as upstream's and link the same
libraries (libmpv, libavcodec, liblog, libc++_shared, libm, libdl, libc).

## Guards

- `packaging.jniLibs.pickFirsts` packages the project's copy, and `verifyMpvBridge` (`app/build.gradle.kts`)
  fails the build if any packaged `libplayer.so` differs from the one in `src/main/jniLibs`.
- `MpvBridgeTest` fails if the per-message log format returns or an entry point goes missing.

When upgrading `libmpv`, rebuild the bridge from the new tag's sources (or drop this override once a release
lets the app choose the log level; upstream added `MPVLib.requestLogMessages` on 2026-11-01, unreleased).
