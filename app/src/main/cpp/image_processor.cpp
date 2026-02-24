#include <jni.h>
#include <android/log.h>
#include <android/bitmap.h>
#include <string.h>
#include <vector>
#include <algorithm>
#include "libyuv.h"

#define LOG_TAG "ImageProcessorNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/**
 * Calculates the crop rectangle for a Center Crop.
 */
void calculateCenterCrop(int src_w, int src_h, int dst_w, int dst_h, 
                         int& crop_x, int& crop_y, int& crop_w, int& crop_h) {
    float src_aspect = (float)src_w / src_h;
    float dst_aspect = (float)dst_w / dst_h;

    if (src_aspect > dst_aspect) {
        // Source is wider than destination - crop the sides
        crop_h = src_h;
        crop_w = (int)(src_h * dst_aspect);
        crop_y = 0;
        crop_x = (src_w - crop_w) / 2;
    } else {
        // Source is taller than destination - crop the top/bottom
        crop_w = src_w;
        crop_h = (int)(src_w / dst_aspect);
        crop_x = 0;
        crop_y = (src_h - crop_h) / 2;
    }
}

extern "C"
JNIEXPORT jbyteArray JNICALL
Java_com_camerainterceptor_processor_NativeImageProcessor_processBitmapToNV21(JNIEnv *env, jclass clazz, jobject input_bitmap, jint target_width, jint target_height) {
    AndroidBitmapInfo info;
    void* pixels;
    int ret;
    
    if ((ret = AndroidBitmap_getInfo(env, input_bitmap, &info)) < 0) {
        LOGE("AndroidBitmap_getInfo() failed! error=%d", ret);
        return nullptr;
    }
    
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        LOGE("Bitmap format is not RGBA_8888!");
        return nullptr;
    }
    
    if ((ret = AndroidBitmap_lockPixels(env, input_bitmap, &pixels)) < 0) {
        LOGE("AndroidBitmap_lockPixels() failed! error=%d", ret);
        return nullptr;
    }

    // 1. Calculate Center Crop
    int crop_x, crop_y, crop_w, crop_h;
    calculateCenterCrop(info.width, info.height, target_width, target_height, crop_x, crop_y, crop_w, crop_h);
    
    // 2. Prepare intermediate scaled ARGB buffer
    std::vector<uint8_t> scaled_rgba(target_width * target_height * 4);
    
    // Pointer to starting pixel after crop
    uint8_t* src_ptr = (uint8_t*)pixels + (crop_y * info.stride) + (crop_x * 4);

    // Scaling using libyuv
    libyuv::ARGBScale(src_ptr, info.stride,
                      crop_w, crop_h,
                      scaled_rgba.data(), target_width * 4,
                      target_width, target_height,
                      libyuv::kFilterBox);

    AndroidBitmap_unlockPixels(env, input_bitmap);

    // 3. Convert Scaled RGBA to NV21
    // NV21 requires (width * height * 1.5) bytes
    jsize nv21_size = target_width * target_height * 3 / 2;
    jbyteArray result = env->NewByteArray(nv21_size);
    if (result == nullptr) return nullptr;

    jbyte* result_ptr = env->GetByteArrayElements(result, nullptr);
    if (result_ptr != nullptr) {
        uint8_t* y_plane = (uint8_t*)result_ptr;
        uint8_t* uv_plane = y_plane + (target_width * target_height);

        // libyuv expects ARGB usually, but lets use the correct converter for Android RGBA
        libyuv::ABGRToNV21(scaled_rgba.data(), target_width * 4,
                           y_plane, target_width,
                           uv_plane, target_width,
                           target_width, target_height);

        env->ReleaseByteArrayElements(result, result_ptr, 0);
    }

    LOGI("NativeImageProcessor: Generated %dx%d NV21 image successfully", target_width, target_height);
    return result;
}

extern "C"
JNIEXPORT jbyteArray JNICALL
Java_com_camerainterceptor_processor_NativeImageProcessor_processBitmapToRGBA(JNIEnv *env, jclass clazz, jobject input_bitmap, jint target_width, jint target_height) {
    AndroidBitmapInfo info;
    void* pixels;
    int ret;
    
    if ((ret = AndroidBitmap_getInfo(env, input_bitmap, &info)) < 0) return nullptr;
    if ((ret = AndroidBitmap_lockPixels(env, input_bitmap, &pixels)) < 0) return nullptr;

    // Center Crop + Scaling
    int crop_x, crop_y, crop_w, crop_h;
    calculateCenterCrop(info.width, info.height, target_width, target_height, crop_x, crop_y, crop_w, crop_h);

    jsize rgba_size = target_width * target_height * 4;
    jbyteArray result = env->NewByteArray(rgba_size);
    if (result == nullptr) {
        AndroidBitmap_unlockPixels(env, input_bitmap);
        return nullptr;
    }

    jbyte* result_ptr = env->GetByteArrayElements(result, nullptr);
    if (result_ptr != nullptr) {
        uint8_t* src_ptr = (uint8_t*)pixels + (crop_y * info.stride) + (crop_x * 4);
        
        libyuv::ARGBScale(src_ptr, info.stride,
                          crop_w, crop_h,
                          (uint8_t*)result_ptr, target_width * 4,
                          target_width, target_height,
                          libyuv::kFilterBox);

        env->ReleaseByteArrayElements(result, result_ptr, 0);
    }

    AndroidBitmap_unlockPixels(env, input_bitmap);
    return result;
}
