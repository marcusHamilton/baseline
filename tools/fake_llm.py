"""A fake OpenAI-compatible server for testing the AI assistant on an emulator, without a real key or any credit.

Run:  ~/.local/bin/uv run --python 3.12 --no-project python -I tools/fake_llm.py [--port 8090] [--key fake-ai-key] [--fail 402|429|500]
Build: ./gradlew :app:installDebug -PgarminBaseUrl=http://127.0.0.1:8080   (a test build accepts http://127.0.0.1 as an AI base URL)
Then: adb reverse tcp:8090 tcp:8090, and in Settings -> AI add the key "fake-ai-key" with base URL http://127.0.0.1:8090/v1.
Any other key gets a 401, so the rejected-key path can be tested. Never ship a build made with -PgarminBaseUrl.

  GET  /v1/models            three models in OpenRouter's shape: one without tools, one with, and deepseek/deepseek-v4.1-flash.
  POST /v1/chat/completions  streams (or not, per "stream") a scripted answer: "Your first number here is <N>.", where N is
                             the first number in the system message. When tools are offered and no tool result is in the
                             conversation yet, it first calls daily_metrics for the last 30 days. The key test's ping
                             request gets a tool_calls reply. The no-tools model answers 400 to any request with tools.
  --fail STATUS              every authorised request answers STATUS instead (429 carries Retry-After: 1), to see the
                             error rows: 402 "No credit left at 127.0.0.1", 429 "Busy", 500 "Couldn't reach".

Each request is logged to stderr as method, path, status and the request's approximate size in tokens (characters / 4).
The body is never logged. Stdlib only.
"""
import argparse
import json
import re
import sys
import time
from datetime import date, timedelta
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

NO_TOOLS_MODEL = "fake/no-tools-model"
MODELS = [
    {"id": NO_TOOLS_MODEL, "name": "Fake: No Tools", "context_length": 8192,
     "pricing": {"prompt": "0.0000001", "completion": "0.0000004"}, "supported_parameters": ["temperature", "max_tokens"]},
    {"id": "fake/tools-model", "name": "Fake: Tools", "context_length": 32768,
     "pricing": {"prompt": "0.0000002", "completion": "0.0000008"},
     "supported_parameters": ["temperature", "max_tokens", "tools", "tool_choice"]},
    {"id": "deepseek/deepseek-v4.1-flash", "name": "DeepSeek: V4.1 Flash", "context_length": 131072,
     "pricing": {"prompt": "0.00000014", "completion": "0.00000028"},
     "supported_parameters": ["temperature", "max_tokens", "tools", "tool_choice"]},
]
NUMBER = re.compile(r"\d+(?:\.\d+)?")
LOOKBACK_DAYS = 30
STREAM_PIECES = 4  # a scripted answer is streamed in about this many chunks


def approx_tokens(body: bytes) -> int:
    return len(body) // 4


def first_number(text: str) -> str | None:
    m = NUMBER.search(text)
    return m.group() if m else None


def answer_text(system: str) -> str:
    return f"Your first number here is {first_number(system) or 'none'}."


def tool_names(request: dict) -> list[str]:
    return [t.get("function", {}).get("name", "") for t in request.get("tools") or []]


def plan(request: dict, today: date) -> tuple[str, object]:
    """What the model does next: ("tool_calls", [{id, name, arguments}]) or ("text", answer)."""
    messages = request.get("messages") or []
    system = next((m.get("content") or "" for m in messages if m.get("role") == "system"), "")
    names = tool_names(request)
    choice = request.get("tool_choice", "auto")
    if "ping" in names:  # the key test: it wants to see a tool call
        return "tool_calls", [{"id": "call_ping", "name": "ping", "arguments": "{}"}]
    looked_up = any(m.get("role") == "tool" for m in messages)
    if "daily_metrics" in names and choice != "none" and not looked_up:
        args = {"from": (today - timedelta(days=LOOKBACK_DAYS - 1)).isoformat(), "to": today.isoformat(),
                "fields": ["recovery", "hrv", "sleep_hours"]}
        return "tool_calls", [{"id": "call_daily_metrics", "name": "daily_metrics", "arguments": json.dumps(args)}]
    return "text", answer_text(system)


