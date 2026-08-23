#include <jni.h>
#include <android/log.h>

#include "viture_glasses_provider.h"
#include "viture_protocol_public.h"
#include "viture_result.h"

#define LOG_TAG "LATERAL/BeastMode"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static int native_display_mode_for(int width, int height, int refresh_rate) {
    if (width == 1920 && height == 1080) {
        switch (refresh_rate) {
            case 60: return VITURE_NATIVE_DISPLAY_MODE_1920_1080_60HZ;
            case 90: return VITURE_NATIVE_DISPLAY_MODE_1920_1080_90HZ;
            case 120: return VITURE_NATIVE_DISPLAY_MODE_1920_1080_120HZ;
        }
    }
    if (width == 1920 && height == 1200) {
        switch (refresh_rate) {
            case 60: return VITURE_NATIVE_DISPLAY_MODE_1920_1200_60HZ;
            case 90: return VITURE_NATIVE_DISPLAY_MODE_1920_1200_90HZ;
            case 120: return VITURE_NATIVE_DISPLAY_MODE_1920_1200_120HZ;
        }
    }
    if (width == 3840 && height == 1200) {
        switch (refresh_rate) {
            case 60: return VITURE_NATIVE_DISPLAY_MODE_ULTRAWIDE_3840_1200_60HZ;
            case 90: return VITURE_NATIVE_DISPLAY_MODE_ULTRAWIDE_3840_1200_90HZ;
            case 120: return VITURE_NATIVE_DISPLAY_MODE_ULTRAWIDE_3840_1200_120HZ;
        }
    }
    return VITURE_GLASSES_ERROR_INVALID_PARAM;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_lateral_beast_BeastDisplayModeController_nativeSetDisplayTiming(
        JNIEnv*, jobject, jint product_id, jint file_descriptor,
        jint width, jint height, jint refresh_rate) {
    const int display_mode = native_display_mode_for(width, height, refresh_rate);
    if (display_mode == VITURE_GLASSES_ERROR_INVALID_PARAM) {
        LOGE("unsupported native timing %dx%d@%d", width, height, refresh_rate);
        return display_mode;
    }
    XRDeviceProviderHandle handle = xr_device_provider_create(product_id, file_descriptor);
    if (!handle) {
        LOGE("create failed pid=0x%x", product_id);
        return VITURE_GLASSES_ERROR_INVALID_PARAM;
    }

    int result = xr_device_provider_initialize(handle, nullptr, nullptr);
    if (result == VITURE_GLASSES_SUCCESS) result = xr_device_provider_start(handle);
    // Beast uses the native display-mode command space while native tracking is active.
    if (result == VITURE_GLASSES_SUCCESS) result = xr_device_provider_native_set_mode(handle, 1);
    if (result == VITURE_GLASSES_SUCCESS) {
        result = xr_device_provider_native_set_display_mode(handle, display_mode);
    }
    if (result != VITURE_GLASSES_SUCCESS) LOGE("display mode command failed: %d", result);

    xr_device_provider_stop(handle);
    xr_device_provider_shutdown(handle);
    xr_device_provider_destroy(handle);
    return result;
}
