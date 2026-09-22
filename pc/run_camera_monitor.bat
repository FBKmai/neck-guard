@echo off
rem Keep comments ASCII-only: this file is UTF-8, and cmd parses it as the
rem system codepage BEFORE `chcp 65001` takes effect, so multibyte chars in
rem `rem` / `echo` lines can be split into bogus commands.
rem Must also stay CRLF (see .gitattributes) or cmd mis-parses if/set blocks.
chcp 65001 >nul
cd /d "%~dp0"

rem Dependencies (torch / ultralytics / opencv) usually live in a virtualenv.
rem Plain `python` would hit the system interpreter and fail with
rem "no ultralytics". Resolution order:
rem   1. %NECKGUARD_PYTHON%
rem   2. ..\.venv next to the repo
rem   3. G:\yolo\venv  (this machine's YOLO env)
rem   4. system python
set PY=
if defined NECKGUARD_PYTHON if exist "%NECKGUARD_PYTHON%" set PY=%NECKGUARD_PYTHON%
if not defined PY if exist "%~dp0..\.venv\Scripts\python.exe" set PY=%~dp0..\.venv\Scripts\python.exe
if not defined PY if exist "G:\yolo\venv\Scripts\python.exe" set PY=G:\yolo\venv\Scripts\python.exe
if not defined PY set PY=python

rem Use the local weights when present, else let the script pick by VRAM.
set MODELARG=
if exist "G:\yolo\models\yolo26x-pose.pt" set MODELARG=--model G:/yolo/models/yolo26x-pose.pt

echo ============================================================
echo  NeckGuard wireless camera (Ctrl+C to quit)
echo  Python: %PY%
echo ============================================================
echo  On the phone: Settings - detection mode - PC, then Start streaming.
echo  Auto-scans the LAN; if nothing is found pass --url with the phone IP
echo  and port 8767. Keep --imgsz equal to the phone streaming resolution.
echo  Preview keys:  c = calibrate   r = reset   q = quit
echo ============================================================
echo.
"%PY%" neck_camera_monitor.py --show %MODELARG% %*
pause
