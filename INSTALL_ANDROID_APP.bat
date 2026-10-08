@echo off
setlocal
title Installing PrivPrint Customer Android App...
echo ========================================================
echo PrivPrint Android App - Install via USB
echo ========================================================
echo.

set ADB="%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
if not exist %ADB% (
    where adb >nul 2>&1
    if %errorlevel% equ 0 (
        set ADB=adb
    ) else (
        echo [ERROR] adb.exe not found at %LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe or in PATH.
        echo Please ensure Android SDK platform-tools is installed.
        pause
        exit /b 1
    )
)

set APK="%~dp0app\build\outputs\apk\debug\app-debug.apk"
if not exist %APK% (
    echo [ERROR] APK file not found at: %APK%
    echo Please build the project first using gradlew assembleDebug.
    pause
    exit /b 1
)

echo [1/3] Checking connected Android devices...
%ADB% devices
echo.

echo Ensure:
echo  1. Your Android phone is connected via USB.
echo  2. USB debugging is ENABLED in Developer Options.
echo  3. You tap 'Allow USB debugging' on your phone screen when prompted.
echo.

%ADB% wait-for-device
echo [2/3] Installing updated PrivPrint APK...
%ADB% install -r %APK%
if %errorlevel% neq 0 (
    echo.
    echo [ERROR] Installation failed. Check device authorization.
    pause
    exit /b %errorlevel%
)

echo.
echo [3/3] Launching PrivPrint...
%ADB% shell am start -n com.example.privprint/.MainActivity

echo.
echo ========================================================
echo SUCCESS! PrivPrint has been installed and launched.
echo ========================================================
pause
