@echo off
title Installing PrivPrint Shop Station Update...
echo ========================================================
echo Installing PrivPrint Shop Station 1.0.6 (Retention Update)
echo ========================================================
echo.
echo Stopping any running PrivPrint instances...
taskkill /f /im "PrivPrint Shop Station.exe" 2>nul
timeout /t 1 /nobreak >nul
echo.
echo Launching Windows Installer with Administrator elevation...
powershell -Command "Start-Process msiexec.exe -ArgumentList '/i \""%~dp0PrivPrint Shop Station-1.0.6.msi\""' -Verb RunAs -Wait"
echo.
echo Installation completed.
pause
