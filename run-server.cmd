@echo off
cd /d "%~dp0"
call build.cmd
if errorlevel 1 exit /b 1
java -Dfile.encoding=UTF-8 -cp build SnakeServer %*
