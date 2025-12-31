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
                while (totalRead < imageData.length && (bytesRead = fis.read(imageData, totalRead, imageData.length - totalRead)) != -1) {
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
     */
    public byte[] getPreSelectedImageBytes() {
        if (!isPackageAllowedInPrefs(lpparam.packageName)) {
            return null;
        }
        
        // Prevent recursion
        if (Boolean.TRUE.equals(isLoadingImage.get())) {
            return null;
        }

        try {
            isLoadingImage.set(true);
            
            String path = findInjectableImagePath();
            
            if (path == null) {
                Logger.w(TAG, "getPreSelectedImageBytes: No readable image file found");
                return null;
            }

            // Read raw file bytes
            File file = new File(path);
            byte[] data = new byte[(int) file.length()];
            
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
            
            Logger.i(TAG, "Loaded injected image: " + data.length + " bytes from " + path);
            return data;
        } catch (Exception e) {
            Logger.e(TAG, "Error reading image sync: " + e.getMessage());
            return null;
        } finally {
            isLoadingImage.set(false);
        }
    }
    
    /**
     * Check if byte array is valid JPEG data
     */
    private boolean isValidJpeg(byte[] data) {
        if (data == null || data.length < 3) return false;
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