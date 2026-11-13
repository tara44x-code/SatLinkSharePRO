@echo off
setlocal
cd /d "%~dp0"
if "%~1"=="32" (
    set "ARCH=32"
    set "PYTAG=-3-32"
    set "REQ=requirements-win32.txt"
) else if "%~1"=="64" (
    set "ARCH=64"
    set "PYTAG=-3-64"
    set "REQ=requirements-win64.txt"
) else (
    echo Open build.bat to create the program. This is an internal build step.
    pause
    exit /b 2
)
set "PY_CMD="
where py >nul 2>nul
if not errorlevel 1 (
    py %PYTAG% -c "import struct,sys; sys.exit(0 if struct.calcsize('P')*8 == int(sys.argv[1]) and (3,10)<=sys.version_info[:2]<(3,15) else 1)" %ARCH% >nul 2>nul
    if not errorlevel 1 set "PY_CMD=py %PYTAG%"
)
if not defined PY_CMD (
    where python >nul 2>nul
    if not errorlevel 1 (
        python -c "import struct,sys; sys.exit(0 if struct.calcsize('P')*8 == int(sys.argv[1]) and (3,10)<=sys.version_info[:2]<(3,15) else 1)" %ARCH% >nul 2>nul
        if not errorlevel 1 set "PY_CMD=python"
    )
)
if not defined PY_CMD (
    echo Matching Python 3.10-3.14 x%ARCH% not found. Install it and retry.
    exit /b 3
)
set "VENV=.venv%ARCH%"
set "LOG=%CD%\build_%ARCH%.log"
>"%LOG%" echo SatLink Share PRO - x%ARCH% build log
echo Building x%ARCH% with %PY_CMD%. See build_%ARCH%.log for details.
if not exist "%VENV%\Scripts\python.exe" (
    echo Creating %VENV%...
    %PY_CMD% -m venv "%VENV%" >>"%LOG%" 2>&1
    if errorlevel 1 goto failed
)
echo Installing Python dependencies...
"%VENV%\Scripts\python.exe" -m pip install --only-binary=:all: -r "%REQ%" >>"%LOG%" 2>&1
if errorlevel 1 goto failed
echo Packaging EXE...
"%VENV%\Scripts\python.exe" -m PyInstaller --noconfirm --clean --onefile --windowed --name "SatLink Share PRO PC x%ARCH%" --icon "%CD%\assets\satlink_share_pc_icon.ico" --add-data "%CD%\assets;assets" --distpath "%CD%\dist" --workpath "%CD%\build\win%ARCH%" --specpath "%CD%\build\win%ARCH%" "%CD%\satlink_share_pc.py" >>"%LOG%" 2>&1
if errorlevel 1 goto failed
if not exist "dist\SatLink Share PRO PC x%ARCH%.exe" goto failed
echo EXE: %CD%\dist\SatLink Share PRO PC x%ARCH%.exe
exit /b 0
:failed
echo.
echo x%ARCH% build failed. Read %LOG% or send it for diagnosis.
echo -------- build_%ARCH%.log --------
type "%LOG%"
echo -------- end of log --------
exit /b 1
