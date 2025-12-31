@echo off
setlocal enabledelayedexpansion

REM ===================================================================
REM Script to copy Camera Interceptor files from VS Code to Android Studio
REM ===================================================================

echo.
echo === Camera Interceptor Migration Tool ===
echo This script will copy your existing files to an Android Studio project.
echo.

REM Create a log file
set LOGFILE=migration_log.txt
echo Migration started at %date% %time% > %LOGFILE%

REM Ask for source directory (VS Code project)
set /p SOURCE_DIR=Enter the path to your VS Code project (e.g., C:\Users\HP\Desktop\My files\Camera Interceptor): 
if not exist "%SOURCE_DIR%" (
    echo ERROR: Source directory does not exist. >> %LOGFILE%
    echo ERROR: Source directory does not exist.
    goto :error
)
echo Source directory set to: %SOURCE_DIR% >> %LOGFILE%

REM Ask for destination directory (Android Studio project)
set /p DEST_DIR=Enter the path to your Android Studio project (e.g., C:\Users\HP\AndroidStudioProjects\Camera Interceptor): 
if not exist "%DEST_DIR%" (
    echo ERROR: Destination directory does not exist. >> %LOGFILE%
    echo ERROR: Destination directory does not exist.
    goto :error
)
echo Destination directory set to: %DEST_DIR% >> %LOGFILE%

REM Verify the destination is an Android Studio project
if not exist "%DEST_DIR%\app\src\main\java" (
    echo ERROR: Destination doesn't appear to be an Android Studio project (missing app/src/main/java). >> %LOGFILE%
    echo ERROR: Destination doesn't appear to be an Android Studio project.
    
    set /p CREATE_DIRS=Would you like to create the required directories? (y/n): 
    if /i "%CREATE_DIRS%"=="y" (
        echo Creating required directories...
        mkdir "%DEST_DIR%\app\src\main\java\com\camerainterceptor\hooks" 2>> %LOGFILE%
        mkdir "%DEST_DIR%\app\src\main\java\com\camerainterceptor\interfaces" 2>> %LOGFILE%
        mkdir "%DEST_DIR%\app\src\main\java\com\camerainterceptor\ui" 2>> %LOGFILE%
        mkdir "%DEST_DIR%\app\src\main\java\com\camerainterceptor\utils" 2>> %LOGFILE%
        mkdir "%DEST_DIR%\app\src\main\res\layout" 2>> %LOGFILE%
        mkdir "%DEST_DIR%\app\src\main\res\values" 2>> %LOGFILE%
        mkdir "%DEST_DIR%\app\src\main\res\xml" 2>> %LOGFILE%
        mkdir "%DEST_DIR%\app\src\main\assets" 2>> %LOGFILE%
        echo Required directories created. >> %LOGFILE%
    ) else (
        goto :error
    )
)

echo.
echo === Starting file migration ===
echo.

REM Create a counter for successful operations
set SUCCESS_COUNT=0
set ERROR_COUNT=0

REM Copy Java files
echo Copying Java files...

REM Copy main Java files
set SRC_JAVA=%SOURCE_DIR%\app\src\main\java\com\camerainterceptor
set DEST_JAVA=%DEST_DIR%\app\src\main\java\com\camerainterceptor

REM Check if source Java directory exists
if not exist "%SRC_JAVA%" (
    REM Try alternative path
    set SRC_JAVA=%SOURCE_DIR%\src\main\java\com\camerainterceptor
    if not exist "%SRC_JAVA%" (
        echo WARNING: Could not find Java source directory. Trying to use root source files... >> %LOGFILE%
        echo WARNING: Could not find Java source directory. Trying to use root source files...
        set SRC_JAVA=%SOURCE_DIR%
    )
)

REM Create destination directories if they don't exist
if not exist "%DEST_JAVA%" mkdir "%DEST_JAVA%" 2>> %LOGFILE%
if not exist "%DEST_JAVA%\hooks" mkdir "%DEST_JAVA%\hooks" 2>> %LOGFILE%
if not exist "%DEST_JAVA%\interfaces" mkdir "%DEST_JAVA%\interfaces" 2>> %LOGFILE%
if not exist "%DEST_JAVA%\ui" mkdir "%DEST_JAVA%\ui" 2>> %LOGFILE%
if not exist "%DEST_JAVA%\utils" mkdir "%DEST_JAVA%\utils" 2>> %LOGFILE%

REM Copy MainHook.java and HookDispatcher.java
call :copy_file "%SRC_JAVA%\MainHook.java" "%DEST_JAVA%\MainHook.java"
call :copy_file "%SRC_JAVA%\HookDispatcher.java" "%DEST_JAVA%\HookDispatcher.java"

