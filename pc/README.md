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

- `GET /api/ping` 返回 `{"ok": true, "name": "neck-receiver", "version": "1"}`
- `POST /api/posture/events`，`multipart/form-data`，字段 `event`（JSON）与可选 `snapshot`（JPEG），请求头 `X-Neck-Token`
- 返回 200 `{"ok": true}`；密钥不匹配 401；解析失败 400
- 上报请求为 `Content-Length` 定长格式，不支持 chunked 传输编码
- UDP `<discovery-port>` 收 `{"neckguard": "discover"}` 探针，单播回接收端地址

截图按 `YYYYMMDD_HHmmss_<类型>.jpg` 保存在 `snapshots/`，只保留最近 200 张。
