@echo off
setlocal

REM ============================================
REM MochiMochi - Build, Sign, Install release APK
REM ============================================

set KEYSTORE=app\github-release.jks
set ALIAS=mochimochi
set KS_PASS=Maxpaynecodl@98
set UNSIGNED_APK=app\build\outputs\apk\release\app-release-unsigned.apk
set SIGNED_APK=app-release-signed.apk

echo.
echo === Step 1/3: Building release APK ===
call gradlew.bat clean assembleRelease
if errorlevel 1 (
    echo Build failed. Aborting.
    exit /b 1
)

if not exist "%UNSIGNED_APK%" (
    echo Could not find %UNSIGNED_APK% - check your build output path.
    exit /b 1
)

echo.
echo === Step 2/3: Signing APK ===
if exist "%SIGNED_APK%" del "%SIGNED_APK%"
call apksigner sign --ks "%KEYSTORE%" --ks-key-alias "%ALIAS%" --ks-pass "pass:%KS_PASS%" --key-pass "pass:%KS_PASS%" --out "%SIGNED_APK%" "%UNSIGNED_APK%"
echo apksigner sign exit code: %errorlevel%
if errorlevel 1 (
    echo Signing failed. Aborting.
    exit /b 1
)
if not exist "%SIGNED_APK%" (
    echo %SIGNED_APK% was not created. Aborting.
    exit /b 1
)

echo.
echo Verifying...
call apksigner verify "%SIGNED_APK%"
echo apksigner verify exit code: %errorlevel%
if errorlevel 1 (
    echo Verification failed. Aborting install.
    exit /b 1
)

echo.
echo === Step 3/3: Installing to connected device ===
call adb devices
call adb install -r "%SIGNED_APK%" > install_result.txt 2>&1
type install_result.txt
findstr /C:"INSTALL_FAILED_UPDATE_INCOMPATIBLE" install_result.txt >nul
if not errorlevel 1 (
    echo.
    echo Signature mismatch with installed app - uninstalling old copy and retrying...
    call adb uninstall com.kawai.mochi
    call adb install -r "%SIGNED_APK%"
)
del install_result.txt 2>nul
if errorlevel 1 (
    echo Install failed. Is a device connected with USB debugging enabled?
    exit /b 1
)

echo.
echo === Done! %SIGNED_APK% built, signed, and installed. ===
pause
endlocal