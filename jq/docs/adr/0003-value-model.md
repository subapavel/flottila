# Value model: kind-first sealed JqValue, views and built values

Status: accepted (2026-11-10, owner, issue #5 "What are the value model and the tape layout?"); amended by ADR-0022 (issue #8: owner on built strings)

Every jq value is a `sealed interface JqValue`, split first by kind and then by family. Builtins
and hosts first ask "is it a string?", which is then one type test. Whether it is a view into a
document or a value the filter built is the second question, and it is answered by a cross-cutting
`sealed interface View`. The tree compiles and runs on JDK 25.0.5 (checked by the grilling adviser).

- **Kinds**:
  - `JqNull` and `JqBool` are enums, with no views.
  - `JqNumber` = `NumberView` | `LiteralNumber` | `DoubleNumber`.
  - `JqString` = `StringView` | `BuiltString`.
  - `JqArray` = `ArrayView` | `BuiltArray`.
  - `JqObject` = `ObjectView` | `BuiltObject`.
- **Views** are records of the document and a tape index. Generated code mostly makes no view at
  all: it walks the tape with an `int` cursor.
- **Built values**:
  - Strings are UTF-8 slices `(byte[], offset, length)`.
  - Arithmetic results are `double`, as in jq.
  - Arrays are `Object[]` plus a length (founding §14 keeps `query-core` on `Object[]`).
  - Objects have **two forms**: a shape, i.e. a key list shared by every object built at one
    construction site, in insertion order, searched linearly up to 8 keys; and a **growable form**
    for keys made at run time, with its own key list, an index built on first lookup, and keys
    appended in place while one operation owns it. Without the growable form, `INDEX`,
    `from_entries`, `with_entries` and `add` would copy the key list on every insert and go
    quadratic. jq-ref builds a 400 000-key `INDEX` in 1.2 s.
- **Ownership**: built arrays, objects and strings carry an owner field (strings added by
  ADR-0022). When it may be used is ADR-0022's.

## Considered options

- **Family-first** (view | built, then kind): the kind needs a second test or a tape read.
- **Views for null and booleans**: no position is ever needed.
- **A long-integer number class**: jq arithmetic is `double`, and integers up to 2^53 are exact
  in it.
- **Shapes only, or a transition cache**: one wide object built key by key stays quadratic.
