package com.camerainterceptor.state;

import android.graphics.ImageFormat;

/**
 * Manages the globally intercepted state of the target app's camera requests.
 * Used to store the exact resolution and format the app *wants* so our native
 * layer
 * can correctly format the injected image to match perfectly.
 */
public class HookState {
    public enum InjectionMode {
        SAFE, // Basic method interception (Phase 4)
        DEEP_SURFACE // Full surface/native hijack (Phase 3)
    }

    private static int targetWidth = -1;
    private static int targetHeight = -1;
    private static int targetFormat = ImageFormat.JPEG; // Default fallback
    private static InjectionMode currentMode = InjectionMode.SAFE;

    public static void setTargetResolution(int width, int height) {
        targetWidth = width;
        targetHeight = height;
    }

    public static void setTargetFormat(int format) {
        targetFormat = format;
    }

    public static void setInjectionMode(InjectionMode mode) {
        currentMode = mode;
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

    public static InjectionMode getInjectionMode() {
        return currentMode;
    }

    public static boolean hasValidResolution() {
        return targetWidth > 0 && targetHeight > 0;
    }

    public static void reset() {
        targetWidth = -1;
        targetHeight = -1;
        targetFormat = ImageFormat.JPEG;
        currentMode = InjectionMode.SAFE;
    }
}
