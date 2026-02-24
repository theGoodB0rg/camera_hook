package com.camerainterceptor.utils;

import android.os.Handler;
import android.os.Looper;

/**
 * A lightweight watchdog to monitor camera operations and prevent hangs.
 * If a task takes too long, it triggers a recovery callback.
 */
public class Watchdog {
    private static final String TAG = "Watchdog";
    private final Handler handler;
    private final long timeoutMs;
    private final Runnable timeoutAction;
    private final String taskName;
    private boolean isRunning = false;

    private final Runnable watchdogTask = new Runnable() {
        @Override
        public void run() {
            if (isRunning) {
                Logger.e(TAG, "WATCHDOG TIMEOUT: task '" + taskName + "' exceeded " + timeoutMs + "ms");
                isRunning = false;
                if (timeoutAction != null) {
                    timeoutAction.run();
                }
            }
        }
    };

    public Watchdog(String taskName, long timeoutMs, Runnable timeoutAction) {
        this.taskName = taskName;
        this.timeoutMs = timeoutMs;
        this.timeoutAction = timeoutAction;
        this.handler = new Handler(Looper.getMainLooper());
    }

    public synchronized void start() {
        if (isRunning) {
            cancel();
        }
        isRunning = true;
        handler.postDelayed(watchdogTask, timeoutMs);
    }

    public synchronized void cancel() {
        isRunning = false;
        handler.removeCallbacks(watchdogTask);
    }
}
