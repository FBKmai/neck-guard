@echo off
chcp 65001 >nul
cd /d "%~dp0"
echo 启动颈椎卫士接收端（Ctrl+C 退出）
python neck_receiver.py %*
pause
