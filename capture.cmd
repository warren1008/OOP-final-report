@echo off
call "%~dp0build.cmd" -Capture
exit /b %errorlevel%
