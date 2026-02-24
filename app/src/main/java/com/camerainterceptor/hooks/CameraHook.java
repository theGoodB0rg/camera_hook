package com.camerainterceptor.hooks;

import android.hardware.Camera;
import android.hardware.Camera.PictureCallback;
import android.hardware.Camera.ShutterCallback;
import android.os.Handler;
import android.os.Looper;

import com.camerainterceptor.HookDispatcher;
import com.camerainterceptor.interfaces.HookCallback;
import com.camerainterceptor.utils.ImageUtils.ImageMetadata;
import com.camerainterceptor.utils.Logger;

import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Hooks for the original Camera API (android.hardware.Camera)
 * Legacy API support with silent injection and robust error handling.
 */
public class CameraHook {
    private static final String TAG = "CameraHook";
    private static final long PREVIEW_RESTART_DELAY_MS = 100;

    private final HookDispatcher dispatcher;
    private final Handler mainHandler;
    private android.view.Surface viewfinderSurface;

    public CameraHook(HookDispatcher dispatcher) {
        this.dispatcher = dispatcher;
        this.mainHandler = new Handler(Looper.getMainLooper());
        initHooks();
    }

    private void initHooks() {
        try {
            Logger.i(TAG, "Initializing Camera API hooks");
            hookCameraParameters();
            hookViewfinder();
            hookTakePicture();
        } catch (Throwable t) {
            Logger.e(TAG, "Failed to initialize Camera API hooks: " + t.getMessage());
        }
    }

    private void hookViewfinder() {
        try {
            XposedHelpers.findAndHookMethod(Camera.class, "setPreviewDisplay",
                    android.view.SurfaceHolder.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            android.view.SurfaceHolder holder = (android.view.SurfaceHolder) param.args[0];
                            if (holder != null && holder.getSurface() != null) {
                                viewfinderSurface = holder.getSurface();
                            }
                        }
                    });

            XposedHelpers.findAndHookMethod(Camera.class, "setPreviewTexture",
                    android.graphics.SurfaceTexture.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            android.graphics.SurfaceTexture texture = (android.graphics.SurfaceTexture) param.args[0];
                            if (texture != null) {
                                viewfinderSurface = new android.view.Surface(texture);
                            }
                        }
                    });

            // 3. Hook startPreview() to begin spoofing
            XposedBridge.hookMethod(XposedHelpers.findMethodExact(Camera.class, "startPreview"), new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (!dispatcher.isInjectionEnabled() || !dispatcher.isViewfinderSpoofingEnabled())
                        return;

                    // Deep Surface Mode (Phase 3) - Only start if enabled
                    if (dispatcher.isDeepSurfaceModeEnabled()) {
                        if (viewfinderSurface != null && viewfinderSurface.isValid()) {
                            Logger.i(TAG, "startPreview: Starting DEEP viewfinder spoofing");
                            dispatcher.getViewfinderManager().startSpoofing(viewfinderSurface);
                        }
                    } else {
                        Logger.d(TAG, "startPreview: DEEP spoofing disabled, using SAFE mode");
                    }
                }
            });

            // 4. Hook stopPreview() to halt spoofing
            XposedBridge.hookMethod(XposedHelpers.findMethodExact(Camera.class, "stopPreview"), new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    dispatcher.getViewfinderManager().stopSpoofing();
                }
            });
        } catch (Throwable t) {
            Logger.e(TAG, "Failed to hook viewfinder: " + t.getMessage());
        }
    }

    private void hookCameraParameters() {
        try {
            XposedHelpers.findAndHookMethod(Camera.Parameters.class, "setPreviewSize",
                    int.class, int.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            com.camerainterceptor.state.HookState.setTargetResolution((int) param.args[0],
                                    (int) param.args[1]);
                        }
                    });

            XposedHelpers.findAndHookMethod(Camera.Parameters.class, "setPictureSize",
                    int.class, int.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            com.camerainterceptor.state.HookState.setTargetResolution((int) param.args[0],
                                    (int) param.args[1]);
                        }
                    });

            XposedHelpers.findAndHookMethod(Camera.Parameters.class, "setPreviewFormat",
                    int.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            com.camerainterceptor.state.HookState.setTargetFormat((int) param.args[0]);
                        }
                    });
        } catch (Throwable t) {
            Logger.e(TAG, "Failed to hook Camera.Parameters: " + t.getMessage());
        }
    }

    private void hookTakePicture() {
        try {
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
                    if (!dispatcher.isPackageAllowed(targetPackage) || !dispatcher.isInjectionEnabled()) {
                        return;
                    }

                    Camera camera = (Camera) param.thisObject;
                    PictureCallback jpegCallback = (PictureCallback) param.args[3];
                    if (jpegCallback == null)
                        return;

                    Logger.i(TAG, "Intercepting takePicture...");

                    // 1. Start Watchdog
                    com.camerainterceptor.utils.Watchdog watchdog = new com.camerainterceptor.utils.Watchdog(
                            "Legacy Injection", 3000, () -> {
                                Logger.e(TAG, "Watchdog triggered: Injection timed out. Recovering...");
                            });
                    watchdog.start();

                    try {
                        int targetWidth = com.camerainterceptor.state.HookState.getTargetWidth();
                        int targetHeight = com.camerainterceptor.state.HookState.getTargetHeight();
                        byte[] imageData = dispatcher.getInjectedImageBytes(targetWidth, targetHeight);

                        if (imageData == null) {
                            watchdog.cancel();
                            return;
                        }

                        param.setResult(null);

                        final ShutterCallback shutterCallback = (ShutterCallback) param.args[0];
                        final PictureCallback rawCallback = (PictureCallback) param.args[1];
                        final PictureCallback postviewCallback = (PictureCallback) param.args[2];

                        Runnable deliverCallbacks = () -> {
                            try {
                                if (shutterCallback != null)
                                    shutterCallback.onShutter();
                                if (rawCallback != null)
                                    rawCallback.onPictureTaken(null, camera);
                                if (postviewCallback != null)
                                    postviewCallback.onPictureTaken(null, camera);

                                jpegCallback.onPictureTaken(imageData, camera);
                                Logger.i(TAG, "Injected " + imageData.length + " bytes successfully");
                                watchdog.cancel(); // SUCCESS

                                mainHandler.postDelayed(() -> {
                                    try {
                                        camera.startPreview();
                                    } catch (Throwable ignored) {
                                    }
                                }, PREVIEW_RESTART_DELAY_MS);
                            } catch (Throwable t) {
                                Logger.e(TAG, "Error delivering callbacks: " + t.getMessage());
                                watchdog.cancel();
                            }
                        };

                        if (Looper.myLooper() == Looper.getMainLooper()) {
                            deliverCallbacks.run();
                        } else {
                            mainHandler.post(deliverCallbacks);
                        }
                    } catch (Throwable t) {
                        Logger.e(TAG, "Unexpected error during injection: " + t.getMessage());
                        watchdog.cancel();
                    }
                }
            });
        } catch (Throwable t) {
            Logger.e(TAG, "Failed to hook takePicture: " + t.getMessage());
        }
    }

    private String buildStack() {
        StringBuilder sb = new StringBuilder();
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        int count = 0;
        for (StackTraceElement el : stack) {
            String cls = el.getClassName();
            if (cls.startsWith("java.lang.Thread") || cls.contains("CameraHook"))
                continue;
            if (count > 6)
                break;
            if (sb.length() > 0)
                sb.append(" | ");
            sb.append(el.getClassName()).append("#").append(el.getMethodName()).append(":").append(el.getLineNumber());
            count++;
        }
        return sb.toString();
    }
}