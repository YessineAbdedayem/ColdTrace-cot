@echo off
rem Runs start.ps1 without changing the PowerShell execution policy. Same options: start -Retrain, start -NoMl, start -Stop
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0start.ps1" %*
if "%~1"=="" pause
