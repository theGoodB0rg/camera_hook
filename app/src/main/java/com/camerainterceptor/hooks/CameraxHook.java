package com.camerainterceptor.hooks;

import android.app.Activity;
import android.content.Context;

import com.camerainterceptor.HookDispatcher;
import com.camerainterceptor.utils.Logger;

import java.lang.reflect.Method;
import java.util.concurrent.Executor;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Hooks for the CameraX API (androidx.camera)
 * With the new ImageReader strategy, we don't need to intercept CameraX
 * explicitly.
 * CameraX sits on top of Camera2, so our Camera2 ImageReader hooks should
 * handle the data injection.
 * We keep this class primarily for logging or future specific tweaks.
 */
public class CameraxHook {
    private static final String TAG = "CameraXHook";
    private final HookDispatcher dispatcher;

    public CameraxHook(HookDispatcher dispatcher) {
        this.dispatcher = dispatcher;
        initHooks();
    }

    // Maps to track CameraX components - kept empty for compatibility if anything
    // references it
    // private final Map<Object, Activity> cameraProviderToActivityMap = new
    // HashMap<>();

    private void initHooks() {
        try {
            Logger.i(TAG, "Initializing CameraX API hooks (Logging only)");

            // Hook ImageCapture to log photo requests
            hookImageCapture();

            Logger.i(TAG, "CameraX API hooks initialized");
        } catch (Throwable t) {
            Logger.e(TAG, "Failed to initialize CameraX API hooks: " + t.getMessage());
            Logger.logStackTrace(TAG, t);
        }
    }

    /**
     * Hook the ImageCapture class to log photo capture requests
     */
    private void hookImageCapture() {
        try {
            // Find the ImageCapture class
            Class<?> imageCaptureClass = XposedHelpers.findClassIfExists(
                    "androidx.camera.core.ImageCapture", dispatcher.getClassLoader());

            if (imageCaptureClass != null) {

                // Newer: takePicture(Executor, ImageCapture.OnImageCapturedCallback)
                Method takePictureCallbackMethod = XposedHelpers.findMethodExactIfExists(
                        imageCaptureClass, "takePicture",
                        Executor.class, XposedHelpers.findClassIfExists(
                                "androidx.camera.core.ImageCapture$OnImageCapturedCallback",
                                dispatcher.getClassLoader()));

                if (takePictureCallbackMethod != null) {
                    XposedBridge.hookMethod(takePictureCallbackMethod, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if (dispatcher.isInjectionEnabled()) {
                                Logger.i(TAG,
                                        "CameraX ImageCapture.takePicture(Callback) detected - relying on Camera2 ImageReader hook");
                            }
                        }
                    });
                }

                // Newer: takePicture(OutputFileOptions, Executor,
                // ImageCapture.OnImageSavedCallback)
                Method takePictureSavedMethod = XposedHelpers.findMethodExactIfExists(
                        imageCaptureClass, "takePicture",
                        XposedHelpers.findClassIfExists(
                                "androidx.camera.core.ImageCapture$OutputFileOptions",
                                dispatcher.getClassLoader()),
                        Executor.class,
                        XposedHelpers.findClassIfExists(
                                "androidx.camera.core.ImageCapture$OnImageSavedCallback",
                                dispatcher.getClassLoader()));

                if (takePictureSavedMethod != null) {
                    XposedBridge.hookMethod(takePictureSavedMethod, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if (dispatcher.isInjectionEnabled()) {
                                Logger.i(TAG,
                                        "CameraX ImageCapture.takePicture(OutputFileOptions) detected - relying on Camera2 ImageReader hook");
                            }
                        }
                    });
                }
            }
        } catch (Throwable t) {
            Logger.e(TAG, "Failed to hook ImageCapture: " + t.getMessage());
        }
    }
}