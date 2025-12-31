// SettingsActivity.java 
package com.camerainterceptor.ui;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceFragment;
import android.preference.PreferenceManager;
import android.widget.Toast;

import com.camerainterceptor.R;
import com.camerainterceptor.utils.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Settings activity for the Camera Interceptor module
 */
public class SettingsActivity extends Activity {
    private static final String TAG = "SettingsActivity";
    private static final int REQUEST_PERMISSIONS = 100;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        try {
            // Initialize default preferences if needed
            initDefaultPreferences();
            
            // Set up the settings UI
            getFragmentManager().beginTransaction()
                    .replace(android.R.id.content, new SettingsFragment())
                    .commit();
            
            // Request necessary permissions
            requestRequiredPermissions();
            
            Logger.i(TAG, "SettingsActivity created");
        } catch (Exception e) {
            Logger.e(TAG, "Error in onCreate: " + e.getMessage());
            Logger.logStackTrace(TAG, e);
            Toast.makeText(this, "Error initializing settings", Toast.LENGTH_SHORT).show();
        }
    }
    
    /**
     * Initialize default preference values
     */
    private void initDefaultPreferences() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        SharedPreferences.Editor editor = prefs.edit();
        
        // Set defaults if they don't exist
        if (!prefs.contains("enable_module")) {
            editor.putBoolean("enable_module", true);
        }
        
        if (!prefs.contains("show_notifications")) {
            editor.putBoolean("show_notifications", true);
        }
        
        if (!prefs.contains("use_custom_picker")) {
            editor.putBoolean("use_custom_picker", false);
        }
        
        editor.apply();
    }
    
    /**
     * Request permissions required for the module
     */
    private void requestRequiredPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            List<String> permissionsNeeded = new ArrayList<>();
            
            // Check for required permissions
            if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) 
                    != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            }
            
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) 
                    != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            }
            
            if (checkSelfPermission(Manifest.permission.CAMERA) 
                    != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.CAMERA);
            }
            
            // Request permissions if needed
            if (!permissionsNeeded.isEmpty()) {
                requestPermissions(permissionsNeeded.toArray(new String[0]), REQUEST_PERMISSIONS);
            }
        }
    }
    
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQUEST_PERMISSIONS) {
            boolean allGranted = true;
            
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            
            if (!allGranted) {
                Toast.makeText(this, "Some permissions were denied. " +
                        "The module may not work correctly.", Toast.LENGTH_LONG).show();
            }
        }
    }
    
    /**
     * Settings fragment to display preferences
     */
    public static class SettingsFragment extends PreferenceFragment {
        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            
            // Load preferences from XML
            addPreferencesFromResource(R.xml.preferences);
        }
    }
    
    /**
     * Get a boolean preference value
     */
    public static boolean getBooleanPreference(Context context, String key, boolean defaultValue) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        return prefs.getBoolean(key, defaultValue);
    }
    
    /**
     * Get a string preference value
     */
    public static String getStringPreference(Context context, String key, String defaultValue) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        return prefs.getString(key, defaultValue);
    }
}