REM Copy hook files
call :copy_file "%SRC_JAVA%\hooks\CameraHook.java" "%DEST_JAVA%\hooks\CameraHook.java"
call :copy_file "%SRC_JAVA%\hooks\Camera2Hook.java" "%DEST_JAVA%\hooks\Camera2Hook.java"
call :copy_file "%SRC_JAVA%\hooks\CameraxHook.java" "%DEST_JAVA%\hooks\CameraxHook.java"
call :copy_file "%SRC_JAVA%\hooks\IntentHook.java" "%DEST_JAVA%\hooks\IntentHook.java"

REM Copy interface files
call :copy_file "%SRC_JAVA%\interfaces\HookCallback.java" "%DEST_JAVA%\interfaces\HookCallback.java"

REM Copy UI files
call :copy_file "%SRC_JAVA%\ui\ImagePickerActivity.java" "%DEST_JAVA%\ui\ImagePickerActivity.java"
call :copy_file "%SRC_JAVA%\ui\SettingsActivity.java" "%DEST_JAVA%\ui\SettingsActivity.java"

REM Copy utility files
call :copy_file "%SRC_JAVA%\utils\ImageUtils.java" "%DEST_JAVA%\utils\ImageUtils.java"
call :copy_file "%SRC_JAVA%\utils\Logger.java" "%DEST_JAVA%\utils\Logger.java"
call :copy_file "%SRC_JAVA%\utils\MediaPickerHelper.java" "%DEST_JAVA%\utils\MediaPickerHelper.java"

REM Also try to copy from flat structure if the hierarchical structure wasn't found
if "%ERROR_COUNT%" GTR "0" (
    echo Trying to find files in flat structure...
    call :copy_file "%SOURCE_DIR%\MainHook.java" "%DEST_JAVA%\MainHook.java"
    call :copy_file "%SOURCE_DIR%\HookDispatcher.java" "%DEST_JAVA%\HookDispatcher.java"
    call :copy_file "%SOURCE_DIR%\CameraHook.java" "%DEST_JAVA%\hooks\CameraHook.java"
    call :copy_file "%SOURCE_DIR%\Camera2Hook.java" "%DEST_JAVA%\hooks\Camera2Hook.java"
    call :copy_file "%SOURCE_DIR%\CameraxHook.java" "%DEST_JAVA%\hooks\CameraxHook.java"
    call :copy_file "%SOURCE_DIR%\IntentHook.java" "%DEST_JAVA%\hooks\IntentHook.java"
    call :copy_file "%SOURCE_DIR%\HookCallback.java" "%DEST_JAVA%\interfaces\HookCallback.java"
    call :copy_file "%SOURCE_DIR%\ImagePickerActivity.java" "%DEST_JAVA%\ui\ImagePickerActivity.java"
    call :copy_file "%SOURCE_DIR%\SettingsActivity.java" "%DEST_JAVA%\ui\SettingsActivity.java"
    call :copy_file "%SOURCE_DIR%\ImageUtils.java" "%DEST_JAVA%\utils\ImageUtils.java"
    call :copy_file "%SOURCE_DIR%\Logger.java" "%DEST_JAVA%\utils\Logger.java"
    call :copy_file "%SOURCE_DIR%\MediaPickerHelper.java" "%DEST_JAVA%\utils\MediaPickerHelper.java"
)

REM Copy XML resources
echo Copying XML resource files...

REM Define source and destination paths for resources
set SRC_RES=%SOURCE_DIR%\app\src\main\res
if not exist "%SRC_RES%" (
    set SRC_RES=%SOURCE_DIR%\src\main\res
    if not exist "%SRC_RES%" (
        set SRC_RES=%SOURCE_DIR%\res
        if not exist "%SRC_RES%" (
            echo WARNING: Could not find resources directory. >> %LOGFILE%
            echo WARNING: Could not find resources directory.
            set SRC_RES=%SOURCE_DIR%
        )
    )
)
set DEST_RES=%DEST_DIR%\app\src\main\res

REM Create destination resource directories if they don't exist
if not exist "%DEST_RES%\layout" mkdir "%DEST_RES%\layout" 2>> %LOGFILE%
if not exist "%DEST_RES%\values" mkdir "%DEST_RES%\values" 2>> %LOGFILE%
if not exist "%DEST_RES%\xml" mkdir "%DEST_RES%\xml" 2>> %LOGFILE%

REM Copy layout files
call :copy_file "%SRC_RES%\layout\activity_picker.xml" "%DEST_RES%\layout\activity_picker.xml"
call :copy_file "%SRC_RES%\layout\activity_settings.xml" "%DEST_RES%\layout\activity_settings.xml"

REM Copy values files
call :copy_file "%SRC_RES%\values\strings.xml" "%DEST_RES%\values\strings.xml"
call :copy_file "%SRC_RES%\values\styles.xml" "%DEST_RES%\values\styles.xml"

REM Copy XML files
call :copy_file "%SRC_RES%\xml\preferences.xml" "%DEST_RES%\xml\preferences.xml"

