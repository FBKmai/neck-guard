@echo off
chcp 65001 >nul
cd /d "%~dp0"
echo 启动颈椎卫士接收端（Ctrl+C 退出）
echo 首次启动会请求一次管理员权限，用于放行防火墙端口，允许即可。
python neck_receiver.py %*
pause
