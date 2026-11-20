@echo off
setlocal
"%~dp0runtime\python.exe" -I -B -X utf8 "%~dp0app\windows_test.py" --cli %*
exit /b %ERRORLEVEL%
