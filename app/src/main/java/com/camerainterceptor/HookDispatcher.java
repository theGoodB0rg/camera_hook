// HookDispatcher.java 
package com.camerainterceptor;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.Bundle;
import android.net.Uri;

import com.camerainterceptor.interfaces.HookCallback;
import com.camerainterceptor.ui.ImagePickerActivity;
import com.camerainterceptor.utils.ImageUtils;
import com.camerainterceptor.utils.Logger;
import com.camerainterceptor.utils.MediaPickerHelper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class HookDispatcher {
    private static final String TAG = "HookDispatcher";
    private final Context context;
    private final XC_LoadPackage.LoadPackageParam lpparam;
    private final List<Object> registeredHooks;
    private final Map<String, Object> sharedData;
    private final Handler mainHandler;

    public HookDispatcher(Context context, XC_LoadPackage.LoadPackageParam lpparam) {
        this.context = context;
        this.lpparam = lpparam;
        this.registeredHooks = new ArrayList<>();
        this.sharedData = new HashMap<>();
        this.mainHandler = new Handler(Looper.getMainLooper());
        
        Logger.i(TAG, "HookDispatcher initialized for package: " + lpparam.packageName);
    }

    public void registerHook(Object hook) {
        if (hook != null) {
            registeredHooks.add(hook);
            Logger.d(TAG, "Registered hook: " + hook.getClass().getSimpleName());
        } else {
            Logger.e(TAG, "Attempted to register null hook");
        }
    }

    public Context getContext() {
        return context;
    }

    public XC_LoadPackage.LoadPackageParam getLoadPackageParam() {
        return lpparam;
    }

    public ClassLoader getClassLoader() {
        return lpparam.classLoader;
    }

    /**
     * Store data that needs to be shared between different hooks
     */
    public void putSharedData(String key, Object value) {
        synchronized (sharedData) {
            sharedData.put(key, value);
            Logger.d(TAG, "Stored shared data with key: " + key);
        }
    }

    /**
     * Retrieve shared data
     */
    public Object getSharedData(String key) {
        synchronized (sharedData) {
            return sharedData.get(key);
        }
    }

    /**
     * Remove shared data
     */
    public void removeSharedData(String key) {
        synchronized (sharedData) {
            sharedData.remove(key);
            Logger.d(TAG, "Removed shared data with key: " + key);
        }
    }

    /**
     * Intercept camera request and launch gallery picker
     */
    public void interceptCameraRequest(final Activity activity, final HookCallback callback) {
        try {
            Logger.i(TAG, "Intercepting camera request for: " + activity.getClass().getName());
            
            // Run on UI thread to show the gallery picker
            mainHandler.post(() -> {
                try {
                    MediaPickerHelper.launchGalleryPicker(activity, uri -> {
                        try {
                            if (uri != null) {
                                Logger.i(TAG, "Image selected: " + uri);
                                // Process the selected image
                                processSelectedImage(activity, uri, callback);
                            } else {
                                Logger.w(TAG, "No image selected");
                                if (callback != null) {
                                    callback.onImageSelectionCancelled();
                                }
                            }
                        } catch (Throwable t) {
                            Logger.e(TAG, "Error processing selected image: " + t.getMessage());
                            Logger.logStackTrace(TAG, t);
                            if (callback != null) {
                                callback.onError(t);
                            }
                        }
                    });
                } catch (Throwable t) {
                    Logger.e(TAG, "Error launching gallery picker: " + t.getMessage());
                    Logger.logStackTrace(TAG, t);
                    if (callback != null) {
                        callback.onError(t);
                    }
                }
            });
        } catch (Throwable t) {
            Logger.e(TAG, "Error in interceptCameraRequest: " + t.getMessage());
            Logger.logStackTrace(TAG, t);
            if (callback != null) {
                callback.onError(t);
            }
        }
    }

    /**
     * Process the selected image and prepare it for the intercepted app
     */
    private void processSelectedImage(Activity activity, Uri uri, HookCallback callback) {
        try {
            // Create image metadata that the app expects
            ImageUtils.ImageMetadata metadata = ImageUtils.createFakeMetadata();
            
            // Process the image to ensure it looks like it came from the camera
            byte[] processedImageData = ImageUtils.processGalleryImage(context, uri, metadata);
            
            if (processedImageData != null) {
                Logger.i(TAG, "Image processed successfully, size: " + processedImageData.length + " bytes");
                
                if (callback != null) {
                    callback.onImageSelected(processedImageData, metadata);
                }
            } else {
                Logger.e(TAG, "Failed to process image");
                if (callback != null) {
                    callback.onError(new RuntimeException("Failed to process image"));
                }
            }
        } catch (Throwable t) {
            Logger.e(TAG, "Error processing image: " + t.getMessage());
            Logger.logStackTrace(TAG, t);
            if (callback != null) {
                callback.onError(t);
            }
        }
    }

    /**
     * Run a task on the main thread and wait for its completion
     */
    public <T> T runOnMainThreadAndWait(Callable<T> callable) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            try {
                return callable.call();
            } catch (Exception e) {
                Logger.e(TAG, "Error running task on main thread: " + e.getMessage());
                return null;
            }
        }

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<T> resultRef = new AtomicReference<>();
        
        mainHandler.post(() -> {
            try {
                resultRef.set(callable.call());
            } catch (Exception e) {
                Logger.e(TAG, "Error running task on main thread: " + e.getMessage());
            } finally {
                latch.countDown();
            }
        });

        try {
            latch.await();
        } catch (InterruptedException e) {
            Logger.e(TAG, "Thread interrupted while waiting for main thread task: " + e.getMessage());
            Thread.currentThread().interrupt();
        }

        return resultRef.get();
    }
}