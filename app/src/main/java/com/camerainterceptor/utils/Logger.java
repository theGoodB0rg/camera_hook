// Logger.java 
package com.camerainterceptor.utils;

import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import de.robv.android.xposed.XposedBridge;

/**
 * Comprehensive logging utility for the CameraInterceptor module
 */
public class Logger {
    private static final String MODULE_TAG = "CameraInterceptor";
    private static final boolean LOG_TO_XPOSED = true;
    private static final boolean LOG_TO_ANDROID = true;
    private static final boolean LOG_TO_FILE = true;
    private static final String LOG_FILE_DIR = "CameraInterceptor";
    private static final String LOG_FILE_NAME = "camera_interceptor_log.txt";
    private static final int MAX_LOG_FILE_SIZE = 5 * 1024 * 1024; // 5MB
    
    private static final ThreadPoolExecutor logExecutor = new ThreadPoolExecutor(
            1, 1, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
    
    static {
        // Initialize logging system
        if (LOG_TO_FILE) {
            try {
                File logDir = new File(Environment.getExternalStorageDirectory(), LOG_FILE_DIR);
                if (!logDir.exists() && !logDir.mkdirs()) {
                    Log.e(MODULE_TAG, "Failed to create log directory");
                }
                
                File logFile = new File(logDir, LOG_FILE_NAME);
                if (logFile.exists() && logFile.length() > MAX_LOG_FILE_SIZE) {
                    // Backup old log file
                    File backupFile = new File(logDir, LOG_FILE_NAME + ".bak");
                    if (backupFile.exists()) {
                        backupFile.delete();
                    }
                    logFile.renameTo(backupFile);
                }
                
                // Create a header for the log file if it's new
                if (!logFile.exists()) {
                    try (FileWriter writer = new FileWriter(logFile)) {
                        writer.write("=== CameraInterceptor Log Started at " + 
                                new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()) + 
                                " ===\n\n");
                    } catch (IOException e) {
                        Log.e(MODULE_TAG, "Failed to write log header: " + e.getMessage());
                    }
                }
            } catch (Exception e) {
                Log.e(MODULE_TAG, "Error initializing log file: " + e.getMessage());
            }
        }
    }
    
    /**
     * Log debug message
     */
    public static void d(String tag, String message) {
        log(Log.DEBUG, tag, message);
    }
    
    /**
     * Log info message
     */
    public static void i(String tag, String message) {
        log(Log.INFO, tag, message);
    }
    
    /**
     * Log warning message
     */
    public static void w(String tag, String message) {
        log(Log.WARN, tag, message);
    }
    
    /**
     * Log error message
     */
    public static void e(String tag, String message) {
        log(Log.ERROR, tag, message);
    }
    
    /**
     * Log stack trace for an exception
     */
    public static void logStackTrace(String tag, Throwable throwable) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        throwable.printStackTrace(pw);
        String stackTrace = sw.toString();
        
        log(Log.ERROR, tag, "Stack trace: " + stackTrace);
    }
    
    /**
     * Internal logging method
     */
    private static void log(int level, String tag, String message) {
        final String fullTag = MODULE_TAG + ":" + tag;
        final String logLevelName = getLogLevelName(level);
        final String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
        final String fullMessage = timestamp + " " + logLevelName + "/" + fullTag + ": " + message;
        
        // Log to XposedBridge
        if (LOG_TO_XPOSED) {
            try {
                XposedBridge.log(fullMessage);
            } catch (Throwable t) {
                // If XposedBridge logging fails, fall back to Android logging
                if (LOG_TO_ANDROID) {
                    Log.println(level, fullTag, "XposedBridge log failed: " + t.getMessage() + ". Original message: " + message);
                }
            }
        }
        
        // Log to Android system log
        if (LOG_TO_ANDROID) {
            try {
                Log.println(level, fullTag, message);
            } catch (Throwable t) {
                // This is a last resort fallback, so we don't handle failures here
            }
        }
        
        // Log to file asynchronously
        if (LOG_TO_FILE) {
            logExecutor.execute(() -> {
                try {
                    File logDir = new File(Environment.getExternalStorageDirectory(), LOG_FILE_DIR);
                    File logFile = new File(logDir, LOG_FILE_NAME);
                    
                    try (FileWriter writer = new FileWriter(logFile, true)) {
                        writer.write(fullMessage + "\n");
                    } catch (IOException e) {
                        if (LOG_TO_ANDROID) {
                            Log.e(MODULE_TAG, "Failed to write to log file: " + e.getMessage());
                        }
                    }
                } catch (Exception e) {
                    if (LOG_TO_ANDROID) {
                        Log.e(MODULE_TAG, "Error writing to log file: " + e.getMessage());
                    }
                }
            });
        }
    }
    
    /**
     * Convert log level integer to readable string
     */
    private static String getLogLevelName(int level) {
        switch (level) {
            case Log.VERBOSE:
                return "VERBOSE";
            case Log.DEBUG:
                return "DEBUG";
            case Log.INFO:
                return "INFO";
            case Log.WARN:
                return "WARN";
            case Log.ERROR:
                return "ERROR";
            case Log.ASSERT:
                return "ASSERT";
            default:
                return "UNKNOWN";
        }
    }
}