REM Also try flat structure for XML files
call :copy_file "%SOURCE_DIR%\activity_picker.xml" "%DEST_RES%\layout\activity_picker.xml"
call :copy_file "%SOURCE_DIR%\activity_settings.xml" "%DEST_RES%\layout\activity_settings.xml"
call :copy_file "%SOURCE_DIR%\strings.xml" "%DEST_RES%\values\strings.xml"
call :copy_file "%SOURCE_DIR%\styles.xml" "%DEST_RES%\values\styles.xml"
call :copy_file "%SOURCE_DIR%\preferences.xml" "%DEST_RES%\xml\preferences.xml"

REM Create xposed_init file in assets
echo Creating Xposed init file...
set DEST_ASSETS=%DEST_DIR%\app\src\main\assets
if not exist "%DEST_ASSETS%" mkdir "%DEST_ASSETS%" 2>> %LOGFILE%
echo com.camerainterceptor.MainHook > "%DEST_ASSETS%\xposed_init" 2>> %LOGFILE%
if %errorlevel% EQU 0 (
    echo SUCCESS: Created xposed_init file. >> %LOGFILE%
    echo SUCCESS: Created xposed_init file.
    set /a SUCCESS_COUNT+=1
) else (
    echo ERROR: Failed to create xposed_init file. >> %LOGFILE%
    echo ERROR: Failed to create xposed_init file.
    set /a ERROR_COUNT+=1
)

REM Copy libraries
echo Copying library files...
set SRC_LIBS=%SOURCE_DIR%\libs
if not exist "%SRC_LIBS%" (
    set SRC_LIBS=%SOURCE_DIR%\app\libs
    if not exist "%SRC_LIBS%" (
        echo WARNING: Could not find libs directory. >> %LOGFILE%
        echo WARNING: Could not find libs directory.
    )
)

if exist "%SRC_LIBS%" (
    set DEST_LIBS=%DEST_DIR%\app\libs
    if not exist "%DEST_LIBS%" mkdir "%DEST_LIBS%" 2>> %LOGFILE%
    
    call :copy_file "%SRC_LIBS%\XposedBridge.jar" "%DEST_LIBS%\XposedBridge.jar"
    
    REM Look for android.jar
    if exist "%SRC_LIBS%\android.jar" (
        echo NOTE: Found android.jar in libs. Android Studio will use the system Android SDK instead. >> %LOGFILE%
        echo NOTE: Found android.jar in libs. Android Studio will use the system Android SDK instead.
    )
)

REM Copy AndroidManifest.xml
echo Copying AndroidManifest.xml...
set SRC_MANIFEST=%SOURCE_DIR%\app\src\main\AndroidManifest.xml
if not exist "%SRC_MANIFEST%" (
    set SRC_MANIFEST=%SOURCE_DIR%\src\main\AndroidManifest.xml
    if not exist "%SRC_MANIFEST%" (
        set SRC_MANIFEST=%SOURCE_DIR%\AndroidManifest.xml
    )
)
set DEST_MANIFEST=%DEST_DIR%\app\src\main\AndroidManifest.xml
call :copy_file "%SRC_MANIFEST%" "%DEST_MANIFEST%"

echo.
echo === Migration Summary ===
echo Successfully copied %SUCCESS_COUNT% files.
if %ERROR_COUNT% GTR 0 (
    echo Failed to copy %ERROR_COUNT% files. See %LOGFILE% for details.
) else (
    echo All files copied successfully!
)

echo Migration completed at %date% %time% >> %LOGFILE%
echo All done! See %LOGFILE% for details.
echo.
echo Next steps:
echo 1. Open your project in Android Studio
echo 2. Build the project to see if there are any missing files or dependencies
echo 3. Fix any remaining errors with Android Studio's suggestions
echo.
echo.
echo Press any key to exit...
pause >nul
goto :end

:copy_file
REM Function to copy a file with error handling
set SRC=%~1
set DEST=%~2

if exist "%SRC%" (
    copy "%SRC%" "%DEST%" >nul 2>> %LOGFILE%
    if %errorlevel% EQU 0 (
        echo SUCCESS: Copied %SRC% to %DEST% >> %LOGFILE%
        echo SUCCESS: Copied %~nx1
        set /a SUCCESS_COUNT+=1
    ) else (
        echo ERROR: Failed to copy %SRC% to %DEST% >> %LOGFILE%
        echo ERROR: Failed to copy %~nx1
        set /a ERROR_COUNT+=1
    )
) else (
    echo WARNING: Source file %SRC% does not exist. >> %LOGFILE%
    REM Don't display this warning to avoid cluttering the console
    set /a ERROR_COUNT+=1
)
exit /b

:error
echo Migration failed! See %LOGFILE% for details.
echo Migration failed at %date% %time% >> %LOGFILE%
echo.
echo Press any key to exit...
pause >nul
exit /b 1

:end
endlocal