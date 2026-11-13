@echo off
setlocal
cd /d "%~dp0"
call build_arch.bat 32
set "RESULT=%errorlevel%"
if not "%RESULT%"=="0" echo Build failed. The error is above and in build_32.log.
pause
exit /b %RESULT%
