#!/usr/bin/env python3
"""BRAND-3 step 2: the service test pins that are SHA-256 of bytes named in their own javadoc, for a tree (`HEAD` or
the working tree). Usage: python3 -I etags.py <repo> HEAD|WORKTREE"""
import hashlib, re, subprocess, sys
from pathlib import Path
repo, which = Path(sys.argv[1]), sys.argv[2]
OLD = {"HEAD": ("mapping/service/src/test/java/io/flottila/mapping/service/", "mapping/corpus/mappings/order-id-copy.mapping.json"),
       "WORKTREE": ("mapping/service/src/test/java/io/rail8/mapping/service/", "mapping/corpus/mappings/order-id-copy.mapping.json")}
svc, corpus = OLD[which]
def read(p):
    if which == "HEAD":
        return subprocess.run(["git", "show", f"HEAD:{p}"], cwd=repo, capture_output=True, check=True).stdout
    return (repo / p).read_bytes()
h = lambda b: hashlib.sha256(b).hexdigest()
fixtures = read(svc + "Fixtures.java").decode()
def text_block(name):   # a Java text block whose closing delimiter has the content's indentation: strip it, keep the final LF
    m = re.search(r"static final String " + name + r" =\n( *)\"\"\"\n(.*?)\n\1\"\"\";", fixtures, re.S)
    ind = m.group(1)
    lines = m.group(2).split("\n")
    assert all(l.startswith(ind) or l == "" for l in lines)
    return ("\n".join(l[len(ind):].rstrip() for l in lines) + "\n").encode()
mapping = read(corpus)
out = {
  "CORPUS_MAPPING_ETAG  sha256sum " + corpus: h(mapping),
  "UNWIRED_ETAG         that file with \"result\": \"a1\" -> null": h(mapping.replace(b'"result": "a1"', b'"result": null')),
  "NEW_MAPPING_ETAG     text block NEW_MAPPING": h(text_block("NEW_MAPPING")),
  "BARE_MAPPING_ETAG    BARE_MAPPING": h(b'{\n  "formatVersion": 1\n}\n'),
  "DESCRIBED_MAPPING_ETAG text block DESCRIBED_MAPPING": h(text_block("DESCRIBED_MAPPING")),
  "DESCRIBED_NEW_MAPPING_ETAG text block DESCRIBED_NEW_MAPPING": h(text_block("DESCRIBED_NEW_MAPPING")),
  "STUDIO_MAPPING_ETAG  text block STUDIO_MAPPING": h(text_block("STUDIO_MAPPING")),
}
refresh = read(svc + "RefreshResourceTest.java").decode()
pre = re.search(r"<pre>\n(.*?)\n\s*\* </pre>", refresh, re.S).group(1)
canon = "\n".join(re.sub(r"^\s*\* ", "", l) for l in pre.split("\n")) + "\n"
out["RENAMED_FINGERPRINT  the canonical text in its javadoc"] = h(canon.encode())
out["(check) skeleton canonical text: the same with OrderId"] = h(canon.replace("OrderNo", "OrderId").encode())
for k, v in out.items():
    print(f"{which:8} {k:62} {v}")
