package com.camerainterceptor.state;

import android.graphics.ImageFormat;

/**
 * Manages the globally intercepted state of the target app's camera requests.
 * Used to store the exact resolution and format the app *wants* so our native
 * layer
 * can correctly format the injected image to match perfectly.
 */
public class HookState {
    private static int targetWidth = -1;
    private static int targetHeight = -1;
    private static int targetFormat = ImageFormat.JPEG; // Default fallback

    public static void setTargetResolution(int width, int height) {
        targetWidth = width;
        targetHeight = height;
    }

    public static void setTargetFormat(int format) {
        targetFormat = format;
    }

    public static int getTargetWidth() {
        return targetWidth;
    }

    public static int getTargetHeight() {
        return targetHeight;
    }

    public static int getTargetFormat() {
        return targetFormat;
    }

    public static boolean hasValidResolution() {
        return targetWidth > 0 && targetHeight > 0;
    }

    public static void reset() {
        targetWidth = -1;
        targetHeight = -1;
        targetFormat = ImageFormat.JPEG;
    }
}
