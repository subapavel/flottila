#!/usr/bin/env python3
"""Flottila load client: POST a file to /in, N total requests over C keep-alive connections.

Usage: python bench.py [--n 20000] [--c 64] [--file ../sample-invoice.xml]
                       [--host localhost] [--port 8080] [--path /in] [--timeout 120]

Prints req/s and latency percentiles for 2xx responses, plus a count per non-2xx status /
error type. Pure stdlib (http.client + threads) so it runs anywhere; on this box it saturates
around 8-10k req/s, so treat results near that as a client ceiling, not a server limit.
"""
import argparse
import http.client
import threading
import time


def worker(args, body, n, out):
    times, errors = [], {}
    conn = http.client.HTTPConnection(args.host, args.port, timeout=args.timeout)
    for _ in range(n):
        t0 = time.perf_counter()
        try:
            conn.request("POST", args.path, body, {"Content-Type": "application/xml"})
            resp = conn.getresponse()
            resp.read()
            dt = time.perf_counter() - t0
            if 200 <= resp.status < 300:
                times.append(dt)
            else:
                errors[resp.status] = errors.get(resp.status, 0) + 1
        except Exception as e:
            errors[type(e).__name__] = errors.get(type(e).__name__, 0) + 1
            try:
                conn.close()
            except Exception:
                pass
            conn = http.client.HTTPConnection(args.host, args.port, timeout=args.timeout)
    out.append((times, errors))


def pct(sorted_times, p):
    if not sorted_times:
        return float("nan")
    return sorted_times[min(len(sorted_times) - 1, int(p / 100 * len(sorted_times)))]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--n", type=int, default=20000)
    ap.add_argument("--c", type=int, default=64)
    ap.add_argument("--file", default="../sample-invoice.xml")
    ap.add_argument("--host", default="localhost")
    ap.add_argument("--port", type=int, default=8080)
    ap.add_argument("--path", default="/in")
    ap.add_argument("--timeout", type=float, default=120.0)
    args = ap.parse_args()

    body = open(args.file, "rb").read()
    per = [args.n // args.c + (1 if i < args.n % args.c else 0) for i in range(args.c)]
    out = []
    threads = [threading.Thread(target=worker, args=(args, body, per[i], out)) for i in range(args.c)]
    t0 = time.perf_counter()
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    wall = time.perf_counter() - t0

    times = sorted(t for ts, _ in out for t in ts)
    errors = {}
    for _, es in out:
        for k, v in es.items():
            errors[k] = errors.get(k, 0) + v

    ok = len(times)
    print(f"requests={args.n} concurrency={args.c} wall={wall:.2f}s")
    print(f"ok={ok} req/s={ok / wall:.0f}")
    if times:
        print(f"p50={pct(times, 50) * 1000:.1f}ms p95={pct(times, 95) * 1000:.1f}ms "
              f"p99={pct(times, 99) * 1000:.1f}ms max={times[-1] * 1000:.1f}ms")
    if errors:
        print("errors:", dict(sorted(errors.items(), key=lambda kv: str(kv[0]))))


if __name__ == "__main__":
    main()
