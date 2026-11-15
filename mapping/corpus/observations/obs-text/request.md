# obs-text

Notation, what to return and the ESR / CPI naming: `../README.md`. Source of all mappings:
`src-orders.xsd` (root `Orders`). Target of all mappings: `tgt-list.xsd` (root `Out`). The one
request of the text family (spec #70, #76). Every mapping maps F1 -> `Out/All`; S1 is
`Orders/Order/Line/Sku` and S2 `Orders/Order/Line/Qty`, both with context `Orders`, so All has
one value per Line. One run of an input answers every case that names it. Each case id is a
directory under `corpus/cases/`. Characters outside the BMP are in the cases marked decided,
which need no run: PO counts them in UTF-16 units, the catalogue says so.

## Mapping text-concat

| Box | Function | Inputs |
| --- | --- | --- |
| S1 | source | `Orders/Order/Line/Sku` with context `Orders` |
| S2 | source | `Orders/Order/Line/Qty` with context `Orders` |
| F1 | `concat` | in1 = S1, in2 = S2 |

Target wire: F1 -> `Out/All`.

| Case | Input | Question it decides and the assumed reading |
| --- | --- | --- |
| `fn/concat/001` | `concat-001.xml` | concat(Sku, Qty) joins the two strings; an empty side leaves the other as it is, two empty give an empty value. |

## Mapping text-equalsS

| Box | Function | Inputs |
| --- | --- | --- |
| S1 | source | `Orders/Order/Line/Sku` with context `Orders` |
| S2 | source | `Orders/Order/Line/Qty` with context `Orders` |
| F1 | `equalsS` | in1 = S1, in2 = S2 |

Target wire: F1 -> `Out/All`.

| Case | Input | Question it decides and the assumed reading |
| --- | --- | --- |
| `fn/equalsS/001` | `equalsS-001.xml` | equalsS compares case-sensitively and writes true or false: keep/keep is true, keep/Keep false, empty/empty true, a/empty false. |

## Mapping text-startsWith

| Box | Function | Inputs |
| --- | --- | --- |
| S1 | source | `Orders/Order/Line/Sku` with context `Orders` |
| S2 | source | `Orders/Order/Line/Qty` with context `Orders` |
| F1 | `startsWith` | in1 = S1, in2 = S2 |

Target wire: F1 -> `Out/All`.

| Case | Input | Question it decides and the assumed reading |
| --- | --- | --- |
| `fn/startsWith/001` | `startsWith-001.xml` | startsWith(Sku, Qty) is true for a prefix and for an empty prefix, false when the prefix is inside, or longer than the value. |

## Mapping text-endsWith

| Box | Function | Inputs |
| --- | --- | --- |
| S1 | source | `Orders/Order/Line/Sku` with context `Orders` |
| S2 | source | `Orders/Order/Line/Qty` with context `Orders` |
| F1 | `endsWith` | in1 = S1, in2 = S2 |

Target wire: F1 -> `Out/All`.

| Case | Input | Question it decides and the assumed reading |
| --- | --- | --- |
| `fn/endsWith/001` | `endsWith-001.xml` | endsWith(Sku, Qty) is true for a suffix and for an empty suffix, false when the suffix is inside, or longer than the value. |

## Mapping text-replaceString-a-bb

| Box | Function | Inputs |
| --- | --- | --- |
| S1 | source | `Orders/Order/Line/Sku` with context `Orders` |
| F1 | `replaceString{search=a, replacement=bb}` | in1 = S1 |

Target wire: F1 -> `Out/All`.

| Case | Input | Question it decides and the assumed reading |
| --- | --- | --- |
| `fn/replaceString/001` | `replaceString-001.xml` | replaceString{search=a, replacement=bb} replaces every a: banana gives bbbnbbnbb, a value without a is kept, aaa gives six b. |

## Mapping text-replaceString-dollar

| Box | Function | Inputs |
| --- | --- | --- |
| S1 | source | `Orders/Order/Line/Sku` with context `Orders` |
| F1 | `replaceString{search=$, replacement=\$1}` | in1 = S1 |

Target wire: F1 -> `Out/All`.

| Case | Input | Question it decides and the assumed reading |
| --- | --- | --- |
| `fn/replaceString/002` | `replaceString-002.xml` | replaceString{search=$, replacement=\$1} takes both literally, no regular expression and no group reference: a$b$ gives a\$1b\$1. |

## Mapping text-replaceString-empty-search

| Box | Function | Inputs |
| --- | --- | --- |
| S1 | source | `Orders/Order/Line/Sku` with context `Orders` |
| F1 | `replaceString{search=, replacement=-}` | in1 = S1 |

Target wire: F1 -> `Out/All`.

| Case | Input | Question it decides and the assumed reading |
| --- | --- | --- |
| `fn/replaceString/003` | `replaceString-003.xml` | replaceString with an empty search leaves the value as it is (Java would insert the replacement between all characters). |

## Mapping text-replaceString-aa-b

| Box | Function | Inputs |
| --- | --- | --- |
| S1 | source | `Orders/Order/Line/Sku` with context `Orders` |
| F1 | `replaceString{search=aa, replacement=b}` | in1 = S1 |

Target wire: F1 -> `Out/All`.

| Case | Input | Question it decides and the assumed reading |
| --- | --- | --- |
| `fn/replaceString/004` | `replaceString-004.xml` | replaceString{search=aa, replacement=b} replaces matches left to right without overlap: aaa gives ba, aaaa gives bb. |

## Mapping text-toUpperCase

| Box | Function | Inputs |
| --- | --- | --- |
| S1 | source | `Orders/Order/Line/Sku` with context `Orders` |
| F1 | `toUpperCase` | in1 = S1 |

Target wire: F1 -> `Out/All`.

| Case | Input | Question it decides and the assumed reading |
| --- | --- | --- |
| `fn/toUpperCase/001` | `toUpperCase-001.xml` | toUpperCase uses the full Unicode mapping: sharp s gives SS, e-acute gives its capital, an empty value stays empty. |

## Mapping text-toLowerCase

| Box | Function | Inputs |
| --- | --- | --- |
| S1 | source | `Orders/Order/Line/Sku` with context `Orders` |
| F1 | `toLowerCase` | in1 = S1 |

Target wire: F1 -> `Out/All`.

| Case | Input | Question it decides and the assumed reading |
| --- | --- | --- |
| `fn/toLowerCase/001` | `toLowerCase-001.xml` | toLowerCase uses the Unicode mapping: capital e-acute gives e-acute, digits stay. |

## Mapping text-trim

| Box | Function | Inputs |
| --- | --- | --- |
| S1 | source | `Orders/Order/Line/Sku` with context `Orders` |
| F1 | `trim` | in1 = S1 |

Target wire: F1 -> `Out/All`.

| Case | Input | Question it decides and the assumed reading |
| --- | --- | --- |
| `fn/trim/001` | `trim-001.xml` | trim leaves out space, tab, line feed and return at both ends and nothing inside: '  a b  ' gives a b, an all-space value gives an empty value; a no-break space is not trimmed. |
