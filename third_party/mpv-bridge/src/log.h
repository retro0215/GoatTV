#pragma once

#include <android/log.h>
#include <jni.h>

// OwnTV change (the only one in src/): upstream sets DEBUG 1, so ALOGV writes every mpv log message
// ("[cplayer:v] Opening <stream URL>" and so on, credentials included) to logcat in every build. With 0
// nothing is printed here; the same messages still reach MPVLib.LogObserver, where the app filters and
// redacts them. ALOGE (property names and error strings only) is unchanged.
#define DEBUG 0

#define LOG_TAG "mpv"
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#if DEBUG
#define ALOGV(...) __android_log_print(ANDROID_LOG_VERBOSE, LOG_TAG, __VA_ARGS__)
#else
#define ALOGV(...) (void)0
#endif

void die(JNIEnv *env, const char *msg);
void throw_java_exception(JNIEnv *env, const char *msg);
