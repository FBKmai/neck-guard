# 颈椎卫士 Windows 接收端

手机 App 检测到头部前倾后，通过局域网把事件和截图发到这台电脑；电脑弹出 Windows 通知（带截图）并播放提示音。

## 安装

需要 Python 3.10 以上（本机是 `python` 命令）。

```bat
cd pc
python -m pip install -r requirements.txt
```

`winotify` 是可选依赖，装不上也能用，会自动回退到 PowerShell 的系统通知。

## 启动

双击 `run_receiver.bat`，或命令行：

```bat
python neck_receiver.py --port 8765 --token 你的密钥
```

启动后会打印本机局域网地址，例如：

```
请在手机设置页填写以下任一地址（选与手机同一 Wi-Fi 的那个）：
    http://192.168.1.23:8765
```

常用参数：

| 参数 | 说明 | 默认 |
|---|---|---|
| `--port` | 监听端口 | 8765 |
| `--token` | 共享密钥，手机端要填一样的；留空则不校验 | 空 |
| `--sound` | 提示音，wav 文件路径或系统别名（SystemExclamation / SystemAsterisk / SystemHand） | SystemExclamation |
| `--beep` | 用蜂鸣序列代替 wav | 关 |
| `--mute` | 静音，只弹通知 | 关 |
| `--quiet-hours 23-7` | 静默时段，只记录不提醒，可跨午夜 | 无 |
| `--snapshot-dir` | 截图保存目录 | `pc/snapshots` |

## 防火墙

第一次运行 Windows 会弹「是否允许 Python 访问网络」，选「专用网络」允许即可。若手机「测试连接」失败，以管理员身份运行：

```bat
netsh advfirewall firewall add rule name="NeckGuard 8765" dir=in action=allow protocol=TCP localport=8765
```

## 手机端设置

在 App「设置」页填写：

- 电脑地址：`http://<电脑IP>:8765`（不要带路径）
- 共享密钥：与 `--token` 一致
- 点「测试连接」应显示成功，点「发送测试事件」电脑应弹通知并响铃

## 本机自测

```bat
python neck_receiver.py --token test123
```

另开一个窗口：

```bat
python send_test_event.py --token test123          :: 发一条 TEST 事件，应弹通知
python send_test_event.py --token test123 --ping   :: 只测连通性
```

## 接口

- `GET /api/ping` 返回 `{"ok": true, "name": "neck-receiver", "version": "1"}`
- `POST /api/posture/events`，`multipart/form-data`，字段 `event`（JSON）与可选 `snapshot`（JPEG），请求头 `X-Neck-Token`
- 返回 200 `{"ok": true}`；密钥不匹配 401；解析失败 400

截图按 `YYYYMMDD_HHmmss_<类型>.jpg` 保存在 `snapshots/`，只保留最近 200 张。
