@echo off
chcp 65001 >nul 2>&1
title Create Sentence - Java
cd /d "%~dp0"

where java >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Java not found. Please install JDK 21 and add it to PATH.
    pause
    exit /b 1
)

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0run.ps1" %*
if errorlevel 1 (
    echo.
    echo [ERROR] Failed to start. See messages above.
    pause
)
