@echo off
call "%~dp0build.cmd" -Test
exit /b %errorlevel%
