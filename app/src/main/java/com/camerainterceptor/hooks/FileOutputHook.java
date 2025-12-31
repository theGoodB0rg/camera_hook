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
import java.io.FilterOutputStream;
import java.io.IOException;
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
    
    // Track URIs that are image saves (from ContentResolver.insert)
    private static final ThreadLocal<Uri> pendingImageUri = new ThreadLocal<>();

    public FileOutputHook(HookDispatcher dispatcher) {
        this.dispatcher = dispatcher;
        initHooks();
    }

    private void initHooks() {
        try {
            Logger.i(TAG, "Initializing file output hooks");

            // Hook FileOutputStream write methods for JPEG files
            hookFileOutputStream();
            
            // Hook ContentResolver for MediaStore saves (Google Camera, etc.)
            hookContentResolver();
            
            // Hook ContentResolver.openOutputStream for MediaStore writes
            hookContentResolverOpenOutputStream();
            
            // Hook Bitmap.compress which many apps use
            hookBitmapCompress();
            
            // Hook generic OutputStream.write for wrapped streams
            hookOutputStreamWrite();

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
            // Hook ContentResolver.insert for MediaStore saves - track when an image entry is created
            XposedHelpers.findAndHookMethod(ContentResolver.class, "insert",
                    Uri.class, ContentValues.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            Uri tableUri = (Uri) param.args[0];
                            Uri resultUri = (Uri) param.getResult();
                            
                            if (tableUri != null && resultUri != null) {
                                String uriStr = tableUri.toString().toLowerCase();
                                if (uriStr.contains("images") || uriStr.contains("media")) {
                                    ContentValues values = (ContentValues) param.args[1];
                                    String displayName = values != null ? 
                                            values.getAsString(MediaStore.Images.Media.DISPLAY_NAME) : "unknown";
                                    String mimeType = values != null ?
                                            values.getAsString(MediaStore.Images.Media.MIME_TYPE) : "unknown";
                                    
                                    Logger.i(TAG, "ContentResolver.insert created image entry: " + displayName + 
                                            " (mime: " + mimeType + ") -> " + resultUri);
                                    
                                    // Mark this URI as pending image save
                                    if (dispatcher.isInjectionEnabled()) {
                                        pendingImageUri.set(resultUri);
                                        Logger.i(TAG, "Marked URI for interception: " + resultUri);
                                    }
                                }
                            }
                        }
                    });

        } catch (Throwable t) {
            Logger.e(TAG, "Failed to hook ContentResolver.insert: " + t.getMessage());
        }
    }
    
    private void hookContentResolverOpenOutputStream() {
        try {
            // Hook openOutputStream(Uri) - this is what Google Camera uses
            XposedHelpers.findAndHookMethod(ContentResolver.class, "openOutputStream",
                    Uri.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            Uri uri = (Uri) param.args[0];
                            OutputStream originalStream = (OutputStream) param.getResult();
                            
                            if (uri != null && originalStream != null && dispatcher.isInjectionEnabled()) {
                                String uriStr = uri.toString().toLowerCase();
                                if (uriStr.contains("images") || uriStr.contains("media") || 
                                    uriStr.contains("dcim") || uriStr.contains("camera")) {
                                    Logger.i(TAG, "ContentResolver.openOutputStream for image URI: " + uri);
                                    
                                    // Wrap the output stream to intercept writes
                                    OutputStream wrappedStream = new InterceptingOutputStream(originalStream, dispatcher, uri.toString());
                                    param.setResult(wrappedStream);
                                    Logger.i(TAG, "Wrapped OutputStream for image interception");
                                }
                            }
                        }
                    });
            
            // Also hook the version with mode parameter
            XposedHelpers.findAndHookMethod(ContentResolver.class, "openOutputStream",
                    Uri.class, String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            Uri uri = (Uri) param.args[0];
                            OutputStream originalStream = (OutputStream) param.getResult();
                            
                            if (uri != null && originalStream != null && dispatcher.isInjectionEnabled()) {
                                String uriStr = uri.toString().toLowerCase();
                                if (uriStr.contains("images") || uriStr.contains("media") ||
                                    uriStr.contains("dcim") || uriStr.contains("camera")) {
                                    Logger.i(TAG, "ContentResolver.openOutputStream (with mode) for image URI: " + uri);
                                    
                                    // Wrap the output stream to intercept writes
                                    OutputStream wrappedStream = new InterceptingOutputStream(originalStream, dispatcher, uri.toString());
                                    param.setResult(wrappedStream);
                                    Logger.i(TAG, "Wrapped OutputStream for image interception");
                                }
                            }
                        }
                    });

        } catch (Throwable t) {
            Logger.e(TAG, "Failed to hook ContentResolver.openOutputStream: " + t.getMessage());
        }
    }
    
    private void hookOutputStreamWrite() {
        try {
            // Hook OutputStream.write(byte[], int, int) to catch bulk writes
            XposedBridge.hookAllMethods(OutputStream.class, "write", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (Boolean.TRUE.equals(isIntercepting.get())) return;
                    if (HookDispatcher.isCurrentlyLoadingImage()) return;
                    
                    // Only process if we have a pending image URI
                    Uri pending = pendingImageUri.get();
                    if (pending == null) return;
                    
                    // Check if this is a byte array write with JPEG signature
                    if (param.args.length >= 1 && param.args[0] instanceof byte[]) {
                        byte[] data = (byte[]) param.args[0];
                        if (data != null && data.length > 100 && isJpegData(data)) {
                            Logger.i(TAG, "Detected JPEG write to pending URI: " + pending);
                            
                            if (dispatcher.isInjectionEnabled()) {
                                byte[] injectedData = dispatcher.getPreSelectedImageBytes();
                                if (injectedData != null && injectedData.length > 0) {
                                    param.args[0] = injectedData;
                                    if (param.args.length >= 3) {
                                        param.args[1] = 0; // offset
                                        param.args[2] = injectedData.length; // length
                                    }
                                    Logger.i(TAG, "SUCCESS! Replaced JPEG data via OutputStream.write (" + injectedData.length + " bytes)");
                                    pendingImageUri.remove(); // Clear after injection
                                }
                            }
                        }
                    }
                }
            });
        } catch (Throwable t) {
            Logger.e(TAG, "Failed to hook OutputStream.write: " + t.getMessage());
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
                            // Skip if we're in our own image loading code
                            if (Boolean.TRUE.equals(isIntercepting.get())) return;
                            if (HookDispatcher.isCurrentlyLoadingImage()) return;
                            
                            String targetPackage = dispatcher.getLoadPackageParam().packageName;
                            if (!dispatcher.isPackageAllowed(targetPackage)) {
                                return;
                            }
                            
                            if (!dispatcher.isInjectionEnabled()) {
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
                            if (injectedData != null && injectedData.length > 0) {
                                try {
                                    isIntercepting.set(true);
                                    outputStream.write(injectedData);
                                    param.setResult(true);
                                    Logger.i(TAG, "SUCCESS! Injected image via Bitmap.compress (" + injectedData.length + " bytes)");
                                } catch (Throwable t) {
                                    Logger.e(TAG, "Failed to write injected data: " + t.getMessage());
                                } finally {
                                    isIntercepting.set(false);
                                }
                            } else {
                                Logger.w(TAG, "No injected data available from getPreSelectedImageBytes");
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
    
    /**
     * Custom OutputStream wrapper that intercepts image data written to MediaStore
     */
    private static class InterceptingOutputStream extends FilterOutputStream {
        private final HookDispatcher dispatcher;
        private final String uriString;
        private final ByteArrayOutputStream buffer;
        private boolean injected = false;
        private boolean firstWrite = true;
        
        public InterceptingOutputStream(OutputStream out, HookDispatcher dispatcher, String uriString) {
            super(out);
            this.dispatcher = dispatcher;
            this.uriString = uriString;
            this.buffer = new ByteArrayOutputStream();
            Logger.d(TAG, "InterceptingOutputStream created for: " + uriString);
        }
        
        @Override
        public void write(int b) throws IOException {
            if (!injected) {
                buffer.write(b);
                // Check if we have enough data to detect JPEG
                if (buffer.size() >= 3 && firstWrite) {
                    firstWrite = false;
                    byte[] data = buffer.toByteArray();
                    if (isJpegHeader(data)) {
                        Logger.i(TAG, "Detected JPEG stream write to: " + uriString);
                    }
                }
            }
            super.write(b);
        }
        
        @Override
        public void write(byte[] b) throws IOException {
            write(b, 0, b.length);
        }
        
        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            if (HookDispatcher.isCurrentlyLoadingImage()) {
                super.write(b, off, len);
                return;
            }
            
            if (!injected && b != null && len > 100) {
                // Check if this is JPEG data
                if (off == 0 && len >= 3 && isJpegHeader(b)) {
                    Logger.i(TAG, "Intercepting JPEG write (" + len + " bytes) to: " + uriString);
                    
                    if (dispatcher.isInjectionEnabled()) {
                        byte[] injectedData = dispatcher.getPreSelectedImageBytes();
                        if (injectedData != null && injectedData.length > 0) {
                            // Write our injected data instead
                            super.write(injectedData, 0, injectedData.length);
                            injected = true;
                            Logger.i(TAG, "SUCCESS! Injected image via InterceptingOutputStream (" + 
                                    injectedData.length + " bytes, replaced " + len + " bytes)");
                            return; // Don't write original data
                        }
                    }
                }
            }
            
            // Pass through original data if not injected
            if (!injected) {
                super.write(b, off, len);
            }
        }
        
        @Override
        public void close() throws IOException {
            if (injected) {
                Logger.i(TAG, "InterceptingOutputStream closed (injection was successful)");
            }
            super.close();
        }
        
        private static boolean isJpegHeader(byte[] data) {
            if (data == null || data.length < 3) return false;
            return (data[0] & 0xFF) == 0xFF && 
                   (data[1] & 0xFF) == 0xD8 && 
                   (data[2] & 0xFF) == 0xFF;
        }
    }
}
