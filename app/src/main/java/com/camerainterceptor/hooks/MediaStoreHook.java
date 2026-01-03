package com.camerainterceptor.hooks;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;

import com.camerainterceptor.HookDispatcher;
import com.camerainterceptor.utils.Logger;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.OutputStream;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * Hooks for MediaStore API operations to intercept modern image save paths.
 * This covers Android 10+ scoped storage and MediaStore-based saves.
 * 
 * Target coverage:
 * - MediaStore.Images.Media.insertImage() - Legacy but still used (~5% of apps)
 * - ContentResolver.openFileDescriptor() - Modern scoped storage
 */
public class MediaStoreHook {
    private static final String TAG = "MediaStoreHook";
    private final HookDispatcher dispatcher;
    
    // Track if we're currently in an interception to avoid recursion
    private static final ThreadLocal<Boolean> isIntercepting = new ThreadLocal<Boolean>() {
        @Override
        protected Boolean initialValue() {
            return false;
        }
    };

    public MediaStoreHook(HookDispatcher dispatcher) {
        this.dispatcher = dispatcher;
        initHooks();
    }

    private void initHooks() {
        try {
            Logger.i(TAG, "Initializing MediaStore hooks");

            // Hook MediaStore.Images.Media.insertImage() methods
            hookInsertImage();
            
            // Hook ContentResolver.openFileDescriptor() for scoped storage
            hookOpenFileDescriptor();

            Logger.i(TAG, "MediaStore hooks initialized successfully");
        } catch (Throwable t) {
            Logger.e(TAG, "Failed to initialize MediaStore hooks: " + t.getMessage());
            Logger.logStackTrace(TAG, t);
        }
    }

    /**
     * Hook MediaStore.Images.Media.insertImage() - all overloads
     * This is a legacy API but still used by some apps (~5%)
     */
    private void hookInsertImage() {
        try {
            Class<?> mediaClass = MediaStore.Images.Media.class;
            
            // Overload 1: insertImage(ContentResolver, Bitmap, String, String)
            // Returns the URL to the newly created image
            XposedHelpers.findAndHookMethod(mediaClass, "insertImage",
                    ContentResolver.class, Bitmap.class, String.class, String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if (Boolean.TRUE.equals(isIntercepting.get())) return;
                            if (HookDispatcher.isCurrentlyLoadingImage()) return;
                            
                            String targetPackage = dispatcher.getLoadPackageParam().packageName;
                            if (!dispatcher.isPackageAllowed(targetPackage)) return;
                            if (!dispatcher.isInjectionEnabled()) return;
                            
                            ContentResolver cr = (ContentResolver) param.args[0];
                            Bitmap originalBitmap = (Bitmap) param.args[1];
                            String title = (String) param.args[2];
                            String description = (String) param.args[3];
                            
                            Logger.logHookTriggered("MediaStore.insertImage(Bitmap)", 
                                    "MediaStore.Images.Media", "insertImage",
                                    targetPackage, "Title: " + title);
                            
                            // Load our injected image as a Bitmap
                            byte[] injectedData = dispatcher.getPreSelectedImageBytes();
                            if (injectedData != null && injectedData.length > 0) {
                                try {
                                    isIntercepting.set(true);
                                    Bitmap injectedBitmap = BitmapFactory.decodeByteArray(
                                            injectedData, 0, injectedData.length);
                                    
                                    if (injectedBitmap != null) {
                                        // Replace the bitmap parameter
                                        param.args[1] = injectedBitmap;
                                        Logger.logInjectionSuccess("MediaStore.insertImage(Bitmap)", 
                                                title, 
                                                originalBitmap != null ? originalBitmap.getByteCount() : 0,
                                                injectedData.length);
                                    } else {
                                        Logger.logInjectionFailure("MediaStore.insertImage(Bitmap)",
                                                "Failed to decode injected image", null);
                                    }
                                } finally {
                                    isIntercepting.set(false);
                                }
                            } else {
                                Logger.logInjectionFailure("MediaStore.insertImage(Bitmap)",
                                        "No injected data available", null);
                            }
                        }
                    });
            
            Logger.d(TAG, "Hooked MediaStore.insertImage(Bitmap) successfully");
            
