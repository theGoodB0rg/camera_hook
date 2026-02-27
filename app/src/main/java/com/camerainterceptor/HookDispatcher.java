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
import java.lang.ref.SoftReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class HookDispatcher {
    private static final String TAG = "HookDispatcher";
    private static final String PACKAGE_NAME = "com.camerainterceptor";
    private static final String PREFS_NAME = "CameraInterceptorPrefs";
    private static final String PREF_IMAGE_PATH = "injected_image_path";
    private static final String PREF_ALLOWED_APPS = "allowed_apps"; // SAFE mode
    private static final String PREF_DEEP_APPS = "deep_apps"; // DEEP mode
    private static final String PREF_INJECTION_MODE = "injection_mode"; // Legacy global setting

    // World-readable external path - must match ImagePickerActivity
    private static final String EXTERNAL_IMAGE_PATH = "/sdcard/.camerainterceptor/injected_image.jpg";

    // Edge case handling constants
    private static final long MIN_INJECTION_INTERVAL_MS = 100; // Minimum 100ms between injections
    private static final int MAX_IMAGE_SIZE_BYTES = 10 * 1024 * 1024; // 10MB max image size
    private static final long CACHE_VALIDITY_MS = 30000; // Cache valid for 30 seconds

    // Rate limiting for rapid captures
    private static final AtomicLong lastInjectionTime = new AtomicLong(0);

    // Cached image data with soft reference (allows GC under memory pressure)
    private static SoftReference<byte[]> cachedImageData = new SoftReference<>(null);
    private static SoftReference<Bitmap> cachedBitmap = new SoftReference<>(null);
    private static long cachedImageTimestamp = 0;
    private static String cachedImagePath = null;

    private final Context context;
    private final XC_LoadPackage.LoadPackageParam lpparam;
    private final List<Object> registeredHooks;
    private final Map<String, Object> sharedData;
    private final Handler mainHandler;
    private XSharedPreferences prefs;
    private com.camerainterceptor.processor.ViewfinderManager viewfinderManager;

    public HookDispatcher(Context context, XC_LoadPackage.LoadPackageParam lpparam) {
        this.context = context;
        this.lpparam = lpparam;
        this.registeredHooks = new ArrayList<>();
        this.sharedData = new HashMap<>();
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.viewfinderManager = new com.camerainterceptor.processor.ViewfinderManager(this);

        // Initialize XSharedPrefs
        this.prefs = new XSharedPreferences(PACKAGE_NAME, PREFS_NAME);
        this.prefs.makeWorldReadable();

        loadInjectionConfiguration();

        Logger.i(TAG, "HookDispatcher initialized for package: " + lpparam.packageName);
    }

    private void loadInjectionConfiguration() {
        if (prefs == null)
            return;
        prefs.reload();

        boolean isDeep = false;
        Set<String> deepApps = prefs.getStringSet(PREF_DEEP_APPS, null);
        if (deepApps != null && deepApps.contains(lpparam.packageName)) {
            isDeep = true;
        } else {
            // Fallback to legacy global setting if not explicitly set in deep_apps
            // but ONLY if the app is in allowed_apps
            Set<String> allowedApps = prefs.getStringSet(PREF_ALLOWED_APPS, null);
            if (allowedApps != null && allowedApps.contains(lpparam.packageName)) {
                String modeStr = prefs.getString(PREF_INJECTION_MODE, "0");
                if ("1".equals(modeStr)) {
                    isDeep = true;
                }
            }
        }

        com.camerainterceptor.state.HookState.setInjectionMode(
                isDeep ? com.camerainterceptor.state.HookState.InjectionMode.DEEP_SURFACE
                        : com.camerainterceptor.state.HookState.InjectionMode.SAFE);
        Logger.i(TAG, "Resolved Injection Mode for " + lpparam.packageName + ": " +
                com.camerainterceptor.state.HookState.getInjectionMode());
    }

    public boolean isDeepSurfaceModeEnabled() {
        boolean deepEnabled = com.camerainterceptor.state.HookState
                .getInjectionMode() == com.camerainterceptor.state.HookState.InjectionMode.DEEP_SURFACE;
        // Even if deep is globally enabled, make sure it's not overridden by specific
        // app logic if needed
        return deepEnabled;
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

        // Check all possible paths for the injected image
        // /data/local/tmp is first because it's most reliably world-readable
        String[] possiblePaths = new String[] {
                "/data/local/tmp/camerainterceptor_image.jpg",
                "/sdcard/.camerainterceptor/injected_image.jpg",
                "/storage/emulated/0/.camerainterceptor/injected_image.jpg",
                "/storage/sdcard0/.camerainterceptor/injected_image.jpg",
                "/mnt/sdcard/.camerainterceptor/injected_image.jpg",
                "/data/media/0/.camerainterceptor/injected_image.jpg",
                "/data/user/0/" + PACKAGE_NAME + "/files/injected_image.jpg",
                "/data/data/" + PACKAGE_NAME + "/files/injected_image.jpg",
                "/data/user_de/0/" + PACKAGE_NAME + "/files/injected_image.jpg",
        };

        for (String p : possiblePaths) {
            File f = new File(p);
            boolean exists = f.exists();
            boolean canRead = exists && f.canRead();
            if (exists) {
                Logger.d(TAG, "Path check: " + p + " exists=" + exists + " canRead=" + canRead);
            }
            if (canRead) {
                Logger.i(TAG, "Found readable injected image at: " + p);
                return true;
            }
        }

        // Try to get image path from prefs
        if (prefs != null) {
            prefs.reload();
            String path = prefs.getString(PREF_IMAGE_PATH, null);
            if (path != null) {
                File f = new File(path);
                if (f.exists() && f.canRead()) {
                    Logger.i(TAG, "Found injected image from prefs: " + path);
                    return true;
                }
            }
        }

        Logger.d(TAG, "No injected image path configured or file missing (checked all paths)");
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

    /**
     * Check if profiling/debug mode is enabled.
     * When enabled, hooks log call stacks without injecting images.
     */
    public boolean isProfilingEnabled() {
        if (prefs == null)
            return false;
        prefs.reload();
        return prefs.getBoolean("profiling_enabled", false);
    }

    /**
     * Check if Viewfinder Spoofing (Phase 3) is enabled in settings.
     */
    public boolean isViewfinderSpoofingEnabled() {
        if (prefs == null)
            return true; // Default to true if prefs unavailable
        prefs.reload();
        return prefs.getBoolean("spoof_viewfinder", true);
    }

    private boolean isPackageAllowedInPrefs(String packageName) {
        if (prefs == null)
            return true;

        Set<String> allowed = prefs.getStringSet(PREF_ALLOWED_APPS, null);
        Set<String> deep = prefs.getStringSet(PREF_DEEP_APPS, null);

        boolean isAllowed = (allowed != null && allowed.contains(packageName)) ||
                (deep != null && deep.contains(packageName));

        if (allowed == null && deep == null) {
            // Empty lists / not configured means no filter applied; allow all
            return true;
        }
        return isAllowed;
    }

    /**
     * Load the pre-selected image from file
     */
    public void getPreSelectedImage(HookCallback callback) {
        try {
            if (!isPackageAllowedInPrefs(lpparam.packageName)) {
                Logger.i(TAG, "Package not in allowed list: " + lpparam.packageName);
                if (callback != null) {
                    callback.onImageSelectionCancelled();
                }
                return;
            }

            // Use the same path resolution as getPreSelectedImageBytes
            String path = findInjectableImagePath();

            if (path == null) {
                Logger.w(TAG, "getPreSelectedImage: No image path found");
                if (callback != null)
                    callback.onImageSelectionCancelled();
                return;
            }

            Logger.i(TAG, "Loading pre-selected image from: " + path);

            // Set flag to prevent recursion when reading
            isLoadingImage.set(true);

            try {
                // Read raw bytes from file
                File file = new File(path);
                byte[] imageData = new byte[(int) file.length()];

                java.io.FileInputStream fis = new java.io.FileInputStream(file);
                int bytesRead = 0;
                int totalRead = 0;
                while (totalRead < imageData.length
                        && (bytesRead = fis.read(imageData, totalRead, imageData.length - totalRead)) != -1) {
                    totalRead += bytesRead;
                }
                fis.close();

                // Get dimensions by decoding just the bounds
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(path, opts);

                // Create metadata
                ImageUtils.ImageMetadata metadata = ImageUtils.createFakeMetadata();
                metadata.width = opts.outWidth;
                metadata.height = opts.outHeight;

                Logger.i(TAG, "Successfully loaded image: " + imageData.length + " bytes, " +
                        opts.outWidth + "x" + opts.outHeight);

                if (callback != null) {
                    callback.onImageSelected(imageData, metadata);
                }
            } finally {
                isLoadingImage.set(false);
            }

        } catch (Throwable t) {
            Logger.e(TAG, "Error loading pre-selected image: " + t.getMessage());
            Logger.logStackTrace(TAG, t);
            if (callback != null) {
                callback.onError(t);
            }
        }
    }

    /**
     * Find the path to an injectable image, checking all possible locations
     */
    private String findInjectableImagePath() {
        // Check all possible paths
        String[] possiblePaths = new String[] {
                "/data/local/tmp/camerainterceptor_image.jpg",
                "/sdcard/.camerainterceptor/injected_image.jpg",
                "/storage/emulated/0/.camerainterceptor/injected_image.jpg",
                "/storage/sdcard0/.camerainterceptor/injected_image.jpg",
                "/mnt/sdcard/.camerainterceptor/injected_image.jpg",
                "/data/media/0/.camerainterceptor/injected_image.jpg",
                "/data/user/0/" + PACKAGE_NAME + "/files/injected_image.jpg",
                "/data/data/" + PACKAGE_NAME + "/files/injected_image.jpg",
                "/data/user_de/0/" + PACKAGE_NAME + "/files/injected_image.jpg",
        };

        for (String p : possiblePaths) {
            File f = new File(p);
            if (f.exists() && f.canRead()) {
                Logger.d(TAG, "findInjectableImagePath: found at " + p);
                return p;
            }
        }

        // Try prefs as last resort
        if (prefs != null) {
            prefs.reload();
            String path = prefs.getString(PREF_IMAGE_PATH, null);
            if (path != null) {
                File f = new File(path);
                if (f.exists() && f.canRead()) {
                    Logger.d(TAG, "findInjectableImagePath: found from prefs at " + path);
                    return path;
                }
            }
        }

        return null;
    }

    /**
     * Gets the injected image bytes scaled to a specific resolution.
     * 
     * @param targetWidth  The width requested by the app
     * @param targetHeight The height requested by the app
     * @return JPEG byte array or null if failed
     */
    public byte[] getInjectedImageBytes(int targetWidth, int targetHeight) {
        if (!isPackageAllowedInPrefs(lpparam.packageName)) {
            return null;
        }

        String path = findInjectableImagePath();
        if (path == null) {
            return null;
        }

        long now = System.currentTimeMillis();
        byte[] cached = cachedImageData.get();

        // Check if we have a cached version with SAME resolution
        // Note: For simplicity, we cache only one version. If resolution changes, we
        // re-scale.
        if (path.equals(cachedImagePath) && cached != null && (now - cachedImageTimestamp) < CACHE_VALIDITY_MS) {
            // Check if resolution matches (Basic check)
            return cached;
        }

        try {
            Bitmap src = getPreSelectedBitmap();
            if (src == null)
                return null;

            Bitmap finalBitmap = src;
            boolean wasScaled = false;

            // Perform scaling if requested dimensions differ from source
            if (targetWidth > 0 && targetHeight > 0
                    && (src.getWidth() != targetWidth || src.getHeight() != targetHeight)) {
                Logger.i(TAG, "Scaling capture image from " + src.getWidth() + "x" + src.getHeight() +
                        " to " + targetWidth + "x" + targetHeight);
                finalBitmap = Bitmap.createScaledBitmap(src, targetWidth, targetHeight, true);
                wasScaled = true;
            }

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            finalBitmap.compress(Bitmap.CompressFormat.JPEG, 95, bos);
            byte[] data = bos.toByteArray();

            if (wasScaled && finalBitmap != src) {
                finalBitmap.recycle();
            }

            // Update cache
            cachedImageData = new SoftReference<>(data);
            cachedImagePath = path;
            cachedImageTimestamp = now;

            return data;
        } catch (Throwable t) {
            Logger.e(TAG, "Error generating scaled injected image: " + t.getMessage());
            return null;
        }
    }

    /**
     * Gets the injected image in NV21 YUV format, scaled to requested resolution.
     * Useful for Camera2 YUV ImageReader interception.
     */
    public byte[] getInjectedYUVData(int targetWidth, int targetHeight) {
        if (!isPackageAllowedInPrefs(lpparam.packageName)) {
            return null;
        }

        try {
            Bitmap src = getPreSelectedBitmap();
            if (src == null)
                return null;

            // Use native processor to convert to NV21 (includes scaling/cropping)
            byte[] yuv = com.camerainterceptor.processor.NativeImageProcessor.processBitmapToNV21(src, targetWidth,
                    targetHeight);

            if (yuv != null) {
                Logger.d(TAG, "Generated " + targetWidth + "x" + targetHeight + " NV21 buffer natively");
            }
            return yuv;
        } catch (Throwable t) {
            Logger.e(TAG, "Error generating YUV data: " + t.getMessage());
            return null;
        }
    }

    // Flag to prevent recursion when our own code triggers hooked methods
    private static final ThreadLocal<Boolean> isLoadingImage = new ThreadLocal<Boolean>() {
        @Override
        protected Boolean initialValue() {
            return false;
        }
    };

    public static boolean isCurrentlyLoadingImage() {
        return Boolean.TRUE.equals(isLoadingImage.get());
    }

    /**
     * Load pre-selected image synchronously (for Camera2 hooks)
     * Reads raw bytes from file - validates JPEG format and converts if needed
     * Includes caching for rapid captures and memory-safe loading
     */
    public byte[] getPreSelectedImageBytes() {
        if (!isPackageAllowedInPrefs(lpparam.packageName)) {
            return null;
        }

        // Prevent recursion
        if (Boolean.TRUE.equals(isLoadingImage.get())) {
            return null;
        }

        // Rate limiting for rapid captures
        long now = System.currentTimeMillis();
        long lastTime = lastInjectionTime.get();
        if (now - lastTime < MIN_INJECTION_INTERVAL_MS) {
            Logger.d(TAG, "Rate limiting: too fast, using cached data if available");
            byte[] cached = cachedImageData.get();
            if (cached != null) {
                return cached;
            }
            // If no cache, proceed anyway but log warning
            Logger.w(TAG, "Rate limit triggered but no cache available, proceeding with load");
        }

        // Check if we have valid cached data
        String currentPath = findInjectableImagePath();
        if (currentPath != null && currentPath.equals(cachedImagePath)) {
            byte[] cached = cachedImageData.get();
            if (cached != null && (now - cachedImageTimestamp) < CACHE_VALIDITY_MS) {
                Logger.d(TAG, "Using cached image data (" + cached.length + " bytes)");
                lastInjectionTime.set(now);
                return cached;
            }
        }

        try {
            isLoadingImage.set(true);

            if (currentPath == null) {
                Logger.w(TAG, "getPreSelectedImageBytes: No readable image file found");
                return null;
            }

            // Check file size before loading
            File file = new File(currentPath);
            long fileSize = file.length();

            if (fileSize > MAX_IMAGE_SIZE_BYTES) {
                Logger.e(TAG, "Image file too large: " + fileSize + " bytes (max: " + MAX_IMAGE_SIZE_BYTES + ")");
                return null;
            }

            if (fileSize == 0) {
                Logger.e(TAG, "Image file is empty: " + currentPath);
                return null;
            }

            // Read raw file bytes
            byte[] data = new byte[(int) fileSize];

            java.io.FileInputStream fis = new java.io.FileInputStream(file);
            int bytesRead = 0;
            int totalRead = 0;
            while (totalRead < data.length && (bytesRead = fis.read(data, totalRead, data.length - totalRead)) != -1) {
                totalRead += bytesRead;
            }
            fis.close();

            if (totalRead != data.length) {
                Logger.e(TAG, "Failed to read complete file: read " + totalRead + " of " + data.length);
                return null;
            }

            // Validate that data is JPEG (magic bytes: FF D8 FF)
            if (!isValidJpeg(data)) {
                Logger.w(TAG, "File is not JPEG format, converting...");
                data = convertToJpeg(data);
                if (data == null) {
                    Logger.e(TAG, "Failed to convert image to JPEG");
                    return null;
                }
            }

            // Cache the loaded data
            cachedImageData = new SoftReference<>(data);
            cachedImageTimestamp = now;
            cachedImagePath = currentPath;
            lastInjectionTime.set(now);

            Logger.i(TAG, "Loaded and cached injected image: " + data.length + " bytes from " + currentPath);
            return data;
        } catch (OutOfMemoryError oom) {
            Logger.e(TAG, "Out of memory loading image - clearing cache and retrying with smaller allocation");
            clearImageCache();
            System.gc();
            return null;
        } catch (Exception e) {
            Logger.e(TAG, "Error reading image sync: " + e.getMessage());
            return null;
        } finally {
            isLoadingImage.set(false);
        }
    }

    /**
     * Clear the cached image data
     */
    public static void clearImageCache() {
        cachedImageData = new SoftReference<>(null);
        cachedBitmap = new SoftReference<>(null);
        cachedImageTimestamp = 0;
        cachedImagePath = null;
        Logger.d(TAG, "Image cache cleared");
    }

    public com.camerainterceptor.processor.ViewfinderManager getViewfinderManager() {
        return viewfinderManager;
    }

    /**
     * Gets the currently selected image as a Bitmap.
     * Uses caching to ensure fast access for the viewfinder loop.
     */
    public Bitmap getPreSelectedBitmap() {
        if (!isPackageAllowedInPrefs(lpparam.packageName)) {
            return null;
        }

        String path = findInjectableImagePath();
        if (path == null) {
            return null;
        }

        long now = System.currentTimeMillis();
        Bitmap cached = cachedBitmap.get();

        if (path.equals(cachedImagePath) && cached != null && (now - cachedImageTimestamp) < CACHE_VALIDITY_MS) {
            return cached;
        }

        // Cache miss or invalid
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inMutable = true; // Required for native manipulation in some cases
            Bitmap bitmap = BitmapFactory.decodeFile(path, opts);

            if (bitmap != null) {
                cachedBitmap = new SoftReference<>(bitmap);
                cachedImagePath = path;
                cachedImageTimestamp = now;
                Logger.i(TAG, "Cached new bitmap for viewfinder: " + path);
            }
            return bitmap;
        } catch (Throwable t) {
            Logger.e(TAG, "Failed to decode bitmap for viewfinder: " + t.getMessage());
            return null;
        }
    }

    /**
     * Check if byte array is valid JPEG data
     */
    private boolean isValidJpeg(byte[] data) {
        if (data == null || data.length < 3)
            return false;
        // JPEG magic bytes: FF D8 FF
        return (data[0] & 0xFF) == 0xFF &&
                (data[1] & 0xFF) == 0xD8 &&
                (data[2] & 0xFF) == 0xFF;
    }

    /**
     * Convert any image data to JPEG format
     */
    private byte[] convertToJpeg(byte[] inputData) {
        try {
            Bitmap bitmap = BitmapFactory.decodeByteArray(inputData, 0, inputData.length);
            if (bitmap == null) {
                Logger.e(TAG, "Failed to decode image for conversion");
                return null;
            }

            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream);
            byte[] jpegData = stream.toByteArray();
            bitmap.recycle();

            Logger.i(TAG, "Converted image to JPEG: " + jpegData.length + " bytes");
            return jpegData;
        } catch (Exception e) {
            Logger.e(TAG, "Error converting to JPEG: " + e.getMessage());
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