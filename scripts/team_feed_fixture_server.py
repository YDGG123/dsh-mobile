#!/usr/bin/env python3
"""仅本机验证用的团队流夹具（team-feed fixture）。

用途：验证「本地名册缓存失效时不许带 resume」这条客户端修复（2026-10-31 Bot 页空白根因）。
它只做两件真实网关会做的事：
  · 收到 auth 里带 resume → 只回 `sync.done{reason:'caught-up'}`（复现事故：不发快照）
  · 收到 auth 里没有 resume → 回一帧完整 `snapshot`（bots/rooms）

不连真实 DSH，不落盘，不做任何写操作。Ctrl-C 停止。默认监听 127.0.0.1:18790。

用法：
  python3 scripts/team_feed_fixture_server.py            # 默认 127.0.0.1:18790
  python3 scripts/team_feed_fixture_server.py --port 18791
"""
import argparse
import base64
import hashlib
import json
import socketserver
import struct
import time

BOTS = [
    {
        "id": "bot-fixture-alpha",
        "name": "FixtureAlice",
        "role": "Bot",
        "sessionId": "bot-fixture-alpha",
        "activity": "idle",
        "avatar": {"shape": "whale", "hasImage": False},
    },
    {
        "id": "bot-fixture-beta",
        "name": "FixtureBob",
        "role": "Bot",
        "sessionId": "bot-fixture-beta",
        "activity": "idle",
        "avatar": {"shape": "whale", "hasImage": False},
    },
]

SNAPSHOT = {
    "kind": "snapshot",
    "instanceId": "fixture-instance",
    "seq": 100,
    "mainBotIds": ["bot-fixture-alpha"],
    "degraded": False,
    "bots": BOTS,
    "rooms": [
        {
            "id": "room-fixture-1",
            "name": "FixtureRoom",
            "members": ["bot-fixture-alpha", "bot-fixture-beta"],
            "activity": "idle",
        }
    ],
}


class Handler(socketserver.StreamRequestHandler):
    def opcode_frame(self, opcode, payload):
        header = bytearray()
        header.append(0x80 | opcode)
        length = len(payload)
        if length < 126:
            header.append(length)
        elif length < 65536:
            header.append(126)
            header.extend(struct.pack("!H", length))
        else:
            header.append(127)
            header.extend(struct.pack("!Q", length))
        return bytes(header) + payload

    def send_json(self, object_):
        payload = json.dumps(object_, ensure_ascii=False).encode("utf-8")
        try:
            self.wfile.write(self.opcode_frame(1, payload))
            self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError):
            print("[fixture] client went away", flush=True)
            raise

    def handle(self):
        self.request.settimeout(120)
        self.rfile.readline()
        headers = {}
        while True:
            line = self.rfile.readline().decode(errors="replace").strip()
            if not line:
                break
            if ":" in line:
                key, value = line.split(":", 1)
                headers[key.lower()] = value.strip()
        accept = base64.b64encode(
            hashlib.sha1((headers.get("sec-websocket-key", "") + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode()).digest()
        ).decode()
        protocol = headers.get("sec-websocket-protocol")
        extra = f"Sec-WebSocket-Protocol: {protocol}\r\n" if protocol else ""
        self.wfile.write(
            (
                "HTTP/1.1 101 Switching Protocols\r\n"
                "Upgrade: websocket\r\n"
                "Connection: Upgrade\r\n"
                f"Sec-WebSocket-Accept: {accept}\r\n"
                f"{extra}\r\n"
            ).encode()
        )
        self.wfile.flush()
        print(f"[fixture] upgrade path={headers.get('x-dsh-channel', '-')}", flush=True)
        self.send_json({"type": "ready", "instanceId": "fixture-instance"})
        try:
            while True:
                head = self.rfile.read(2)
                if len(head) < 2 or head[0] & 0x0F == 8:
                    print("[fixture] client closed", flush=True)
                    return
                opcode = head[0] & 0x0F
                length = head[1] & 0x7F
                if length == 126:
                    length = struct.unpack("!H", self.rfile.read(2))[0]
                elif length == 127:
                    length = struct.unpack("!Q", self.rfile.read(8))[0]
                mask = self.rfile.read(4) if head[1] & 0x80 else b""
                data = self.rfile.read(length)
                if mask:
                    data = bytes(value ^ mask[index % 4] for index, value in enumerate(data))
                if opcode == 9:
                    self.wfile.write(self.opcode_frame(10, data))
                    self.wfile.flush()
                    continue
                if opcode != 1:
                    continue
                frame = json.loads(data.decode("utf-8", errors="replace"))
                kind = frame.get("type")
                if kind == "ping":
                    self.send_json({"type": "pong", "at": int(time.time() * 1000)})
                    continue
                if kind != "auth":
                    print(f"[fixture] ignore frame type={kind}", flush=True)
                    continue
                resume = frame.get("resume") or {}
                if resume:
                    after_seq = resume.get("afterSeq")
                    print(f"[fixture] AUTH resume=yes afterSeq={after_seq} instance={resume.get('instanceId')}", flush=True)
                    # 复现事故形态：网关认为已追平 → 只发 sync.done，不发快照。
                    self.send_json({"type": "sync.done", "reason": "caught-up", "seq": after_seq})
                else:
                    print("[fixture] AUTH resume=no → sending full snapshot", flush=True)
                    self.send_json(SNAPSHOT)
                    self.send_json({"type": "sync.done", "reason": "snapshot", "seq": SNAPSHOT["seq"]})
        except Exception as error:  # noqa: BLE001 - 夹具进程，出错打出来即可
            print(f"[fixture] connection ended: {error}", flush=True)


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=18790)
    args = parser.parse_args()
    with Server((args.host, args.port), Handler) as server:
        print(f"[fixture] listening on ws://{args.host}:{args.port}/mobile/v1/team", flush=True)
        server.serve_forever()


if __name__ == "__main__":
    main()
