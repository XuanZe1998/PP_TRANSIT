@echo off
setlocal EnableExtensions
cd /d "%~dp0"
title ChatGPT Payment Link Service

set "VENV_DIR=%CD%\venv"
set "VENV_PYTHON=%VENV_DIR%\Scripts\python.exe"
set "CHECK_ONLY=0"
set "PYTHONUTF8=1"
set "PYTHONIOENCODING=utf-8"

if /I "%~1"=="--check" set "CHECK_ONLY=1"

echo ========================================
echo   ChatGPT Payment Link Service
echo ========================================
echo.

if not exist ".env" (
    echo [WARN] .env was not found. Defaults will be used.
    echo        Copy .env.example to .env to configure proxy and database.
    echo.
)

where python >nul 2>nul
if errorlevel 1 (
    set "FAIL_STEP=Python was not found. Install Python 3.10+ and add it to PATH."
    goto :fail
)

if not exist "%VENV_PYTHON%" (
    echo [1/4] Creating Python virtual environment...
    python -m venv "%VENV_DIR%"
    if errorlevel 1 (
        set "FAIL_STEP=Failed to create the Python virtual environment."
        goto :fail
    )
) else (
    echo [1/4] Python virtual environment is ready.
)

echo [2/4] Checking Python packages...
"%VENV_PYTHON%" -c "import flask, httpx, curl_cffi, playwright, pymysql, dotenv" >nul 2>nul
if errorlevel 1 (
    echo       Missing packages detected. Installing requirements...
    "%VENV_PYTHON%" -m pip install -r requirements.txt
    if errorlevel 1 (
        set "FAIL_STEP=Failed to install requirements.txt. Check the pip output above."
        goto :fail
    )
) else (
    echo       Python packages are ready.
)

echo [3/4] Checking Playwright Chromium...
"%VENV_PYTHON%" -m playwright install chromium
if errorlevel 1 (
    set "FAIL_STEP=Failed to install Playwright Chromium. Check your network and retry."
    goto :fail
)

echo [4/4] Checking the payment service module...
"%VENV_PYTHON%" -c "from payment_web import app; assert app is not None"
if errorlevel 1 (
    set "FAIL_STEP=Failed to import payment_web.py. Review the Python error above."
    goto :fail
)

if "%CHECK_ONLY%"=="1" (
    echo.
    echo [OK] Startup check passed.
    exit /b 0
)

echo.
echo [INFO] Starting Payment Service...
echo [INFO] Python API: http://127.0.0.1:5000
echo [INFO] Admin page: http://localhost:5173/admin/payment-link
echo [INFO] Keep this window open. Press Ctrl+C to stop the service.
echo.

"%VENV_PYTHON%" payment_web.py
set "SERVICE_EXIT_CODE=%ERRORLEVEL%"
if not "%SERVICE_EXIT_CODE%"=="0" (
    set "FAIL_STEP=Payment Service exited with code %SERVICE_EXIT_CODE%."
    goto :fail
)

echo.
echo [INFO] Payment Service stopped.
pause
exit /b 0

:fail
echo.
echo [ERROR] %FAIL_STEP%
echo [ERROR] Working directory: %CD%
echo.
echo Capture the complete output above when reporting this problem.
pause
exit /b 1
