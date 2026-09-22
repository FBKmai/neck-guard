# 颈椎卫士 电脑端

电脑端有两个程序，按「谁来做检测」区分：

| 程序 | 谁检测 | 用途 |
|---|---|---|
| `neck_receiver.py` | 手机 | 手机本机推理，电脑只负责收事件、弹通知、响铃 |
| `neck_camera_monitor.py` | 电脑 | 手机只当无线相机推画面，电脑用更大的模型判定并提醒 |

两者不能同时对同一台手机用：手机 App 设置页的「检测方式」决定走哪条路。下面先讲接收端，无线相机模式见本文末尾。

---

# 接收端（手机检测）

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

启动后会打印状态横幅：

```
颈椎卫士 接收端已启动，监听 0.0.0.0:8765
自动发现: 已开启（UDP 8766）
  >> 手机 App 设置页点「扫描电脑」即可自动填地址，无需手动输入 <<
防火墙放行: 已自动添加
手填地址（扫描失败时备用，多个地址时选与手机同一 Wi-Fi 的那个）：
    http://192.168.1.23:8765
```

常用参数：

| 参数 | 说明 | 默认 |
|---|---|---|
| `--port` | 上报用的 HTTP 端口 | 8765 |
| `--discovery-port` | 自动发现用的 UDP 端口 | 8766 |
| `--token` | 共享密钥，手机端要填一样的；留空则不校验 | 空 |
| `--no-firewall` | 不自动放行防火墙 | 关 |
| `--firewall-only` | 只放行防火墙后退出，不启动服务 | 关 |
| `--sound` | 提示音，wav 文件路径或系统别名（SystemExclamation / SystemAsterisk / SystemHand） | SystemExclamation |
| `--beep` | 用蜂鸣序列代替 wav | 关 |
| `--mute` | 静音，只弹通知 | 关 |
| `--quiet-hours 23-7` | 静默时段，只记录不提醒，可跨午夜 | 无 |
| `--snapshot-dir` | 截图保存目录 | `pc/snapshots` |

## 自动发现（推荐用法）

手机端点「扫描电脑」后会向局域网广播一个 UDP 探针，本接收端收到后把自己的地址单播回去，手机直接填好，不用手动输入 IP。

这样做的好处是**不会填错 IP**。电脑上常有多张网卡（VMware、VirtualBox、WSL、Hyper-V 都会装虚拟网卡），`ipconfig` 会列出好几个地址，手填时挑到虚拟网卡那个，手机就会连不上，报「连接被拒绝」。接收端回应时用路由探测算出与手机同网段的那个地址，从根上避免这个问题。

协议（与 App 的 `PcDiscovery.kt` 对应）：

- 手机广播 → `{"neckguard": "discover", "v": 1}`
- 电脑单播回 → `{"neckguard": "receiver", "name": "<主机名>", "host": "<同网段IP>", "port": 8765, "token_required": true, "v": 1}`

扫不到时的排查顺序：接收端是否在运行 → 手机和电脑是否同一个 Wi-Fi → 路由器是否开了 AP 隔离（开了就只能手填）→ 防火墙 UDP 8766 是否放行。

本机可以用 `test_discovery.py` 自测发现功能，它是手机端扫描逻辑的 Python 等价物：

```bat
python test_discovery.py
```

## 防火墙

接收端启动时会自动检查并放行所需端口，首次会弹一次 UAC，允许即可。之后不再提示；换了端口会自动重新放行。

想自己控制的话，用 `--no-firewall` 关掉自动放行，然后以管理员身份执行：

```bat
netsh advfirewall firewall add rule name="NeckGuard Receiver TCP" dir=in action=allow protocol=TCP localport=8765
netsh advfirewall firewall add rule name="NeckGuard Receiver UDP" dir=in action=allow protocol=UDP localport=8766
```

也可以只放行不启动服务：

```bat
python neck_receiver.py --firewall-only
```

检查规则是否生效：

```powershell
Get-NetFirewallRule -DisplayName "NeckGuard Receiver*"
```

## 手机端设置

在 App「设置」页的「电脑接收端（局域网）」区块：

1. 点「扫描电脑」，在弹出的列表里选中这台电脑，地址会自动填好。扫不到再手填 `http://<电脑IP>:8765`（不要带路径）。
2. 共享密钥填得和 `--token` 一致；两边都留空则不校验。
3. 打开「上报到电脑」开关。
4. 点「测试连接」应显示成功，点「发送测试事件」电脑应弹通知并响铃。

失败时 App 会直接说明原因，例如「电脑拒绝了连接」通常是地址填到了虚拟网卡上，改用「扫描电脑」即可。

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

事件类型与提醒方式：

