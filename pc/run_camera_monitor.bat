@echo off
chcp 65001 >nul
cd /d "%~dp0"
echo 启动颈椎卫士 无线相机（Ctrl+C 退出）
echo 先在手机 App 设置页把「检测方式」选成「电脑检测」，再点「开始推流」。
echo 本脚本会自动扫描局域网里的手机；扫不到就加 --url http://手机IP:8767/video
echo.
python neck_camera_monitor.py --show %*
pause
