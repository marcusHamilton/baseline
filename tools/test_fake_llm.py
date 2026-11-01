"""Tests for fake_llm.py, the fake OpenAI-compatible server for the AI assistant.

Run:  ~/.local/bin/uv run --python 3.12 --no-project python -I tools/test_fake_llm.py
"""
import importlib.util
import json
import re
import threading
import unittest
import urllib.error
import urllib.request
from datetime import date, timedelta
from http.server import ThreadingHTTPServer
from pathlib import Path

_spec = importlib.util.spec_from_file_location("fake_llm", Path(__file__).resolve().parent / "fake_llm.py")
fl = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(fl)

KEY = "test-key"
TODAY = date(2026, 11, 1)
SYSTEM = "You are a coach.\n\nToday is Sun 1 Nov 2026 (Europe/London). Recovery 71%, HRV 62 ms."
TOOLS = [{"type": "function", "function": {"name": "daily_metrics", "parameters": {"type": "object", "properties": {}}}}]
PING = [{"type": "function", "function": {"name": "ping", "parameters": {"type": "object", "properties": {}}}}]


class Server:
    def __init__(self, fail=None, key=KEY):
        self.httpd = ThreadingHTTPServer(("127.0.0.1", 0), fl.make_handler(key, fail=fail, today=TODAY, delay=0))
        self.base = f"http://127.0.0.1:{self.httpd.server_port}/v1"
        threading.Thread(target=self.httpd.serve_forever, daemon=True).start()

    def close(self):
        self.httpd.shutdown()
        self.httpd.server_close()

    def request(self, path, body=None, key=KEY):
        headers = {"Content-Type": "application/json"}
        if key is not None:
            headers["Authorization"] = f"Bearer {key}"
        data = None if body is None else json.dumps(body).encode()
        return urllib.request.urlopen(urllib.request.Request(self.base + path, data=data, headers=headers))

    def chat(self, messages, **extra):
        r = self.request("/chat/completions", {"model": "deepseek/deepseek-v4.1-flash", "messages": messages, **extra})
        return r.read().decode()


def user_messages(system=SYSTEM, question="How did I sleep?"):
    return [{"role": "system", "content": system}, {"role": "user", "content": question}]


def sse_chunks(raw: str):
    """The JSON payloads of a text/event-stream body, and whether it ended with [DONE]."""
    lines = [l for l in raw.splitlines() if l.startswith("data:")]
    payloads = [l[5:].strip() for l in lines]
    return [json.loads(p) for p in payloads if p != "[DONE]"], payloads[-1] == "[DONE]"