| type | 含义 | 电脑端表现 |
|---|---|---|
| `ALERT` | 过了提醒冷却的前倾 | 弹通知 + 响铃 |
| `CONFIRMED` | 冷却期内仍在前倾 | 弹通知，不响铃 |
| `RECOVERED` | 坐正恢复 | 弹通知，不响铃 |
| `TEST` | 设置页发的测试事件 | 弹通知 + 响铃 |

只有 `ALERT` 和 `TEST` 会响铃。长时间低头时会持续收到「仍在前倾」的静音通知，
既不漏掉状态，也不会每隔几秒就被铃声打断。响铃间隔由 App 设置页的「提醒冷却」控制，默认 10 分钟。

事件里的 `neckDeg` 是**前倾角**：耳肩连线与髋肩连线（躯干线）的夹角，即头相对自己身体前伸了多少，头与身体共线时为 0°。`torsoDeg` 是躯干与竖直方向的夹角，只做记录不参与判定。App v0.4 及更早发来的 `neckDeg` 是相对竖直线的旧口径，数值会偏大。

- `GET /api/ping` 返回 `{"ok": true, "name": "neck-receiver", "version": "1"}`
- `POST /api/posture/events`，`multipart/form-data`，字段 `event`（JSON）与可选 `snapshot`（JPEG），请求头 `X-Neck-Token`
- 返回 200 `{"ok": true}`；密钥不匹配 401；解析失败 400
- 上报请求为 `Content-Length` 定长格式，不支持 chunked 传输编码
- UDP `<discovery-port>` 收 `{"neckguard": "discover"}` 探针，单播回接收端地址

截图按 `YYYYMMDD_HHmmss_<类型>.jpg` 保存在 `snapshots/`，只保留最近 200 张。

---

# 无线相机模式（电脑检测）

手机只把摄像头画面推给电脑，姿态检测、判定和提醒全在电脑上做。适合两种情况：手机性能不够或发热，以及电脑有独显想用更大的模型换更高的准确率。

手机端用的是 lite 模型加 640x480，在手抬到脸前遮挡时追踪会抖；电脑端可以跑 YOLO26 的 l 或 x 尺寸加 960 分辨率，遮挡下稳得多。

## 装依赖

```powershell
cd pc
python -m pip install -r requirements-camera.txt
```

`torch` 要按显卡单独装，版本对不上会报 `no kernel image is available`：

| 显卡 | 驱动要求 | 安装命令 |
|---|---|---|
| RTX 50 系（5090 等，Blackwell / sm_120） | >= 570 | `pip install torch torchvision --index-url https://download.pytorch.org/whl/cu128` |
| RTX 40 / 30 系 | >= 528 | `pip install torch torchvision --index-url https://download.pytorch.org/whl/cu124` |

装完自检，两行都要是 True 和你的卡名：

```powershell
python -c "import torch; print(torch.cuda.is_available(), torch.cuda.get_device_name(0))"
```

首次运行会从 GitHub 下载模型权重，走代理：

```powershell
$env:HTTPS_PROXY = "http://127.0.0.1:2080"
```

没装成 torch 也能用：加 `--backend mediapipe` 走 CPU 后备（需要 Python 3.11 或 3.12），准确率与手机端持平，只是不吃 GPU。

## 用法

手机 App「设置」页把「检测方式」选成「电脑检测」，回「摆放/校准」页点「开始推流」。然后在电脑上：

```powershell
cd pc
python neck_camera_monitor.py --show
```

不带参数就自动扫描局域网里的手机。扫不到时用手机监测页显示的地址手填：

```powershell
python neck_camera_monitor.py --url http://192.168.1.20:8767/video --show
```

`--show` 会开一个预览窗，能看到实时的耳肩髋连线和角度，并支持快捷键：

- `c` 校准：保持端正坐姿 3 秒，取中位数做个人基线，按「后端 + 模型」存进 `camera_monitor.json`，下次用同一组合时自动带上
- `r` 清除校准，回到绝对阈值
- `q` 退出

不加 `--show` 就是纯后台模式，只在控制台打状态行。

## 常用参数

