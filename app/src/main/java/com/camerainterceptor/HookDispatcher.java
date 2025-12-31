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
import java.util.Set;
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
    private static final String PREF_ALLOWED_APPS = "allowed_apps";
    
    // World-readable external path - must match ImagePickerActivity
    private static final String EXTERNAL_IMAGE_PATH = "/sdcard/.camerainterceptor/injected_image.jpg";

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
        if (!isPackageAllowedInPrefs(lpparam.packageName)) {
            Logger.d(TAG, "Package not allowed for injection: " + lpparam.packageName);
            return false;
        }

        // First check: External storage (most reliable, world-readable)
        File externalFile = new File(EXTERNAL_IMAGE_PATH);
        if (externalFile.exists() && externalFile.canRead()) {
            Logger.d(TAG, "Found injected image at external path: " + EXTERNAL_IMAGE_PATH);
            return true;
        }

        // Try to get image path from prefs
        String path = null;
        if (prefs != null) {
            prefs.reload();
            path = prefs.getString(PREF_IMAGE_PATH, null);
        }
        
        // Check if pref path exists
        if (path != null && new File(path).exists() && new File(path).canRead()) {
            Logger.d(TAG, "Found injected image from prefs: " + path);
            return true;
        }
        
        // Fallback: check other standard locations
        File[] fallbackPaths = new File[] {
            new File("/storage/emulated/0/.camerainterceptor/injected_image.jpg"),
            new File("/data/user/0/" + PACKAGE_NAME + "/files/injected_image.jpg"),
            new File("/data/data/" + PACKAGE_NAME + "/files/injected_image.jpg"),
            new File("/data/user_de/0/" + PACKAGE_NAME + "/files/injected_image.jpg"),
        };
        
        for (File fallback : fallbackPaths) {
            if (fallback.exists() && fallback.canRead()) {
                Logger.i(TAG, "Found injected image at fallback path: " + fallback.getAbsolutePath());
                return true;
            }
        }
        
        Logger.d(TAG, "No injected image path configured or file missing (checked prefs and fallbacks)");
        return false;
    }

    public boolean isPackageAllowed() {
        return isPackageAllowed(lpparam.packageName);
    }

    public boolean isPackageAllowed(String packageName) {
        if (prefs == null)
            return true;
        prefs.reload();
        return isPackageAllowedInPrefs(packageName);
    }

    private boolean isPackageAllowedInPrefs(String packageName) {
        if (prefs == null)
            return true;

        Set<String> allowed = prefs.getStringSet(PREF_ALLOWED_APPS, null);
        if (allowed == null || allowed.isEmpty()) {
            // Empty list means no filter applied; allow all
            return true;
        }
        return allowed.contains(packageName);
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
            if (!isPackageAllowedInPrefs(lpparam.packageName)) {
                Logger.i(TAG, "Package not in allowed list: " + lpparam.packageName);
                if (callback != null) {
                    callback.onImageSelectionCancelled();
                }
                return;
            }
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
        if (!isPackageAllowedInPrefs(lpparam.packageName)) {
            return null;
        }

        try {
            String path = null;
            
            // First priority: External storage (world-readable)
            File externalFile = new File(EXTERNAL_IMAGE_PATH);
            if (externalFile.exists() && externalFile.canRead()) {
                path = EXTERNAL_IMAGE_PATH;
                Logger.i(TAG, "Loading image from external storage: " + path);
            }
            
            // Second: Try prefs
            if (path == null && prefs != null) {
                prefs.reload();
                String prefPath = prefs.getString(PREF_IMAGE_PATH, null);
                if (prefPath != null && new File(prefPath).exists() && new File(prefPath).canRead()) {
                    path = prefPath;
                    Logger.d(TAG, "Loading image from prefs path: " + path);
                }
            }
            
            // Third: Fallback locations
            if (path == null) {
                File[] fallbackPaths = new File[] {
                    new File("/storage/emulated/0/.camerainterceptor/injected_image.jpg"),
                    new File("/data/user/0/" + PACKAGE_NAME + "/files/injected_image.jpg"),
                    new File("/data/data/" + PACKAGE_NAME + "/files/injected_image.jpg"),
                    new File("/data/user_de/0/" + PACKAGE_NAME + "/files/injected_image.jpg"),
                };
                
                for (File fallback : fallbackPaths) {
                    if (fallback.exists() && fallback.canRead()) {
                        path = fallback.getAbsolutePath();
                        Logger.i(TAG, "Using fallback image path: " + path);
                        break;
                    }
                }
            }
            
            if (path == null) {
                Logger.w(TAG, "No readable image file found at any location");
                return null;
            }

            Bitmap bitmap = BitmapFactory.decodeFile(path);
            if (bitmap == null) {
                Logger.e(TAG, "Failed to decode bitmap from: " + path);
                return null;
            }

            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream);
            byte[] data = stream.toByteArray();
            bitmap.recycle();
            
            Logger.i(TAG, "Loaded injected image: " + data.length + " bytes from " + path);
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