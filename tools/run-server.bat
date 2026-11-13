@echo off
REM Double-click launcher for the Travel-Assistant server (Windows).
REM Calls run-server.ps1 with the execution policy bypassed for this run only.
REM Optional: run-server.bat 9090   -> start on a custom port.

setlocal
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0run-server.ps1" %*
set EXITCODE=%ERRORLEVEL%

echo.
if not "%EXITCODE%"=="0" echo [server exited with code %EXITCODE%]
echo Press any key to close this window.
pause >nul
