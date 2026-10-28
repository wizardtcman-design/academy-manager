#!/usr/bin/env python3
"""Pretty-printer for `wrangler tail --format json` streams.

wrangler emits PRETTY multi-line JSON objects (not JSON lines). This reader
brace-matches top-level objects and prints one compact line per invocation:
  <timestamp> <METHOD> <pathname> outcome=<outcome>
plus indented logs/exceptions.

PRIVACY: query strings are DROPPED (sign/pay/one-time tokens must never reach
CI logs). Cloudflare's tail itself already redacts Authorization/Cookie
headers, so bearer keys never appear in the payload.
"""
import sys
import json


def iter_objects(text):
    depth = 0
    cur = None
    in_str = False
    esc = False
    for i, ch in enumerate(text):
        if in_str:
            if esc:
                esc = False
            elif ch == '\\':
                esc = True
            elif ch == '"':
                in_str = False
            continue
        if ch == '"':
            in_str = True
        elif ch == '{':
            if depth == 0:
                cur = i
            depth += 1
        elif ch == '}':
            if depth > 0:
                depth -= 1
                if depth == 0 and cur is not None:
                    yield text[cur:i + 1]
                    cur = None


for e in iter_objects(sys.stdin.read()):
    try:
        obj = json.loads(e)
    except Exception:
        continue
    ev = obj.get('event') or {}
    req = ev.get('request') or {}
    url = req.get('url', '')
    path = url.split('?')[0] if url else ''
    method = req.get('method', '')
    if not path:
        path = f"cron({ev.get('cron', '?')})" if ev.get('cron') else '?'
        method = 'CRON'
    outcome = obj.get('outcome', '?')
    ts = obj.get('eventTimestamp', '')
    logs = ' | '.join(
        ' '.join(str(x) for x in l.get('message', []))
        if isinstance(l.get('message'), list) else str(l.get('message', ''))
        for l in (obj.get('logs') or [])
    )
    excs = ' | '.join(
        f"{x.get('name', 'Error')}: {x.get('message', '')}"
        for x in (obj.get('exceptions') or [])
    )
    print(f"{ts} {method} {path} outcome={outcome}")
    if logs:
        print(f"   logs: {logs[:600]}")
    if excs:
        print(f"   exceptions: {excs[:600]}")
