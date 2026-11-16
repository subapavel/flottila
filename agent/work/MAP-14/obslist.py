#!/usr/bin/env python3
"""Link the entries of one run in each observation log and list the latest state (MAP-14).
A run is a mapping on an input. Every entry names both. One run can carry two mapping names - the name it was drawn
under on PO and the one on Cloud Integration: an alias is a (mapping, input) whose entries carry no `po75` value and
which shares its case and input with exactly one (mapping, input) that does. A case alone does not identify a run:
several mappings, or several inputs, can test one case."""
import json, glob, os, collections
ROOT = "/home/user/flottila/mapping/corpus/observations/"
def groups(meta):
    runs = meta.get("runs", [])
    by = collections.OrderedDict()
    for i, r in enumerate(runs):
        assert r.get("mapping") and r.get("input"), (i, r)
        by.setdefault((r["mapping"], r["input"]), []).append(i)
    def has_po(k): return any(runs[i].get("po75") is not None for i in by[k])
    def cases(k): return {runs[i].get("case") for i in by[k] if runs[i].get("case")}
    alias = {}
    for k in by:
        if has_po(k): continue
        cand = [j for j in by if j != k and j[1] == k[1] and has_po(j) and cases(j) & cases(k)]
        if len(cand) == 1: alias[k] = cand[0]
    out = collections.OrderedDict()
    for k, idx in by.items():
        out.setdefault(alias.get(k, k), []).extend(idx)
    return runs, [sorted(v) for v in out.values()], alias
def latest(runs, idx, field):
    for i in reversed(idx):
        if runs[i].get(field) is not None: return runs[i][field], i
    return None, None
rows, aliases, sets_all = [], [], []
for d in sorted(glob.glob(ROOT + "obs-*/meta.json")):
    s = os.path.basename(os.path.dirname(d)); sets_all.append(s)
    runs, gs, al = groups(json.load(open(d, encoding="utf-8")))
    aliases += [(s, k, v) for k, v in al.items()]
    for idx in gs:
        cpi, ci = latest(runs, idx, "cpi"); po, pi = latest(runs, idx, "po75")
        rows.append(dict(set=s, idx=idx, cpi=cpi, ci=ci, po=po, pi=pi, runs=runs))
if __name__ == "__main__":
    print("logs:", len(sets_all), "sets with runs:", len({r["set"] for r in rows}), "runs:", len(rows), "aliases:", len(aliases))
    for a in aliases: print("  alias", a)
    c = collections.Counter((str(r["po"]), str(r["cpi"])) for r in rows)
    for k, v in sorted(c.items(), key=lambda kv: -kv[1]): print(v, k)
    for r in rows:
        if r["po"] is None or r["cpi"] is None or "BLOCKED" in str(r["cpi"]) + str(r["po"]):
            i = r["idx"][-1]; e = r["runs"][i]
            print("  odd:", r["set"], e.get("mapping"), e.get("input"), e.get("case"), "po=", r["po"], "cpi=", r["cpi"])
