#!/usr/bin/env python3
"""Private LAN Whisper bridge. No cloud services or persistent audio storage."""
import argparse
import hmac
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = Path(__file__).resolve().parent
MAX_BYTES = 128 * 1024 * 1024
MAX_SECONDS = 7200


def transcribe(audio, work, cli, model):
    duration = float(subprocess.check_output([
        "ffprobe", "-v", "error", "-show_entries", "format=duration",
        "-of", "default=noprint_wrappers=1:nokey=1", str(audio)], timeout=30))
    if not 0 < duration <= MAX_SECONDS:
        raise ValueError("녹음은 2시간 이내여야 합니다.")
    wav = work / "audio.wav"
    subprocess.run(["ffmpeg", "-nostdin", "-v", "error", "-i", str(audio),
                    "-vn", "-ar", "16000", "-ac", "1", "-c:a", "pcm_s16le", str(wav)],
                   check=True, timeout=180, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    output = work / "result"
    subprocess.run([str(cli), "-m", str(model), "-f", str(wav), "-l", "ko",
                    "-oj", "-of", str(output), "-sns", "-np", "-t", "6"],
                   check=True, timeout=7200, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    result = json.loads(output.with_suffix(".json").read_text())
    text = "\n".join(row["text"].strip() for row in result["transcription"] if row["text"].strip()).strip()
    if not text:
        raise ValueError("인식된 음성이 없습니다. 원래 받아쓰기는 유지됩니다.")
    return {"text": text, "model": "whisper-large-v3-q5_0", "seconds": duration}


class Server(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True


class DualStackServer(Server):
    address_family = socket.AF_INET6

    def server_bind(self):
        self.socket.setsockopt(socket.IPPROTO_IPV6, socket.IPV6_V6ONLY, 0)
        super().server_bind()


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass  # Do not log tokens, audio, or transcript text.

    def reply(self, status, data):
        body = json.dumps(data, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(body)
        self.close_connection = True

    def do_GET(self):
        self.reply(200 if self.path == "/health" else 404,
                   {"service": "study-helper-local-stt"})

    def do_POST(self):
        self.connection.settimeout(120)
        if self.path != "/transcribe":
            return self.reply(404, {"error": "경로를 찾을 수 없습니다."})
        if not hmac.compare_digest(self.headers.get("Authorization", ""), "Bearer " + self.server.token):
            return self.reply(401, {"error": "Mac 연결 인증이 필요합니다."})
        try:
            size = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            size = 0
        if not 0 < size <= MAX_BYTES or self.headers.get("Transfer-Encoding"):
            return self.reply(413, {"error": "녹음 파일은 128MB 이내여야 합니다."})
        if not self.server.job.acquire(blocking=False):
            return self.reply(409, {"error": "Mac에서 다른 녹음을 변환하고 있습니다. 잠시 뒤 다시 시도하세요."})
        try:
            with tempfile.TemporaryDirectory(prefix="study-stt-") as directory:
                work = Path(directory)
                audio = work / "upload.audio"
                with audio.open("wb") as output:
                    remaining = size
                    while remaining:
                        chunk = self.rfile.read(min(remaining, 1024 * 1024))
                        if not chunk:
                            raise ValueError("녹음 전송이 중단되었습니다.")
                        output.write(chunk)
                        remaining -= len(chunk)
                result = transcribe(audio, work, self.server.cli, self.server.model)
            self.reply(200, result)
        except ValueError as error:
            self.reply(422, {"error": str(error)})
        except subprocess.TimeoutExpired:
            self.reply(504, {"error": "변환 시간이 초과되었습니다. 짧은 녹음으로 다시 시도하세요."})
        except (OSError, subprocess.CalledProcessError, KeyError, json.JSONDecodeError):
            try:
                self.reply(500, {"error": "Mac에서 음성을 변환하지 못했습니다. 녹음과 모델을 확인하세요."})
            except OSError:
                pass
        finally:
            self.server.job.release()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="::")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--model", type=Path, default=ROOT / "build/ggml-large-v3-q5_0.bin")
    parser.add_argument("--cli", type=Path, default=ROOT / "build/whisper.cpp/build/bin/whisper-cli")
    args = parser.parse_args()
    if not args.model.is_file() or not args.cli.is_file():
        parser.error("먼저 tools/local-stt/setup.sh를 실행하세요.")
    pairing = ROOT / "build/local-stt-pairing.json"
    if not pairing.exists():
        hostname = subprocess.check_output(["scutil", "--get", "LocalHostName"], text=True).strip()
        with os.fdopen(os.open(pairing, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "w") as file:
            json.dump({"endpoint": f"http://{hostname}.local:{args.port}", "token": secrets.token_urlsafe(32)}, file)
    config = json.loads(pairing.read_text())
    server_type = DualStackServer if ":" in args.host else Server
    server = server_type((args.host, args.port), Handler)
    server.token = config["token"]
    server.cli, server.model, server.job = args.cli, args.model, threading.Lock()
    print(f"Local Whisper ready: {config['endpoint']} (Ctrl+C to stop)", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
