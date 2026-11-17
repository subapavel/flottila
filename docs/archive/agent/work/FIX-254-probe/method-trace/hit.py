#!/usr/bin/env python3
"""exit 0 and print the index when the clock thread found a quiet sample inside a FlowWorker.failed call. usage: hit.py <recording.jfr>"""
import json, subprocess, sys
def ns(s):
    hms, frac = s[11:-1].split('.'); h, m, sec = hms.split(':')
    return (int(h) * 3600 + int(m) * 60 + int(sec)) * 10**9 + int(frac.ljust(9, '0'))
raw = subprocess.run(['jfr', 'print', '--json', '--events', 'jdk.MethodTrace', sys.argv[1]], capture_output=True, text=True).stdout
fails, quiet = [], []
for x in json.loads(raw)['recording']['events']:
    v = x['values']; m = v['method']; name = m['type']['name'].split('/')[-1] + '.' + m['name']
    t = ns(v['startTime'])
    if name == 'FlowWorker.failed':
        fails.append((t, t + int(round(float(v['duration'][2:-1]) * 1e9))))
    elif name == 'VirtualTime.flowTime':
        quiet.append(t)
fails.sort()
hits = [(i, (q - a) // 1000) for i, (a, b) in enumerate(fails) for q in quiet if a < q < b]
print('failed() calls us:', [(b - a) // 1000 for a, b in fails], 'quiet inside (call, +us):', hits)
sys.exit(0 if hits else 1)
