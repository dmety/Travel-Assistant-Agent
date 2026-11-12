@echo off
setlocal EnableExtensions
title Travel Assistant Launcher

rem Double-click launcher: reuse or start the backend, then open the console client.
rem All messages are ASCII on purpose (Chinese Windows console uses code page 936).
rem Do NOT add "chcp 65001": Java 21 writes GBK here and would show mojibake.
rem Usage:
rem   run-client.bat                       -> interactive console (REPL)
rem   run-client.bat attractions Hangzhou  -> one-shot command, no pause
rem Optional: set TRAVEL_CLI_PORT to use a different port (default 8080).

set "ROOT=%~dp0"
cd /d "%ROOT%"

set "PORT=8080"
if not "%TRAVEL_CLI_PORT%"=="" set "PORT=%TRAVEL_CLI_PORT%"
set "SERVER_JAR=%ROOT%travel-server\target\travel-server-0.1.0-SNAPSHOT.jar"
set "CLIENT_JAR=%ROOT%travel-client\target\travel-client-0.1.0-SNAPSHOT.jar"
set "SERVER_PORT=%PORT%"
set "TRAVEL_DATA_DIR=%ROOT%data"

set "JAVA_EXE=java"
where java >NUL 2>&1
if errorlevel 1 (
  if exist "D:\Java\bin\java.exe" (
    set "JAVA_EXE=D:\Java\bin\java.exe"
  ) else (
    echo [ERROR] Java 21 not found. Add the JDK bin folder to PATH.
    if "%~1"=="" pause
    exit /b 1
  )
)

if not exist "%SERVER_JAR%" (
  echo [ERROR] Missing server jar. Build with: mvn -pl travel-server -am package
  if "%~1"=="" pause
  exit /b 1
)
if not exist "%CLIENT_JAR%" (
  echo [ERROR] Missing client jar. Build with: mvn -pl travel-client -am package
  if "%~1"=="" pause
  exit /b 1
)

rem Spring Boot does not read .env itself. Load same-name variables from it.
rem .env is UTF-8 with Chinese comments: under code page 936 "for /f" silently
rem stops at those bytes. Switch to UTF-8 ONLY while parsing, then restore the
rem original code page so Java 21 keeps its GBK console output (no mojibake).
rem application.yml falls back to AMAP_API_KEY; AMAP_KEY is a second safety net.
for /f "tokens=2 delims=:" %%C in ('chcp') do set "OLD_CP=%%C"
set "OLD_CP=%OLD_CP:.=%"
set "OLD_CP=%OLD_CP: =%"
if "%OLD_CP%"=="" set "OLD_CP=936"
chcp 65001 >NUL
if exist "%ROOT%.env" (
  for /f "usebackq eol=# tokens=1,* delims==" %%A in ("%ROOT%.env") do if not "%%~B"=="" set "%%A=%%~B"
) else (
  echo [WARN] .env not found - map and model features will run degraded.
)
chcp %OLD_CP% >NUL
if not defined AMAP_KEY set "AMAP_KEY=%AMAP_API_KEY%"

rem Hidden diagnostics: set TRAVEL_CLI_SELFTEST=1 to check config without starting anything.
rem Deliberately reports only loaded/missing, never the secret values.
if not "%TRAVEL_CLI_SELFTEST%"=="" (
  echo [SELFTEST] port=%PORT% java=%JAVA_EXE%
  echo [SELFTEST] server jar=OK client jar=OK
  if defined AMAP_API_KEY (echo [SELFTEST] amap=loaded) else (echo [SELFTEST] amap=MISSING)
  if defined AMAP_KEY (echo [SELFTEST] amap alias=ok) else (echo [SELFTEST] amap alias=MISSING)
  if defined BAIDU_AK (echo [SELFTEST] baidu=loaded) else (echo [SELFTEST] baidu=MISSING)
  if defined DEEPSEEK_API_KEY (echo [SELFTEST] deepseek=loaded) else (echo [SELFTEST] deepseek=MISSING)
  exit /b 0
)

rem Reuse an already running backend; never start a second one on the same port.
netstat -ano | findstr ":%PORT%" | findstr "LISTENING" >NUL 2>&1
if not errorlevel 1 (
  echo [OK] Backend already listening on %PORT%; reusing it.
  goto READY
)

echo [*] Starting backend on %PORT% in a separate window...
start "Travel Assistant Server" /D "%ROOT%" "%JAVA_EXE%" -jar "%SERVER_JAR%"

where curl >NUL 2>&1
if errorlevel 1 (
  echo [WARN] curl.exe not found; waiting 10s without a health check.
  ping -n 11 127.0.0.1 >NUL
  goto READY
)

set /a WAIT=0
:WAIT_LOOP
curl -s --noproxy "*" -o NUL "http://127.0.0.1:%PORT%/api/health" >NUL 2>&1
if not errorlevel 1 goto READY
set /a WAIT+=1
if %WAIT% GEQ 45 (
  echo [WARN] Backend not healthy after 45s. Check the server window.
  goto READY
)
ping -n 2 127.0.0.1 >NUL
goto WAIT_LOOP

:READY
echo [*] Backend: http://127.0.0.1:%PORT%
if "%~1"=="" (
  echo [*] Opening console client. Type help for commands, exit to quit.
  title Travel Assistant Console
  "%JAVA_EXE%" -jar "%CLIENT_JAR%" "http://127.0.0.1:%PORT%"
) else (
  "%JAVA_EXE%" -jar "%CLIENT_JAR%" "http://127.0.0.1:%PORT%" %*
)
set "CLI_RC=%ERRORLEVEL%"
if not "%~1"=="" exit /b %CLI_RC%
echo.
echo Client exited (code %CLI_RC%). Close the server window to stop the backend.
pause
exit /b %CLI_RC%
