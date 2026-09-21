# -*- coding: utf-8 -*-
"""模拟手机端的发现探针，验证接收端 DiscoveryService 能否正确回应。

用法：先启动 neck_receiver.py，再另开窗口运行本脚本。
这是 PcDiscovery.kt 的 Python 等价物，用于在没有手机的情况下自测。
"""
import json
import socket
import sys

DISCOVERY_PORT = 8766
PROBE = json.dumps({"neckguard": "discover", "v": 1}).encode("utf-8")


def broadcast_targets(port):
    """255.255.255.255 加各网卡定向广播，覆盖屏蔽全局广播的路由器。"""
    targets = [("255.255.255.255", port)]
    try:
        import ipaddress
        import subprocess
        # Windows 下用 ipconfig 拿不到掩码就算了，全局广播通常够用
    except Exception:
        pass
    return targets


def main():
    port = int(sys.argv[1]) if len(sys.argv) > 1 else DISCOVERY_PORT
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    s.settimeout(2.0)
    found = {}
    try:
        for target in broadcast_targets(port):
            try:
                s.sendto(PROBE, target)
                print("已发送探针 ->", target)
            except OSError as e:
                print("发送失败", target, e)
        # 也给回环发一份，方便本机自测
        try:
            s.sendto(PROBE, ("127.0.0.1", port))
            print("已发送探针 -> ('127.0.0.1', %d)" % port)
        except OSError:
            pass
        while True:
            try:
                data, addr = s.recvfrom(4096)
            except socket.timeout:
                break
            try:
                info = json.loads(data.decode("utf-8"))
            except ValueError:
                continue
            if info.get("neckguard") != "receiver":
                continue
            key = (info.get("host"), info.get("port"))
            if key in found:
                continue
            found[key] = info
            print("发现接收端: name=%s host=%s port=%s token_required=%s (来自 %s)" % (
                info.get("name"), info.get("host"), info.get("port"),
                info.get("token_required"), addr[0]))
    finally:
        s.close()

    if not found:
        print("未发现任何接收端")
        return 1
    print("\n共发现 %d 个，手机端会把地址填成：" % len(found))
    for (host, p) in found:
        print("    http://%s:%s" % (host, p))
    return 0


if __name__ == "__main__":
    sys.exit(main())
