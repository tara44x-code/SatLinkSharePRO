@echo off
cd /d "%~dp0"
call build_exe.bat
exit /b %errorlevel%
