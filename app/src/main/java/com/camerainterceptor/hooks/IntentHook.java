package com.camerainterceptor.hooks;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.MediaStore;

import com.camerainterceptor.HookDispatcher;
import com.camerainterceptor.utils.Logger;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Hooks for Activity intents that launch the camera
 * Modified to be PASS-THROUGH (Logging only).
 * We rely on hooking the camera app itself (via CameraHook/Camera2Hook) to
 * inject the image data.
 */
public class IntentHook {
    private static final String TAG = "IntentHook";
    private final HookDispatcher dispatcher;

    public IntentHook(HookDispatcher dispatcher) {
        this.dispatcher = dispatcher;
        initHooks();
    }

    private void initHooks() {
        try {
            Logger.i(TAG, "Initializing camera intent hooks (Logging only)");

            // Hook startActivityForResult to detect camera intents
            hookStartActivityForResult();

            Logger.i(TAG, "Camera intent hooks initialized");
        } catch (Throwable t) {
            Logger.e(TAG, "Failed to initialize camera intent hooks: " + t.getMessage());
            Logger.logStackTrace(TAG, t);
        }
    }

    private void hookStartActivityForResult() {
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "startActivityForResult",
                    Intent.class, int.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            Intent intent = (Intent) param.args[0];
                            if (isCameraIntent(intent)) {
                                Logger.i(TAG, "Camera Intent detected: " + intent.getAction());
                                if (dispatcher.isInjectionEnabled()) {
                                    Logger.i(TAG,
                                            "Injection is ENABLED. Expecting downstream camera app to be hooked.");
                                }
                            }
                        }
                    });

            XposedHelpers.findAndHookMethod(Activity.class, "startActivityForResult",
                    Intent.class, int.class, Bundle.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            Intent intent = (Intent) param.args[0];
                            if (isCameraIntent(intent)) {
                                Logger.i(TAG, "Camera Intent detected (with bundle): " + intent.getAction());
                            }
                        }
                    });

        } catch (Throwable t) {
            Logger.e(TAG, "Failed to hook startActivityForResult: " + t.getMessage());
        }
    }

    private boolean isCameraIntent(Intent intent) {
        if (intent == null)
            return false;
        String action = intent.getAction();
        if (action == null)
            return false;

        return action.equals(MediaStore.ACTION_IMAGE_CAPTURE) ||
                action.equals(MediaStore.ACTION_IMAGE_CAPTURE_SECURE) ||
                (action.equals(Intent.ACTION_MAIN) && intent.hasCategory("android.intent.category.APP_GALLERY"));
    }
}