#!/usr/bin/env python3
"""BRAND-3 step 2: the new structure fingerprints, derived outside the engine.

Input: old/<name>.txt, today's canonical text of each structure (the bytes SHA-256 runs over in
Structure.fingerprint, ADR-0009). Each is first checked against today's pin by its own SHA-256.
The rename changes only namespace URIs (`urn:flottila:` -> `urn:rail8:`), which enter the text
verbatim inside `Q{...}`; the text is "root" then each type in id order (TreeMap, Java String
order = code-unit order, all ASCII here) with its fields in field order. So the new text is the
old one with the URIs substituted and the type blocks re-sorted by their new id.
Usage: python3 -I derive.py <dir holding old/>
"""
import hashlib, sys
from pathlib import Path

OLD_PINS = {
    "skeleton": "df1714b8b2e4b09e326626a2ea72780bf6298a0ca733c69f5586ea657e4bd90d",
    "derivation-order": "7fa870df17665f949da9986b2036fc81a4299fe38c920afdd24b20ac29b17284",
    "derivation-receipt": "f7b3d1697015e4f93e73caa19b269e7cbd44f8b19146ba6b50c49bdb7bfa5c84",
    "gate-po": "c7a83f28ce9966a4cb158317dc7a29a6e89c59ec6ed129bbd3317aa50a7293f3",
    "gate-invoice": "58de05eec9c995263cd630786bcdecf224806c5e23cdecad4681bea25648ea4a",
    "wsdl-input": "510d96ec830e4ad8aa2e477a6fe73640f58587cc84ce10ec68ce666ca65edd16",
    "wsdl-output": "facdda4e20e0833ad276202aa9f4c563264b3b071e5c12825ba01082ba1cbc85",
    "bench-contexts": "19ce6273eb060c4591317fddea6219e5847e3b87032d82f34c72376bd5b3d978",
    "bench-orders": "5fbe142e70d99a399295c6702dd7d68a32d70ce1e01d4bf10ec5d7ae19a6da98",
    "bench-wide": "f86c88062497643172ac8aec2d2d94d5b55826a60df1f2e1c291fa61a6e49f72",
    "evil": "b1e7aba69ac4f5572ccadcb28bc162f0cab8d7be4cca3a487fff96340387ad00",
    "nil": "88f31d654678c63609b0643c7175af239bb68f36962ffaee980d17722f045ef2",
}

def renamed(text: str) -> str:
    lines = text.split("\n")
    assert lines[-1] == "" and lines[0].startswith("root ")
    root, blocks = lines[0], []
    for line in lines[1:-1]:
        if line.startswith("type "):
            blocks.append([line])
        else:
            assert line.startswith("field ") and blocks
            blocks[-1].append(line)
    sub = lambda s: s.replace("urn:flottila:", "urn:rail8:")
    old_ids = [b[0][5:] for b in blocks]
    assert old_ids == sorted(old_ids), "today's text is in id order"
    blocks = sorted(([sub(l) for l in b] for b in blocks), key=lambda b: b[0][5:])
    out = "\n".join([sub(root)] + [l for b in blocks for l in b]) + "\n"
    assert "flottila" not in out.lower()
    return out

base = Path(sys.argv[1])
(base / "new").mkdir(exist_ok=True)
for name, pin in OLD_PINS.items():
    old = (base / "old" / f"{name}.txt").read_bytes()
    today = hashlib.sha256(old).hexdigest()
    assert today == pin, f"{name}: today's text gives {today}, not the pin {pin}"
    new = renamed(old.decode("utf-8"))
    (base / "new" / f"{name}.txt").write_text(new, encoding="utf-8")
    print(f"{name:20} old sha256:{pin} (reproduced)  new sha256:{hashlib.sha256(new.encode()).hexdigest()}")
