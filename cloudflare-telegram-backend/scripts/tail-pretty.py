#!/usr/bin/env python3
"""Pretty-printer for `wrangler tail --format json` streams.

Reads JSON lines on stdin, prints one compact line per invocation:
  <timestamp> <METHOD> <pathname> outcome=<outcome>
plus indented logs/exceptions. Query strings are DROPPED (sign/pay/one-time
tokens must never appear in CI logs). No values are redacted beyond that
because wrangler tail payloads never include secrets; log lines produced by
the worker are technical error messages only.
"""
import sys
import json

for line in sys.stdin:
    line = line.strip()
    if not line:
        continue
    if not line.startswith('{'):
        print(line)
        continue
    try:
        e = json.loads(line)
    except Exception:
        print(line[:200])
        continue
    req = (e.get('event') or {}).get('request') or {}
    url = req.get('url', '')
    path = url.split('?')[0] if url else '(non-http)'
    method = req.get('method', '?')
    outcome = e.get('outcome', '?')
    ts = e.get('eventTimestamp') or ''
    logs = ' | '.join(
        ' '.join(str(x) for x in l.get('message', []))
        if isinstance(l.get('message'), list) else str(l.get('message', ''))
        for l in (e.get('logs') or [])
    )
    excs = ' | '.join(
        f"{x.get('name', 'Error')}: {x.get('message', '')}"
        for x in (e.get('exceptions') or [])
    )
    print(f"{ts} {method} {path} outcome={outcome}")
    if logs:
        print('   logs:', logs[:600])
    if excs:
        print('   exceptions:', excs[:600])