class FakeLlm(unittest.TestCase):
    def server(self, **kwargs):
        s = Server(**kwargs)
        self.addCleanup(s.close)
        return s

    def test_wrong_key_is_401_on_both_endpoints(self):
        s = self.server()
        for path, body in (("/models", None), ("/chat/completions", {"messages": user_messages()})):
            with self.assertRaises(urllib.error.HTTPError) as e:
                s.request(path, body, key="wrong")
            self.assertEqual(e.exception.code, 401)
        with self.assertRaises(urllib.error.HTTPError) as e:
            s.request("/models", key=None)
        self.assertEqual(e.exception.code, 401)

    def test_models_lists_three_with_openrouter_fields(self):
        s = self.server()
        models = json.load(s.request("/models"))["data"]
        self.assertEqual(len(models), 3)
        ids = [m["id"] for m in models]
        self.assertIn("deepseek/deepseek-v4.1-flash", ids)
        for m in models:
            self.assertIsInstance(m["context_length"], int)
            self.assertEqual(set(m["pricing"]), {"prompt", "completion"})
            self.assertIsInstance(m["supported_parameters"], list)
        with_tools = [m["id"] for m in models if "tools" in m["supported_parameters"]]
        self.assertEqual(len(with_tools), 2)  # one model without tools, the other two with

    def test_ping_test_answers_with_a_tool_call(self):
        s = self.server()
        body = {"model": "deepseek/deepseek-v4.1-flash", "stream": False, "max_tokens": 16, "tools": PING, "tool_choice": "required",
                "messages": [{"role": "system", "content": "Call the ping tool."}, {"role": "user", "content": "ping"}]}
        reply = json.load(s.request("/chat/completions", body))
        message = reply["choices"][0]["message"]
        self.assertEqual(message["tool_calls"][0]["function"]["name"], "ping")
        self.assertEqual(reply["choices"][0]["finish_reason"], "tool_calls")

    def test_a_model_without_tools_refuses_them_with_400_and_answers_without(self):
        s = self.server()
        body = {"model": "fake/no-tools-model", "stream": False, "tools": PING, "tool_choice": "auto", "messages": user_messages()}
        with self.assertRaises(urllib.error.HTTPError) as e:
            s.request("/chat/completions", body)
        self.assertEqual(e.exception.code, 400)
        del body["tools"], body["tool_choice"]
        message = json.load(s.request("/chat/completions", body))["choices"][0]["message"]
        self.assertNotIn("tool_calls", message)

    def test_streamed_answer_quotes_the_first_number_and_ends_with_done(self):
        s = self.server()
        raw = s.chat(user_messages(), stream=True)
        chunks, done = sse_chunks(raw)
        self.assertTrue(done)
        self.assertTrue(raw.rstrip().endswith("data: [DONE]"))
        text = "".join(c["choices"][0]["delta"].get("content", "") for c in chunks)
        self.assertEqual(text, "Your first number here is 1.")  # "1" of "1 Nov", the first number in the system message
        self.assertGreater(len([c for c in chunks if c["choices"][0]["delta"].get("content")]), 1)  # really streamed in pieces
        self.assertEqual(chunks[-1]["choices"][0]["finish_reason"], "stop")

    def test_system_message_without_a_number(self):
        s = self.server()
        reply = json.loads(s.chat(user_messages(system="No digits here."), stream=False))
        self.assertEqual(reply["choices"][0]["message"]["content"], "Your first number here is none.")

    def test_non_streamed_answer_is_one_json_message(self):
        s = self.server()
        reply = json.loads(s.chat(user_messages(), stream=False))
        self.assertEqual(reply["choices"][0]["message"]["content"], "Your first number here is 1.")
        self.assertEqual(reply["choices"][0]["finish_reason"], "stop")

    def test_first_round_with_tools_asks_for_daily_metrics_over_the_last_30_days(self):
        s = self.server()
        chunks, done = sse_chunks(s.chat(user_messages(), stream=True, tools=TOOLS, tool_choice="auto"))
        self.assertTrue(done)
        calls = {}
        for c in chunks:
            for d in c["choices"][0]["delta"].get("tool_calls", []):
                slot = calls.setdefault(d["index"], {"id": None, "name": None, "arguments": ""})
                slot["id"] = d.get("id") or slot["id"]
                slot["name"] = d.get("function", {}).get("name") or slot["name"]
                slot["arguments"] += d.get("function", {}).get("arguments") or ""
        self.assertEqual(list(calls), [0])
        self.assertEqual(calls[0]["name"], "daily_metrics")
        self.assertTrue(calls[0]["id"])
        args = json.loads(calls[0]["arguments"])
        self.assertEqual(args["to"], TODAY.isoformat())
        self.assertEqual(args["from"], (TODAY - timedelta(days=29)).isoformat())  # 30 days, both ends counted
        self.assertEqual(chunks[-1]["choices"][0]["finish_reason"], "tool_calls")

    def test_after_the_tool_result_it_answers_in_text(self):
        s = self.server()
        messages = user_messages() + [
            {"role": "assistant", "tool_calls": [{"id": "call_1", "type": "function", "function": {"name": "daily_metrics", "arguments": "{}"}}]},
            {"role": "tool", "tool_call_id": "call_1", "content": "date,hrv\n2026-10-31,64"},
        ]
        chunks, done = sse_chunks(s.chat(messages, stream=True, tools=TOOLS, tool_choice="auto"))
        self.assertTrue(done)
        text = "".join(c["choices"][0]["delta"].get("content", "") for c in chunks)
        self.assertEqual(text, "Your first number here is 1.")

    def test_tool_choice_none_never_calls_a_tool(self):
        s = self.server()
        reply = json.loads(s.chat(user_messages(), stream=False, tools=TOOLS, tool_choice="none"))
        self.assertNotIn("tool_calls", reply["choices"][0]["message"])

    def test_fail_429_has_retry_after_1(self):
        s = self.server(fail=429)
        with self.assertRaises(urllib.error.HTTPError) as e:
            s.chat(user_messages(), stream=True)
        self.assertEqual(e.exception.code, 429)
        self.assertEqual(e.exception.headers["Retry-After"], "1")

    def test_fail_402_and_500(self):
        for code in (402, 500):
            s = self.server(fail=code)
            with self.assertRaises(urllib.error.HTTPError) as e:
                s.chat(user_messages(), stream=True)
            self.assertEqual(e.exception.code, code)

    def test_fail_still_checks_the_key_first(self):
        s = self.server(fail=500)
        with self.assertRaises(urllib.error.HTTPError) as e:
            s.request("/chat/completions", {"messages": user_messages()}, key="wrong")
        self.assertEqual(e.exception.code, 401)

    def test_unknown_path_is_404(self):
        s = self.server()
        with self.assertRaises(urllib.error.HTTPError) as e:
            s.request("/nope")
        self.assertEqual(e.exception.code, 404)

    def test_approximate_token_count_is_chars_over_four(self):
        self.assertEqual(fl.approx_tokens(b"x" * 400), 100)
        self.assertEqual(fl.approx_tokens(b""), 0)

    def test_first_number_helper(self):
        self.assertEqual(fl.first_number("Recovery 71.5% on 3 Nov"), "71.5")
        self.assertEqual(fl.first_number("none"), None)


if __name__ == "__main__":
    unittest.main()