            // Overload 2: insertImage(ContentResolver, String, String, String)
            // Inserts an image from a file path
            XposedHelpers.findAndHookMethod(mediaClass, "insertImage",
                    ContentResolver.class, String.class, String.class, String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if (Boolean.TRUE.equals(isIntercepting.get())) return;
                            if (HookDispatcher.isCurrentlyLoadingImage()) return;
                            
                            String targetPackage = dispatcher.getLoadPackageParam().packageName;
                            if (!dispatcher.isPackageAllowed(targetPackage)) return;
                            if (!dispatcher.isInjectionEnabled()) return;
                            
                            String imagePath = (String) param.args[1];
                            String title = (String) param.args[2];
                            String description = (String) param.args[3];
                            
                            Logger.logHookTriggered("MediaStore.insertImage(Path)", 
                                    "MediaStore.Images.Media", "insertImage",
                                    targetPackage, "Path: " + imagePath + ", Title: " + title);
                            
                            // For path-based insertImage, we need to find a world-readable path
                            // to our injected image
                            String injectedPath = findInjectableImagePath();
                            if (injectedPath != null) {
                                try {
                                    isIntercepting.set(true);
                                    // Replace the path parameter
                                    param.args[1] = injectedPath;
                                    Logger.logInjectionSuccess("MediaStore.insertImage(Path)", 
                                            title, -1, -1);
                                } finally {
                                    isIntercepting.set(false);
                                }
                            } else {
                                Logger.logInjectionFailure("MediaStore.insertImage(Path)",
                                        "No injectable image path available", null);
                            }
                        }
                    });
            
            Logger.d(TAG, "Hooked MediaStore.insertImage(Path) successfully");
            
        } catch (NoSuchMethodError e) {
            Logger.w(TAG, "MediaStore.insertImage method not found (may be deprecated on this API level)");
        } catch (Throwable t) {
            Logger.e(TAG, "Failed to hook MediaStore.insertImage: " + t.getMessage());
        }
    }
    
    /**
     * Hook ContentResolver.openFileDescriptor() for scoped storage writes
     * Modern apps on Android 10+ use this to write to MediaStore URIs
     */
    private void hookOpenFileDescriptor() {
        try {
            // Hook openFileDescriptor(Uri, String) - basic version
            XposedHelpers.findAndHookMethod(ContentResolver.class, "openFileDescriptor",
                    Uri.class, String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            handleOpenFileDescriptor(param);
                        }
                    });
            
            Logger.d(TAG, "Hooked ContentResolver.openFileDescriptor(Uri, String) successfully");
            
            // Also hook the version with CancellationSignal (Android API 19+)
            try {
                Class<?> cancellationSignalClass = Class.forName("android.os.CancellationSignal");
                XposedHelpers.findAndHookMethod(ContentResolver.class, "openFileDescriptor",
                        Uri.class, String.class, cancellationSignalClass,
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                handleOpenFileDescriptor(param);
                            }
                        });
                Logger.d(TAG, "Hooked ContentResolver.openFileDescriptor(Uri, String, CancellationSignal) successfully");
            } catch (ClassNotFoundException e) {
                Logger.d(TAG, "CancellationSignal class not found, skipping that overload");
            }
            
        } catch (Throwable t) {
            Logger.e(TAG, "Failed to hook ContentResolver.openFileDescriptor: " + t.getMessage());
        }
    }
    
    /**
     * Handle openFileDescriptor hook - check if it's an image write and track it
     */
    private void handleOpenFileDescriptor(XC_MethodHook.MethodHookParam param) {
        try {
            if (Boolean.TRUE.equals(isIntercepting.get())) return;
            if (HookDispatcher.isCurrentlyLoadingImage()) return;
            
            Uri uri = (Uri) param.args[0];
            String mode = (String) param.args[1];
            ParcelFileDescriptor pfd = (ParcelFileDescriptor) param.getResult();
            
            if (uri == null || pfd == null) return;
            
            // Only intercept write modes
            if (mode == null || !mode.contains("w")) return;
            
            String targetPackage = dispatcher.getLoadPackageParam().packageName;
            if (!dispatcher.isPackageAllowed(targetPackage)) return;
            if (!dispatcher.isInjectionEnabled()) return;
            
            String uriStr = uri.toString().toLowerCase();
            
            // Check if this is an image URI
            if (uriStr.contains("images") || uriStr.contains("media") || 
                uriStr.contains("dcim") || uriStr.contains("camera") ||
                uriStr.contains("photo") || uriStr.contains("picture")) {
                
                Logger.logHookTriggered("ContentResolver.openFileDescriptor", 
                        "ContentResolver", "openFileDescriptor",
                        targetPackage, "URI: " + uri + ", Mode: " + mode);
                
                // For write mode, we need to write our injected image to the file descriptor
                // This is tricky because the app expects to write to the FD
                // We'll write our data first, then close it and return null to prevent app from writing
                
                byte[] injectedData = dispatcher.getPreSelectedImageBytes();
                if (injectedData != null && injectedData.length > 0) {
                    try {
                        isIntercepting.set(true);
                        
                        FileDescriptor fd = pfd.getFileDescriptor();
                        FileOutputStream fos = new FileOutputStream(fd);
                        fos.write(injectedData);
                        fos.flush();
                        // Don't close FOS as it would close the underlying FD
                        
                        Logger.logInjectionSuccess("ContentResolver.openFileDescriptor", 
                                uri.toString(), -1, injectedData.length);
                        
                        // Note: We can't prevent the app from writing more data
                        // This hook is best-effort for FD-based writes
                        
                    } catch (Throwable t) {
                        Logger.logInjectionFailure("ContentResolver.openFileDescriptor",
                                "Failed to write to file descriptor", t);
                    } finally {
                        isIntercepting.set(false);
                    }
                }
            }
        } catch (Throwable t) {
            Logger.e(TAG, "Error in handleOpenFileDescriptor: " + t.getMessage());
        }
    }
    
    /**
     * Find the path to an injectable image file
     */
    private String findInjectableImagePath() {
        String[] possiblePaths = new String[] {
            "/data/local/tmp/camerainterceptor_image.jpg",
            "/sdcard/.camerainterceptor/injected_image.jpg",
            "/storage/emulated/0/.camerainterceptor/injected_image.jpg",
        };
        
        for (String path : possiblePaths) {
            java.io.File f = new java.io.File(path);
            if (f.exists() && f.canRead()) {
                return path;
            }
        }
        
        return null;
    }
}
