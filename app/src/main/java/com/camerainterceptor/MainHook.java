// MainHook.java 
package com.camerainterceptor;

import android.app.Application;
import android.content.Context;

import com.camerainterceptor.hooks.Camera2Hook;
import com.camerainterceptor.hooks.CameraHook;
import com.camerainterceptor.hooks.CameraxHook;
import com.camerainterceptor.hooks.FileOutputHook;
import com.camerainterceptor.hooks.IntentHook;
import com.camerainterceptor.utils.Logger;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage, IXposedHookZygoteInit {
    private static final String TAG = "CameraInterceptor";
    private static Context globalContext = null;
    private static HookDispatcher hookDispatcher = null;

    @Override
    public void initZygote(StartupParam startupParam) throws Throwable {
        Logger.i(TAG, "Initializing CameraInterceptor zygote hook");
        // Initialize any resources needed before package loading
    }

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (lpparam.packageName.equals("com.camerainterceptor")) {
            // Don't hook into our own module
            return;
        }

        try {
            Logger.i(TAG, "Loading package: " + lpparam.packageName);
            
            // Hook into Application's attach method to get a valid context
            XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    globalContext = (Context) param.args[0];
                    
                    if (globalContext != null) {
                        Logger.i(TAG, "Got application context for: " + lpparam.packageName);
                        
                        // Initialize hook dispatcher with context and package info
                        hookDispatcher = new HookDispatcher(globalContext, lpparam);
                        
                        // Register all hooks through the dispatcher
                        registerHooks();
                    } else {
                        Logger.e(TAG, "Failed to get application context");
                    }
                }
            });
        } catch (Throwable t) {
            Logger.e(TAG, "Error in handleLoadPackage: " + t.getMessage());
            Logger.logStackTrace(TAG, t);
        }
    }

    private void registerHooks() {
        try {
            if (hookDispatcher == null) {
                Logger.e(TAG, "Cannot register hooks: HookDispatcher is null");
                return;
            }

            // Register all our hooks
            hookDispatcher.registerHook(new CameraHook(hookDispatcher));
            hookDispatcher.registerHook(new Camera2Hook(hookDispatcher));
            hookDispatcher.registerHook(new CameraxHook(hookDispatcher));
            hookDispatcher.registerHook(new FileOutputHook(hookDispatcher));
            hookDispatcher.registerHook(new IntentHook(hookDispatcher));

            Logger.i(TAG, "All hooks registered successfully");
        } catch (Throwable t) {
            Logger.e(TAG, "Failed to register hooks: " + t.getMessage());
            Logger.logStackTrace(TAG, t);
        }
    }
}