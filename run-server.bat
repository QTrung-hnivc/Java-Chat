@echo off
chcp 65001 >nul
cd /d "%~dp0"
javac -encoding UTF-8 ChatServer.java ChatClient.java
if errorlevel 1 (
    echo.
    echo Compile failed - is a JDK installed and on PATH?
    pause
    exit /b 1
)
java -Dfile.encoding=UTF-8 ChatServer
if errorlevel 1 pause
