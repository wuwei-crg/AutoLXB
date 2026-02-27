@echo off
setlocal

set "ROOT=%~dp0"
set "VENV=%ROOT%.venv"

if not exist "%ROOT%requirements.txt" (
  echo [ERROR] requirements.txt not found. Please run from project root.
  pause
  exit /b 1
)

if not exist "%VENV%\Scripts\python.exe" (
  echo [INFO] Creating virtual environment...
  py -3 -m venv "%VENV%"
  if errorlevel 1 (
    echo [ERROR] Failed to create virtual environment.
    pause
    exit /b 1
  )
)

echo [INFO] Installing dependencies...
"%VENV%\Scripts\python.exe" -m pip install --upgrade pip >nul
"%VENV%\Scripts\python.exe" -m pip install -r "%ROOT%requirements.txt"
if errorlevel 1 (
  echo [ERROR] Dependency installation failed.
  pause
  exit /b 1
)

echo [INFO] Starting web_console at http://localhost:5000 ...
pushd "%ROOT%web_console"
"%VENV%\Scripts\python.exe" app.py
set "EXIT_CODE=%ERRORLEVEL%"
popd

if not "%EXIT_CODE%"=="0" (
  echo [WARN] web_console exited with code %EXIT_CODE%.
)

endlocal & exit /b %EXIT_CODE%
