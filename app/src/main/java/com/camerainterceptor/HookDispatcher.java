package com.camerainterceptor;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;

import com.camerainterceptor.interfaces.HookCallback;
import com.camerainterceptor.utils.ImageUtils;
import com.camerainterceptor.utils.Logger;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class HookDispatcher {
    private static final String TAG = "HookDispatcher";
    private static final String PACKAGE_NAME = "com.camerainterceptor";
    private static final String PREFS_NAME = "CameraInterceptorPrefs";
    private static final String PREF_IMAGE_PATH = "injected_image_path";

    private final Context context;
    private final XC_LoadPackage.LoadPackageParam lpparam;
    private final List<Object> registeredHooks;
    private final Map<String, Object> sharedData;
    private final Handler mainHandler;
    private XSharedPreferences prefs;

    public HookDispatcher(Context context, XC_LoadPackage.LoadPackageParam lpparam) {
        this.context = context;
        this.lpparam = lpparam;
        this.registeredHooks = new ArrayList<>();
        this.sharedData = new HashMap<>();
        this.mainHandler = new Handler(Looper.getMainLooper());

        // Initialize XSharedPrefs
        this.prefs = new XSharedPreferences(PACKAGE_NAME, PREFS_NAME);
        this.prefs.makeWorldReadable();

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
     * Check if a pre-selected image is available for injection
     */
    public boolean isInjectionEnabled() {
        if (prefs == null)
            return false;
        prefs.reload();
        String path = prefs.getString(PREF_IMAGE_PATH, null);
        return path != null && new File(path).exists();
    }

    /**
     * Load the pre-selected image from file
     */
    public void getPreSelectedImage(HookCallback callback) {
        try {
            if (prefs == null) {
                if (callback != null)
                    callback.onError(new IllegalStateException("Prefs not initialized"));
                return;
            }

            prefs.reload();
            String path = prefs.getString(PREF_IMAGE_PATH, null);

            if (path == null) {
                if (callback != null)
                    callback.onImageSelectionCancelled();
                return;
            }

            File imgFile = new File(path);
            if (!imgFile.exists() || !imgFile.canRead()) {
                Logger.e(TAG, "Image file not found or not readable: " + path);
                if (callback != null)
                    callback.onError(new IllegalStateException("File unreadable"));
                return;
            }

            Logger.i(TAG, "Loading pre-selected image from: " + path);

            // processSelectedImage expects a Uri usually, but we have a raw file path.
            // We should load bytes directly.

            Bitmap bitmap = BitmapFactory.decodeFile(path);
            if (bitmap == null) {
                if (callback != null)
                    callback.onError(new IllegalStateException("Failed to decode bitmap"));
                return;
            }

            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, stream);
            byte[] imageData = stream.toByteArray();

            // Create metadata
            ImageUtils.ImageMetadata metadata = ImageUtils.createFakeMetadata();
            metadata.width = bitmap.getWidth();
            metadata.height = bitmap.getHeight();

            if (callback != null) {
                callback.onImageSelected(imageData, metadata);
            }

            bitmap.recycle();

        } catch (Throwable t) {
            Logger.e(TAG, "Error loading pre-selected image: " + t.getMessage());
            Logger.logStackTrace(TAG, t);
            if (callback != null) {
                callback.onError(t);
            }
        }
    }

    /**
     * Load pre-selected image synchronously (for Camera2 hooks)
     */
    public byte[] getPreSelectedImageBytes() {
        if (!isInjectionEnabled())
            return null;

        try {
            prefs.reload();
            String path = prefs.getString(PREF_IMAGE_PATH, null);
            if (path == null)
                return null;

            Bitmap bitmap = BitmapFactory.decodeFile(path);
            if (bitmap == null)
                return null;

            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, stream);
            byte[] data = stream.toByteArray();
            bitmap.recycle();
            return data;
        } catch (Exception e) {
            Logger.e(TAG, "Error reading image sync: " + e.getMessage());
            return null;
        }
    }

    /**
     * Run a task on the main thread and wait for its completion
     * (Kept for compatibility if hooks need it, though less critical now)
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
            Logger.e(TAG, "Thread interrupted: " + e.getMessage());
            Thread.currentThread().interrupt();
        }

        return resultRef.get();
    }
}