| 参数 | 说明 | 默认 |
|---|---|---|
| `--url` | 手机推流地址，不填则自动扫描 | 自动 |
| `--token` | 推流密钥，与手机端设置页一致 | 空 |
| `--backend` | `yolo` 或 `mediapipe` | yolo |
| `--model` | YOLO 权重 | 按显存自动选 |
| `--imgsz` | 推理分辨率，越大越准也越慢 | 960 |
| `--half` | 半精度推理，GPU 上更快 | 关 |
| `--device` | `cuda:0` 或 `cpu` | 自动 |
| `--threshold` | 未校准时的绝对阈值（度） | 35 |
| `--window-sec` / `--consecutive-windows` | 确认窗时长与连续窗数 | 3 / 2 |
| `--trigger-sec` | 超过阈值持续多久进入确认 | 1.4 |
| `--recover-sec` | 低于退出线持续多久算恢复 | 3.5 |
| `--invalid-grace-sec` | 计时途中看不清的容忍时长 | 1.0 |
| `--min-valid-ratio` | 确认窗内有效帧至少要占期望帧数的多少 | 1/3 |
| `--cooldown-min` | 两次响铃之间的最小间隔（分钟） | 10 |
| `--allow-no-hip` | 髋不可见时退回竖直参考继续判定 | 关 |
| `--torso-hold-sec` | 髋丢失后沿用上次躯干方向多少秒，0 关闭 | 2 |
| `--kpt-conf` | YOLO 关键点可信下限 | 0.5 |
| `--mp-visibility` | MediaPipe 关键点 visibility 下限 | 0.5 |
| `--quiet-hours 23-7` | 静默时段，只记录不提醒 | 无 |
| `--mute` / `--beep` | 静音 / 用蜂鸣代替提示音 | 关 |

模型按显存自动选：16 GB 以上用 `yolo26x-pose`，10 GB 以上用 l，7 GB 以上用 m，再小用 s。想固定就用 `--model yolo26l-pose.pt`。

几个 v0.7 的行为要点：

- **触发与恢复按时长而不是帧数**。以前按帧数计，手机 1.4 fps 的「连续 2 帧」是 1.4 秒，电脑 10 fps 只有 0.2 秒，两端根本不是一回事。现在换帧率、换后端都不影响判定。
- **短暂看不清不清零计时**。抓一下脸、手挡住耳朵，只要不超过 `--invalid-grace-sec` 就只挂起计时，不用从头再来。
- **髋被桌子挡住时沿用上次躯干方向**（`--torso-hold-sec`）。退回竖直参考等于换了角度口径，伏案时同一个姿势能从 11° 跳到 35° 直接误报；沿用躯干方向则数值不动。
- **基线按后端和模型分开存**。`camera_monitor.json` 里是 `baselines: {"yolo:yolo26s-pose": 12.3, "mediapipe": 19.5}`。YOLO 与 MediaPipe 的关键点位置有系统偏差，共用一个基线会让阈值整体偏移，所以换后端后要各校准一次。旧版本的单个基线会自动迁移，先凑合用，建议重新校准。

## 推流协议

手机端实现在 `android/.../report/MjpegServer.kt`，纯 JDK ServerSocket，没引第三方库。

- `GET /video` → `multipart/x-mixed-replace; boundary=neckguardframe`，每段一帧 JPEG，带 `Content-Length`
- `GET /snapshot` → 最新一帧 JPEG
- `GET /info` → `{"neckguard":"camera","name","width","height","fps","lens","token_required","v":1}`
- `GET /` → 一页内嵌 `<img>` 的 HTML，**用浏览器打开手机地址能直接看到画面**，排查时先试这个
- 请求头 `X-Neck-Token`，浏览器里没法加头，所以也支持 `?token=xxx`

自动发现（UDP 8768，方向与接收端相反，这次是电脑广播、手机应答）：

- 电脑广播 → `{"neckguard": "discover-camera", "v": 1}`
- 手机单播回 → `{"neckguard": "camera", "name": "<机型>", "host": "<同网段IP>", "port": 8767, "token_required": false, "v": 1}`

手机回的 `host` 由它那侧按电脑所在网段算出，避免回成数据网络或热点的地址。

## 排查

**扫不到手机**：按顺序查手机是否点了「开始推流」→ 手机和电脑是否同一个 Wi-Fi → 路由器是否开了 AP 隔离（开了只能手填）→ 电脑防火墙是否放行 UDP 8768 的回包。

**扫到了但连不上**：先用浏览器打开 `http://手机IP:8767/`，能看到画面说明推流正常，问题在本脚本这侧。

**注意代理**：如果设了 `HTTP_PROXY` / `HTTPS_PROXY`（本仓库访问 GitHub 就要走代理），默认的 urllib 会把局域网地址也扔给代理，表现为 502。脚本内部已强制直连，不受环境变量影响。

**画面卡顿或延迟大**：降低手机端的推流分辨率或帧率，或者调小 `--imgsz`。推流是「只保留最新帧」的，慢了会丢帧而不是堆积延迟。

**报 `no kernel image is available`**：torch 版本与显卡对不上，按上面的表重装。

## 本机自测

不需要手机也能验证拉流与解析：

```powershell
cd pc
python -m unittest test_camera_monitor -v
```

它会起一个假的推流服务端，覆盖分帧、密钥校验、断线重连、发现协议解析。
