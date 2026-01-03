package com.camerainterceptor.hooks;

import android.hardware.Camera;
import android.hardware.Camera.PictureCallback;

import com.camerainterceptor.HookDispatcher;
import com.camerainterceptor.interfaces.HookCallback;
import com.camerainterceptor.utils.ImageUtils.ImageMetadata;
import com.camerainterceptor.utils.Logger;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Hooks for the original Camera API (android.hardware.Camera)
 * Legacy API support with silent injection.
 */
public class CameraHook {
    private static final String TAG = "CameraHook";
    private final HookDispatcher dispatcher;

    public CameraHook(HookDispatcher dispatcher) {
        this.dispatcher = dispatcher;
        initHooks();
    }

    private void initHooks() {
        try {
            Logger.i(TAG, "Initializing Camera API hooks");
            hookTakePicture();
            Logger.i(TAG, "Camera API hooks initialized successfully");
        } catch (Throwable t) {
            Logger.e(TAG, "Failed to initialize Camera API hooks: " + t.getMessage());
            Logger.logStackTrace(TAG, t);
        }
    }

    /**
     * Hook Camera.takePicture() to intercept photo capture
     */
    private void hookTakePicture() {
        try {
            // There are multiple overloaded takePicture methods, but they all call the most
            // complete one
            Method takePictureMethod = XposedHelpers.findMethodExactIfExists(
                    Camera.class, "takePicture",
                    Camera.ShutterCallback.class,
                    Camera.PictureCallback.class,
                    Camera.PictureCallback.class,
                    Camera.PictureCallback.class);

            if (takePictureMethod == null)
                return;

            XposedBridge.hookMethod(takePictureMethod, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    String targetPackage = dispatcher.getLoadPackageParam().packageName;
                    if (!dispatcher.isPackageAllowed(targetPackage)) {
                        Logger.d(TAG, "Package not allowed for injection: " + targetPackage);
                        return;
                    }

                    if (dispatcher.isProfilingEnabled()) {
                        Logger.i(TAG, "[PROFILE] Camera.takePicture invoked. stack=" + buildStack());
                        return; // allow normal capture
                    }

                    if (!dispatcher.isInjectionEnabled()) {
                        Logger.d(TAG, "Injection disabled or no image available");
                        return;
                    }

                    Camera camera = (Camera) param.thisObject;
                    Logger.i(TAG, "Camera.takePicture() intercepted, Silent Injection Enabled");

                    // Get the JPEG callback (which is the last parameter)
                    PictureCallback jpegCallback = (PictureCallback) param.args[3];

                    if (jpegCallback != null) {
                        // Prevent the original method from executing (no real photo taken)
                        param.setResult(null);

                        // Inject pre-selected image
                        Logger.i(TAG, "Injecting pre-selected image into Camera API");

                        dispatcher.getPreSelectedImage(new HookCallback() {
                            @Override
                            public void onImageSelected(byte[] imageData, ImageMetadata metadata) {
                                try {
                                    // Deliver fake image to app
                                    jpegCallback.onPictureTaken(imageData, camera);
                                    Logger.i(TAG, "Successfully delivered injected image to app");
                                } catch (Throwable t) {
                                    Logger.e(TAG, "Error delivering image to app: " + t.getMessage());
                                }
                            }

                            @Override
                            public void onImageSelectionCancelled() {
                                Logger.w(TAG, "Image selection cancelled/failed, cannot inject.");
                            }

                            @Override
                            public void onError(Throwable throwable) {
                                Logger.e(TAG, "Error obtaining pre-selected image: " + throwable.getMessage());
                            }
                        });
                    }
                }
            });
        } catch (Throwable t) {
            Logger.e(TAG, "Failed to hook Camera.takePicture: " + t.getMessage());
            Logger.logStackTrace(TAG, t);
        }
    }

    private String buildStack() {
        StringBuilder sb = new StringBuilder();
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        int count = 0;
        for (StackTraceElement el : stack) {
            String cls = el.getClassName();
            if (cls.startsWith("java.lang.Thread") || cls.contains("CameraHook")) {
                continue;
            }
            if (count > 6) break;
            if (sb.length() > 0) sb.append(" | ");
            sb.append(el.getClassName()).append("#").append(el.getMethodName()).append(":").append(el.getLineNumber());
            count++;
        }
        return sb.toString();
    }
}