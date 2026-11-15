#!/usr/bin/env python3
"""FIX-254: every recorded event of every thread inside one FlowWorker.failed call.
usage: window.py <recording.jfr> <index of the failed() call, from 0> [before-us] [after-us]"""
import json, subprocess, sys

TYPES = 'jdk.GCPhasePause,jdk.SafepointEnd,jdk.MethodTrace,jdk.FileWrite,jdk.FileRead,jdk.JavaMonitorEnter,jdk.ClassLoad,jdk.VirtualThreadPinned,jdk.ExecutionSample,jdk.NativeMethodSample,jdk.GarbageCollection,jdk.SafepointBegin,jdk.VirtualThreadStart,jdk.VirtualThreadEnd'

def ns(s):
    hms, frac = s[11:-1].split('.'); h, m, sec = hms.split(':')
    return (int(h) * 3600 + int(m) * 60 + int(sec)) * 10**9 + int(frac.ljust(9, '0'))

def dur(v):
    s = v.get('duration')
    return int(round(float(s[2:-1]) * 1e9)) if s else 0

def frames(v, n=4):
    fr = ((v.get('stackTrace') or {}).get('frames') or [])[:n]
    return ' < '.join(f['method']['type']['name'].split('/')[-1] + '.' + f['method']['name'] for f in fr)

raw = subprocess.run(['jfr', 'print', '--json', '--stack-depth', '12', '--events', TYPES, sys.argv[1]], capture_output=True, text=True).stdout
ev = []
for x in json.loads(raw)['recording']['events']:
    v = x['values']; ty = x['type'][4:]
    th = (v.get('eventThread') or v.get('sampledThread') or {}).get('javaName') or '-'
    if ty == 'MethodTrace':
        m = v['method']; what = m['type']['name'].split('/')[-1] + '.' + m['name']
    elif ty in ('FileWrite', 'FileRead'):
        what = '%s %s bytes %s' % (ty, v.get('bytesWritten', v.get('bytesRead')), (v.get('path') or '')[-40:])
    elif ty == 'JavaMonitorEnter':
        what = 'MONITOR %s held by %s | %s' % ((v.get('monitorClass') or {}).get('name'), (v.get('previousOwner') or {}).get('javaName'), frames(v))
    elif ty == 'ClassLoad':
        what = 'ClassLoad ' + ((v.get('loadedClass') or {}).get('name') or '?')
    elif ty == 'VirtualThreadPinned':
        what = 'PINNED %s / %s | %s' % (v.get('blockingOperation'), v.get('pinnedReason'), frames(v, 6))
    elif ty in ('ExecutionSample', 'NativeMethodSample'):
        what = '%s %s | %s' % ('SAMPLE' if ty == 'ExecutionSample' else 'NATIVE', v.get('state'), frames(v, 7))
    else:
        what = ty + ' ' + str(v.get('name') or v.get('cause') or '')
    ev.append((ns(v['startTime']), dur(v), th, what, ty))
ev.sort()
if len(sys.argv) > 5:
    import datetime
    for line in open(sys.argv[5]):
        epoch, m, q = line.split()
        epoch = int(epoch)
        day = epoch % (86400 * 10**9)   # ns of the UTC day, as the recording's times
        ev.append((day, 0, 'count-agent', 'COUNTERS mounted=%s queued=%s' % (m, q), 'Count'))
    ev.sort()
fails = [e for e in ev if e[3] == 'FlowWorker.failed']
print('FlowWorker.failed calls:', [(e[2], e[1] // 1000) for e in fails])
f = fails[int(sys.argv[2])]
before = int(sys.argv[3]) * 1000 if len(sys.argv) > 3 else 300_000
after = int(sys.argv[4]) * 1000 if len(sys.argv) > 4 else 300_000
a, b = f[0], f[0] + f[1]
loads = reads = 0
for e in ev:
    if not (a - before <= e[0] <= b + after):
        continue
    if e[4] == 'ClassLoad' and a <= e[0] <= b:
        loads += 1
        continue
    if e[4] == 'FileRead' and a <= e[0] <= b:
        reads += 1
        continue
    if e[3].startswith('MockComponent$Drain') or e[3].startswith('StackTraceElement.of'):
        continue
    print('  %+6d us %6d us  %-26s %s' % ((e[0] - a) // 1000, e[1] // 1000, e[2][:26], e[3][:190]))
print('inside the call: %d class loads, %d file reads (not listed)' % (loads, reads))
