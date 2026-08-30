#include <jni.h>
#include <android/log.h>

#define LOG_TAG "LATERAL/BeastMode"

extern "C" JNIEXPORT jint JNICALL
Java_com_lateral_beast_BeastDisplayModeController_nativeSetDisplayTiming(
        JNIEnv*, jobject, jint, jint, jint, jint, jint) {
    __android_log_print(
            ANDROID_LOG_INFO,
            LOG_TAG,
            "Native VITURE display timing is unavailable in the monitor build");
    return -1;
}
