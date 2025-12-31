package com.camerainterceptor.hooks;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;

import com.camerainterceptor.HookDispatcher;
import com.camerainterceptor.utils.Logger;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.OutputStream;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Hooks for file output operations to intercept when camera apps save images.
 * This is a more reliable approach than trying to modify ImageReader buffers.
 */
public class FileOutputHook {
    private static final String TAG = "FileOutputHook";
    private final HookDispatcher dispatcher;
    
    // Track if we're currently in an interception to avoid recursion
    private static final ThreadLocal<Boolean> isIntercepting = new ThreadLocal<Boolean>() {
        @Override
        protected Boolean initialValue() {
            return false;
        }
    };

    public FileOutputHook(HookDispatcher dispatcher) {
        this.dispatcher = dispatcher;
        initHooks();
    }

    private void initHooks() {
        try {
            Logger.i(TAG, "Initializing file output hooks");

            // Hook FileOutputStream write methods for JPEG files
            hookFileOutputStream();
            
            // Hook ContentResolver insert for MediaStore saves
            hookContentResolver();
            
            // Hook Bitmap.compress which many apps use
            hookBitmapCompress();

            Logger.i(TAG, "File output hooks initialized successfully");
        } catch (Throwable t) {
            Logger.e(TAG, "Failed to initialize file output hooks: " + t.getMessage());
            Logger.logStackTrace(TAG, t);
        }
    }

    private void hookFileOutputStream() {
        try {
            // Hook the constructor that takes a File to track what file is being written
            XposedHelpers.findAndHookConstructor(FileOutputStream.class, File.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            File file = (File) param.args[0];
                            if (file != null && isImageFile(file.getName())) {
                                XposedHelpers.setAdditionalInstanceField(param.thisObject, "targetFile", file);
                                Logger.d(TAG, "FileOutputStream opened for image: " + file.getAbsolutePath());
                            }
                        }
                    });

            XposedHelpers.findAndHookConstructor(FileOutputStream.class, String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            String path = (String) param.args[0];
                            if (path != null && isImageFile(path)) {
                                XposedHelpers.setAdditionalInstanceField(param.thisObject, "targetFile", new File(path));
                                Logger.d(TAG, "FileOutputStream opened for image path: " + path);
                            }
                        }
                    });

            // Hook write(byte[]) to intercept full image writes
            XposedHelpers.findAndHookMethod(FileOutputStream.class, "write", byte[].class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if (Boolean.TRUE.equals(isIntercepting.get())) return;
                            
                            File targetFile = (File) XposedHelpers.getAdditionalInstanceField(param.thisObject, "targetFile");
                            if (targetFile == null) return;
                            
                            byte[] data = (byte[]) param.args[0];
                            if (data == null || data.length < 100) return;
                            
                            // Check if this looks like JPEG data
                            if (isJpegData(data)) {
                                tryInjectImage(param, data, targetFile);
                            }
                        }
                    });

        } catch (Throwable t) {
            Logger.e(TAG, "Failed to hook FileOutputStream: " + t.getMessage());
        }
    }

    private void hookContentResolver() {
        try {
            // Hook ContentResolver.insert for MediaStore saves
            XposedHelpers.findAndHookMethod(ContentResolver.class, "insert",
                    Uri.class, ContentValues.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            Uri uri = (Uri) param.args[0];
                            if (uri != null && uri.toString().contains("images")) {
                                ContentValues values = (ContentValues) param.args[1];
                                if (values != null) {
                                    String displayName = values.getAsString(MediaStore.Images.Media.DISPLAY_NAME);
                                    Logger.d(TAG, "ContentResolver.insert for image: " + displayName);
                                }
                            }
                        }
                    });

        } catch (Throwable t) {
            Logger.e(TAG, "Failed to hook ContentResolver: " + t.getMessage());
        }
    }

    private void hookBitmapCompress() {
        try {
            // Hook Bitmap.compress - this is commonly used to save camera images
            XposedHelpers.findAndHookMethod(Bitmap.class, "compress",
                    Bitmap.CompressFormat.class, int.class, OutputStream.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if (Boolean.TRUE.equals(isIntercepting.get())) return;
                            
                            String targetPackage = dispatcher.getLoadPackageParam().packageName;
                            if (!dispatcher.isPackageAllowed(targetPackage)) {
                                return;
                            }
                            
                            if (!dispatcher.isInjectionEnabled()) {
                                Logger.d(TAG, "Bitmap.compress: Injection disabled");
                                return;
                            }

                            Bitmap.CompressFormat format = (Bitmap.CompressFormat) param.args[0];
                            OutputStream outputStream = (OutputStream) param.args[2];
                            
                            // Only intercept JPEG compression
                            if (format != Bitmap.CompressFormat.JPEG) {
                                return;
                            }

                            // Check if this is writing to a file
                            File targetFile = null;
                            if (outputStream instanceof FileOutputStream) {
                                targetFile = (File) XposedHelpers.getAdditionalInstanceField(outputStream, "targetFile");
                            }
                            
                            Logger.i(TAG, "Intercepting Bitmap.compress to JPEG" + 
                                    (targetFile != null ? " -> " + targetFile.getName() : ""));

                            byte[] injectedData = dispatcher.getPreSelectedImageBytes();
                            if (injectedData != null) {
                                try {
                                    isIntercepting.set(true);
                                    outputStream.write(injectedData);
                                    param.setResult(true);
                                    Logger.i(TAG, "Successfully injected image via Bitmap.compress (" + injectedData.length + " bytes)");
                                } catch (Throwable t) {
                                    Logger.e(TAG, "Failed to write injected data: " + t.getMessage());
                                } finally {
                                    isIntercepting.set(false);
                                }
                            }
                        }
                    });

        } catch (Throwable t) {
            Logger.e(TAG, "Failed to hook Bitmap.compress: " + t.getMessage());
        }
    }

    private void tryInjectImage(XC_MethodHook.MethodHookParam param, byte[] originalData, File targetFile) {
        String targetPackage = dispatcher.getLoadPackageParam().packageName;
        if (!dispatcher.isPackageAllowed(targetPackage)) {
            return;
        }
        
        if (!dispatcher.isInjectionEnabled()) {
            Logger.d(TAG, "FileOutputStream write: Injection disabled");
            return;
        }

        Logger.i(TAG, "Intercepting JPEG write to: " + targetFile.getName() + 
                " (original size: " + originalData.length + " bytes)");

        byte[] injectedData = dispatcher.getPreSelectedImageBytes();
        if (injectedData != null) {
            param.args[0] = injectedData;
            Logger.i(TAG, "Replaced image data with injected image (" + injectedData.length + " bytes)");
        } else {
            Logger.w(TAG, "No injected image available");
        }
    }

    private boolean isImageFile(String fileName) {
        if (fileName == null) return false;
        String lower = fileName.toLowerCase();
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || 
               lower.endsWith(".png") || lower.contains("img_") || 
               lower.contains("photo") || lower.contains("camera") ||
               lower.contains("dcim");
    }

    private boolean isJpegData(byte[] data) {
        if (data == null || data.length < 3) return false;
        // JPEG magic bytes: FF D8 FF
        return (data[0] & 0xFF) == 0xFF && 
               (data[1] & 0xFF) == 0xD8 && 
               (data[2] & 0xFF) == 0xFF;
    }
}
