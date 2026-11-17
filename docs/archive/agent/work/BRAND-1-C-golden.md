# BRAND-1 commit C — golden constants recomputed outside the code under test

Script: `agent/work/BRAND-1-C-golden.py` (stdlib `hmac`/`hashlib`, HKDF per RFC 5869 written out, CRC32C written out).
Commands: `python3 -I agent/work/BRAND-1-C-golden.py flottila FJS1` (old, output BRAND-1-C-golden-old.txt) and
`python3 -I agent/work/BRAND-1-C-golden.py rail8 RJS1` (new, output BRAND-1-C-golden-new.txt).
Old run: every value equals the literal in the test at HEAD c017e9c (reproduced: yes, 16 of 16).
MASTER_FP_K1/K2 = HMAC-SHA256(master, INFO + " fingerprint")[0..8] (the test's comment at :65): they DO depend on the
label, so they change; the MASTER_*_PROOF frames likewise. The two RFC 5869 vectors carry no label: unchanged.
Cross-check: the relabeled python3 one-liners in PeerKnownAnswerTest's comments (:50, :57, :65) print the new SUB_K1,
FP_K1 and MASTER_FP_K1.

| constant | file:line | old (HEAD, reproduced) | new |
| --- | --- | --- | --- |
| SUB_K1 | platform/runtime/src/test/java/io/rail8/runtime/journal/peer/PeerKnownAnswerTest.java:53 | `29270d2bedfc61712f9fd2011df34155d0e57e067591f705e25c765d56a8b969` | `4bf3e05f4f8dbce605562950fcb641f50ffd7bd6c2c2fe34deb6c21885658cea` |
| SUB_K2 | platform/runtime/src/test/java/io/rail8/runtime/journal/peer/PeerKnownAnswerTest.java:55 | `373384c9a9bf6c4c3cabf482d24052a756c7b31e19d4f4c5683a6b1a5b96b953` | `678a89202eac406b5ab15a3a8fb58b5265b9437b9983463897809f566a2a0352` |
| FP_K1 | platform/runtime/src/test/java/io/rail8/runtime/journal/peer/PeerKnownAnswerTest.java:60 | `921381e52ce9d750` | `923c5a41eae666b1` |
| FP_K2 | platform/runtime/src/test/java/io/rail8/runtime/journal/peer/PeerKnownAnswerTest.java:62 | `aa0aa5b0de650e76` | `7205e54e940fd589` |
| MASTER_FP_K1 | platform/runtime/src/test/java/io/rail8/runtime/journal/peer/PeerKnownAnswerTest.java:67 | `0a2082a6b58e11ea` | `5d03b11cd5f2c09a` |
| MASTER_FP_K2 | platform/runtime/src/test/java/io/rail8/runtime/journal/peer/PeerKnownAnswerTest.java:69 | `99a7d37570fe2dfc` | `c8879eb8a2a3ee96` |
| DIALER_PROOF | platform/runtime/src/test/java/io/rail8/runtime/journal/peer/PeerKnownAnswerTest.java:213-214 | `0000003902000000000000000154cd8faac02fec83412db46d4ce3dbb3e172e5222b6d95a9323ffb9d1ff1aeb57b0061c6c1681546864de81b39dfcb23` | `00000039020000000000000001e26a0f2aa5e3c3247ce9ca463785499802b3e855644c642fb09fa83baa212c26f0b87a158c1737a8b742490bfa67d21c` |
| LISTENER_PROOF | platform/runtime/src/test/java/io/rail8/runtime/journal/peer/PeerKnownAnswerTest.java:215-216 | `000000390200000000000000017ff9e01b14a6c639384d69a3413659a39f9fec853847d12489333a257810e905379ee1237dbc381a9bb523d80c5114c5` | `00000039020000000000000001ce8f832f72847320621ba216f63b8afefc899cfa756ddfc874e029b31b2d6f7171475c4b35bdcba2a583ca391adf16c3` |
| MASTER_DIALER_PROOF | platform/runtime/src/test/java/io/rail8/runtime/journal/peer/PeerKnownAnswerTest.java:217-218 | `000000390200000000000000015f2257d558968dbd5d559e69ea56adcfe24bd17a0a4b75e7ce83356f5c9dd202eb95677b6c3be6fed605e5425e9265ee` | `000000390200000000000000014d280a2b385aae7e9523e7c47bb9eed040da7cdeb3525844e8df6e38f7524690656b1fe08dd6306a66d36c9e185a6ac7` |
| MASTER_LISTENER_PROOF | platform/runtime/src/test/java/io/rail8/runtime/journal/peer/PeerKnownAnswerTest.java:219-220 | `000000390200000000000000017088689f0c1bb7293af99f0888deb8df707635c7accf9d25b16f751f47e7f115aa17533675d435f5def6f0b5fca9469f` | `000000390200000000000000012ad8f08048454b0ddf9d5eb5b76e148aa71de9917600fa8b58e637b04218451f4bbfeda6048b4c3886a409a80751e589` |
| RFC5869_TC3 (no label) | platform/runtime/src/test/java/io/rail8/runtime/journal/peer/PeerKnownAnswerTest.java:157, :234 | `8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8` | `8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8` (unchanged) |
| RFC5869_TC1 (no label) | platform/runtime/src/test/java/io/rail8/runtime/journal/peer/PeerKnownAnswerTest.java:168 | `3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865` | `3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865` (unchanged) |
| HEADER | platform/runtime/src/test/java/io/rail8/runtime/journal/peer/ReplFramesTest.java:45 | `464a5331010b0b0b0b00004000800000000000000b00066e6f64652d6200000000000000070000019b76daab52f3e308dc` | `524a5331010b0b0b0b00004000800000000000000b00066e6f64652d6200000000000000070000019b76daab52b92491df` |
| OPEN | platform/runtime/src/test/java/io/rail8/runtime/journal/peer/ReplFramesTest.java:46 | `00066e6f64652d620b0b0b0b00004000800000000000000b00000000000000070000000000100000464a5331010b0b0b0b00004000800000000000000b00066e6f64652d6200000000000000070000019b76daab52f3e308dc` | `00066e6f64652d620b0b0b0b00004000800000000000000b00000000000000070000000000100000524a5331010b0b0b0b00004000800000000000000b00066e6f64652d6200000000000000070000019b76daab52b92491df` |
| FILE_CRC | platform/runtime/src/test/java/io/rail8/runtime/journal/peer/ReplFramesTest.java:42 | `0xad255015` | `0x5243a293` |
| SEAL | platform/runtime/src/test/java/io/rail8/runtime/journal/peer/ReplFramesTest.java:48 | `00066e6f64652d620b0b0b0b00004000800000000000000b00000000000000070000000000000039ad255015` | `00066e6f64652d620b0b0b0b00004000800000000000000b000000000000000700000000000000395243a293` |
