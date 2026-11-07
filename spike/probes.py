#!/usr/bin/env python3
"""TEMPORARY (Stage 1 spike): runtime probes of SUT behavior under LLM failures.

Requires `spike/run-sut.sh up` first. Usage: spike/probes.py <probe-name>...
Each probe: reset MockServer, arm it, send one chat message, report what the user saw
and what reached the mock (count + timestamps).
"""
import json
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime

MOCK = "http://localhost:1080"
SUT = "http://localhost:8080"
COMPLETIONS = "/v1/chat/completions"


def http(method, url, body=None, timeout=600):
    data = body.encode() if isinstance(body, str) else (json.dumps(body).encode() if body is not None else None)
    req = urllib.request.Request(url, data=data, method=method, headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, r.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()


def reset():
    http("PUT", MOCK + "/mockserver/reset")


def arm(response, times=None, last_message_role=None, path=COMPLETIONS):
    req = {"method": "POST", "path": path}
    if last_message_role:
        req["body"] = {"type": "JSON_PATH", "jsonPath": f"$.messages[-1:][?(@.role == '{last_message_role}')]"}
    exp = {"httpRequest": req, "httpResponse": response}
    if times:
        exp["times"] = {"remainingTimes": times, "unlimited": False}
    status, _ = http("PUT", MOCK + "/mockserver/expectation", exp)
    assert status == 201, status


def completion(content=None, tool_calls=None, finish="stop"):
    msg = {"role": "assistant", "content": content}
    if tool_calls:
        msg["tool_calls"] = tool_calls
        finish = "tool_calls"
    body = {"id": "chatcmpl-x", "object": "chat.completion", "created": 1, "model": "gpt-4o",
            "choices": [{"index": 0, "message": msg, "finish_reason": finish}],
            "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2}}
    return {"statusCode": 200, "headers": {"Content-Type": ["application/json"]},
            "body": {"type": "STRING", "string": json.dumps(body)}}


def tool_call(name, args, call_id="call_1"):
    return [{"id": call_id, "type": "function", "function": {"name": name, "arguments": args}}]


def recorded(path=COMPLETIONS):
    _, body = http("PUT", MOCK + "/mockserver/retrieve?type=REQUEST_RESPONSES&format=JSON", {"path": path})
    return json.loads(body) if body else []


def chat(message, timeout=600):
    t0 = time.monotonic()
    try:
        status, body = http("POST", SUT + "/chat", json.dumps(message), timeout=timeout)
    except Exception as e:  # client-side timeout etc.
        status, body = None, f"<client: {type(e).__name__}: {e}>"
    return status, body, time.monotonic() - t0


def report(name, status, body, elapsed, path=COMPLETIONS):
    events = recorded(path)
    stamps = [datetime.strptime(e["timestamp"], "%Y-%m-%d %H:%M:%S.%f") for e in events]
    gaps = [round((b - a).total_seconds(), 2) for a, b in zip(stamps, stamps[1:])]
    print(f"\n=== {name}")
    print(f"user saw: HTTP {status} in {elapsed:.1f}s")
    print(f"body    : {body[:400]!r}")
    print(f"LLM calls to {path}: {len(events)}; gaps between calls (s): {gaps}")
    return events


def probe_429():
    reset()
    arm({"statusCode": 429, "headers": {"Retry-After": ["2"], "Content-Type": ["application/json"]},
         "body": {"type": "STRING", "string": json.dumps({"error": {"message": "Rate limit reached", "type": "requests", "code": "rate_limit_exceeded"}})}})
    report("RATE_LIMIT_429 (always 429, Retry-After: 2)", *chat("Hi"))


def probe_429_then_ok():
    reset()
    arm({"statusCode": 429, "headers": {"Retry-After": ["2"]}}, times=1)
    arm(completion("OK after 429"))
    report("RATE_LIMIT_429 (once, then success)", *chat("Hi"))


def probe_503_twice():
    reset()
    arm({"statusCode": 503}, times=2)
    arm(completion("OK after 503s"))
    report("SERVER_ERROR_503 (2x 503, then success)", *chat("Hi"))


def probe_timeout():
    reset()
    arm({**completion("late answer"), "delay": {"timeUnit": "SECONDS", "value": 150}})
    report("TIMEOUT_NO_FIRST_TOKEN (150 s delay; client gives up at 200 s)", *chat("Hi", timeout=200))


def probe_malformed():
    reset()
    arm({"statusCode": 200, "headers": {"Content-Type": ["application/json"]},
         "body": {"type": "STRING", "string": '{"id":"x","choices":[{"message":{"role":"assist'}})
    report("MALFORMED_JSON_BODY", *chat("Hi"))


def probe_unknown_tool():
    reset()
    arm(completion("final"), last_message_role="tool")
    arm(completion(tool_calls=tool_call("deleteAllOwners", "{}")))
    report("TOOL_UNKNOWN_NAME", *chat("Hi"))


def probe_invalid_args():
    reset()
    arm(completion("I could not add the pet."), last_message_role="tool")
    arm(completion(tool_calls=tool_call("addPetToOwner", json.dumps({"ownerId": "seven", "pet": {"name": "Rex"}}))))
    events = report("TOOL_ARGS_INVALID_TYPES (ownerId='seven')", *chat("Add Rex to owner seven"))
    for e in events:
        msgs = json.loads(e["httpRequest"]["body"].get("string") or json.dumps(e["httpRequest"]["body"]["json"]))["messages"]
        last = msgs[-1]
        if last["role"] == "tool":
            print(f"tool result sent back to LLM: {last['content'][:300]!r}")


def probe_tool_loop():
    # Run 1 (uncontrolled, 60 s) made the SUT unresponsive and flooded MockServer's log.
    # Controlled: 100 ms per LLM response, client gives up at 20 s, then a priority 400 breaks the server-side loop.
    reset()
    arm({**completion(tool_calls=tool_call("listOwners", "{}")), "delay": {"timeUnit": "MILLISECONDS", "value": 100}})
    status, body, elapsed = chat("Hi", timeout=20)
    t_break = time.monotonic()
    http("PUT", MOCK + "/mockserver/expectation",
         {"httpRequest": {"path": COMPLETIONS}, "httpResponse": {"statusCode": 400}, "priority": 100})
    time.sleep(3)
    events = report("TOOL_CALL_LOOP (100 ms/response; client gives up at 20 s; then broken by a 400)", status, body, elapsed)
    stamps = [datetime.strptime(e["timestamp"], "%Y-%m-%d %H:%M:%S.%f") for e in events]
    sizes = [len(e["httpRequest"]["body"].get("string") or json.dumps(e["httpRequest"]["body"].get("json"))) for e in events]
    print(f"calls/s: {len(events) / max((stamps[-1] - stamps[0]).total_seconds(), 0.001):.1f}; "
          f"request body bytes first/last: {sizes[0]}/{sizes[-1]}; loop still running after client gave up: "
          f"{(stamps[-1] - stamps[0]).total_seconds():.1f}s span vs client {elapsed:.1f}s")
    print(f"(break armed {time.monotonic() - t_break:.1f}s ago)")


def probe_happy_with_tool():
    reset()
    arm(completion("Here are the owners."), last_message_role="tool")
    arm(completion(tool_calls=tool_call("listOwners", "{}")))
    events = report("HAPPY_PATH with tool round trip (listOwners)", *chat("List owners"))
    for i, e in enumerate(events):
        msgs = json.loads(e["httpRequest"]["body"].get("string") or json.dumps(e["httpRequest"]["body"]["json"]))["messages"]
        print(f"  request {i + 1}: {len(msgs)} messages, roles={[m['role'] for m in msgs]}")
        if msgs[-1]["role"] == "tool":
            print(f"  tool msg keys={list(msgs[-1])}; assistant tool_calls msg={json.dumps(msgs[-2])[:200]}")


PROBES = {n[len("probe_"):]: f for n, f in globals().items() if n.startswith("probe_")}

if __name__ == "__main__":
    for name in sys.argv[1:] or PROBES:
        PROBES[name]()
