@echo off
REM Fueller laptop ingester — Windows wrapper for Task Scheduler.
REM Edit PYTHON below if your `python` is not on PATH.
setlocal

set SCRIPT_DIR=%~dp0
set PYTHON=python

cd /d "%SCRIPT_DIR%"
"%PYTHON%" fueller_ingest.py %*
exit /b %ERRORLEVEL%
