package com.camerainterceptor.processor;

import android.graphics.Bitmap;

/**
 * JNI Interop class for offloading heavy image processing (resizing, format
 * conversion)
 * to the native C++ layer. Essential for maintaining frame rate when injecting
 * large images into high-speed camera pipelines.
 */
public class NativeImageProcessor {

    static {
        try {
            System.loadLibrary("image_processor");
        } catch (UnsatisfiedLinkError e) {
            // Log error but don't crash - we'll fallback to safe mode if needed
            com.camerainterceptor.utils.Logger.e("NativeImageProcessor",
                    "Failed to load libimage_processor.so: " + e.getMessage());
        }
    }

    /**
     * Resizes a Bitmap to exactly match target dimensions using a Center Crop
     * algorithm,
     * then converts the resulting pixels into an NV21 (YUV) byte array.
     * 
     * @param inputBitmap  The original, full-size Bitmap loaded from disk
     * @param targetWidth  The exact width expected by the camera API
     * @param targetHeight The exact height expected by the camera API
     * @return A byte array containing NV21 formatted YUV data ready for injection
     */
    public static native byte[] processBitmapToNV21(Bitmap inputBitmap, int targetWidth, int targetHeight);

    /**
     * Resizes a Bitmap to exactly match target dimensions using a Center Crop
     * algorithm,
     * maintaining the output as an RGB byte array (useful for Surface rendering).
     * 
     * @param inputBitmap  The original, full-size Bitmap loaded from disk
     * @param targetWidth  The exact width expected by the camera API
     * @param targetHeight The exact height expected by the camera API
     * @return A byte array containing raw RGBA_8888 data
     */
    public static native byte[] processBitmapToRGBA(Bitmap inputBitmap, int targetWidth, int targetHeight);

    /**
     * Directly injects a Bitmap frame into an Android Surface.
     * Uses native ANativeWindow APIs for high-speed frame delivery to the
     * viewfinder.
     * 
     * @param source The bitmap to inject
     * @param target The surface destination (e.g. from SurfaceView or ImageReader)
     * @return true if injection was successful
     */
    public static native boolean injectFrameToSurface(Bitmap source, android.view.Surface target);
}
