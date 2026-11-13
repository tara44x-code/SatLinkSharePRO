@echo off
setlocal
cd /d "%~dp0"
chcp 65001 >nul
echo SatLink Share PRO - Windows EXE builder
echo.
set "BUILT=0"
set "FAILED=0"
call build_arch.bat 64
if errorlevel 3 (echo Skipping x64: Python 3.10-3.14 64-bit was not found.) else if errorlevel 1 (set "FAILED=1") else (set "BUILT=1")
call build_arch.bat 32
if errorlevel 3 (echo Skipping x32: Python 3.10-3.14 32-bit was not found.) else if errorlevel 1 (set "FAILED=1") else (set "BUILT=1")
echo.
if "%BUILT%"=="1" echo Built EXE files are in: %CD%\dist
if "%BUILT%"=="0" echo No EXE was built. Install Python 3.10-3.14 for your Windows architecture and retry.
if "%FAILED%"=="1" echo A build failed. See build_32.log or build_64.log here: %CD%
if "%BUILT%"=="1" if "%FAILED%"=="0" echo Done. You may close this window.
pause
if "%FAILED%"=="1" exit /b 1
if "%BUILT%"=="0" exit /b 1
exit /b 0