def split_pieces(text: str, pieces: int) -> list[str]:
    """[text] cut at word boundaries into about [pieces] chunks that join back to the original."""
    words = re.findall(r"\S+\s*", text)
    size = max(1, -(-len(words) // pieces))
    return ["".join(words[i:i + size]) for i in range(0, len(words), size)]


def completion_id() -> str:
    return f"chatcmpl-fake-{int(time.time() * 1000)}"


def make_handler(key: str, fail: int | None = None, today: date | None = None, delay: float = 0.05):
    today = today or date.today()

    class Handler(BaseHTTPRequestHandler):
        tokens = 0

        def reply(self, code: int, body: bytes = b"", ctype: str = "application/json", headers: dict | None = None):
            self.send_response(code)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(body)))
            for k, v in (headers or {}).items():
                self.send_header(k, v)
            self.end_headers()
            self.wfile.write(body)

        def error(self, code: int, message: str, headers: dict | None = None):
            self.reply(code, json.dumps({"error": {"code": code, "message": message}}).encode(), headers=headers)

        def authorised(self) -> bool:
            if self.headers.get("Authorization") == f"Bearer {key}":
                return True
            self.error(401, "Invalid API key")
            return False

        def read_body(self) -> bytes:
            body = self.rfile.read(int(self.headers.get("Content-Length") or 0))
            self.tokens = approx_tokens(body)
            return body

        def failing(self) -> bool:
            if fail is None:
                return False
            self.error(fail, "Failing on purpose (--fail)", {"Retry-After": "1"} if fail == 429 else None)
            return True

        def do_GET(self):
            path = self.path.split("?")[0]
            if path not in ("/v1/models", "/models"):
                return self.error(404, "Not found")
            if not self.authorised() or self.failing():
                return
            self.reply(200, json.dumps({"data": MODELS}).encode())

        def do_POST(self):
            path = self.path.split("?")[0]
            raw = self.read_body()
            if path not in ("/v1/chat/completions", "/chat/completions"):
                return self.error(404, "Not found")
            if not self.authorised() or self.failing():
                return
            try:
                request = json.loads(raw)
                assert isinstance(request, dict)
            except (ValueError, AssertionError):
                return self.error(400, "Malformed request")
            model = request.get("model") or MODELS[-1]["id"]
            if model == NO_TOOLS_MODEL and request.get("tools"):
                return self.error(400, "This model does not support tools")
            kind, content = plan(request, today)
            if request.get("stream"):
                self.stream(model, kind, content)
            else:
                self.single(model, kind, content)

        def single(self, model: str, kind: str, content):
            if kind == "tool_calls":
                message = {"role": "assistant", "content": None, "tool_calls": [
                    {"id": c["id"], "type": "function", "function": {"name": c["name"], "arguments": c["arguments"]}} for c in content]}
            else:
                message = {"role": "assistant", "content": content}
            finish = "tool_calls" if kind == "tool_calls" else "stop"
            body = {"id": completion_id(), "object": "chat.completion", "model": model,
                    "choices": [{"index": 0, "message": message, "finish_reason": finish}],
                    "usage": {"prompt_tokens": self.tokens, "completion_tokens": 8, "total_tokens": self.tokens + 8}}
            self.reply(200, json.dumps(body).encode())

        def stream(self, model: str, kind: str, content):
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.send_header("Cache-Control", "no-cache")
            self.end_headers()
            cid = completion_id()

            def send(delta: dict, finish: str | None = None):
                chunk = {"id": cid, "object": "chat.completion.chunk", "model": model,
                         "choices": [{"index": 0, "delta": delta, "finish_reason": finish}]}
                self.wfile.write(f"data: {json.dumps(chunk)}\n\n".encode())
                self.wfile.flush()
                time.sleep(delay)

            send({"role": "assistant", "content": ""})
            if kind == "tool_calls":
                for i, c in enumerate(content):
                    send({"tool_calls": [{"index": i, "id": c["id"], "type": "function",
                                          "function": {"name": c["name"], "arguments": ""}}]})
                    half = len(c["arguments"]) // 2  # arguments arrive in pieces, as from a real provider
                    for part in (c["arguments"][:half], c["arguments"][half:]):
                        if part:
                            send({"tool_calls": [{"index": i, "function": {"arguments": part}}]})
                send({}, "tool_calls")
            else:
                for piece in split_pieces(content, STREAM_PIECES):
                    send({"content": piece})
                send({}, "stop")
            self.wfile.write(b"data: [DONE]\n\n")
            self.wfile.flush()

        def log_request(self, code="-", size="-"):
            sys.stderr.write(f"{self.command} {self.path.split('?')[0]} -> {code} (~{self.tokens} tokens)\n")

        def log_message(self, fmt, *args):
            pass  # errors the base class would log carry no request detail we want; the line above is the log

    return Handler


def main():
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("--port", type=int, default=8090)
    p.add_argument("--key", default="fake-ai-key", help="the only API key accepted; any other gets a 401")
    p.add_argument("--fail", type=int, choices=(402, 429, 500), help="answer every authorised request with this status")
    p.add_argument("--today", type=date.fromisoformat, default=date.today(),
                   help="YYYY-MM-DD the 30-day daily_metrics lookup ends on; default is the host's date")
    args = p.parse_args()
    server = ThreadingHTTPServer(("0.0.0.0", args.port), make_handler(args.key, args.fail, args.today))
    print(f"fake AI server on :{args.port}; key: {args.key}" + (f"; failing every request with {args.fail}" if args.fail else ""),
          flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
