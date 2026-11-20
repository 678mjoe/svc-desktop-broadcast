@echo off
setlocal
"%~dp0runtime\python.exe" -I -B -X utf8 "%~dp0app\windows_test.py"
set "SVC_EXIT=%ERRORLEVEL%"
if not "%SVC_EXIT%"=="0" pause
exit /b %SVC_EXIT%
