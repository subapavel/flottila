# Read accessors at the API seam: edges, staleness, empty input

Status: accepted (2026-11-11, architect, on the designer's draft for #32 "S1.10 Read to values"); amends ADR-0016 (accessors), ADR-0004 (staleness)

- **Edges.** `JqArray.get(i)` outside [0, `length()`) throws `IndexOutOfBoundsException`.
  `JqObject.get(key)` returns Java `null` for a missing key, so a host can tell "absent" from a
  present `null` (`JqNull.NULL`). jq's `.[i]` and `.a` are filter operations and keep jq's own rules;
  they are not these accessors.
- **Staleness.** A Workspace `read` makes that Workspace's previous document stale as it starts,
  including when the read then fails, so after a failed read no document is current. `close()` is
  idempotent and drops only its own document. Every view accessor and `root()` on a stale document
  throws `JqTermination` with cause `STALE_VIEW` and exit code 5 (ADR-0017's code for a failed
  evaluation). The check is one field load and one null test per accessor call; an accessor that
  calls host code (`forEach`) checks again after every call, because the host may read into the same
  Workspace inside it (#32 review).
- **Empty input.** Empty or whitespace-only bytes throw `JqSyntaxException`: `Workspace.read` reads
  exactly one document. The CLI's rule that an empty stdin runs nothing belongs to the input-stream
  reader (#38), not to `read`.
- **Equality.** `JqValue.equals` on a view is record equality (same document, same tape index), never
  jq equality. jq equality is `JvEqual` in the oracle and the compare operation in the engine.
- **Views are records** `(Document doc, int index)` in `….internal.value`, as ADR-0003 says. Views
  carry no `owner` (ADR-0022 puts it on built arrays, objects and strings).
- **Depth.** Until spec 2 wires `Limits` into the Workspace, the reader enforces the default
  `nestingDepth` of 10 000 as a constant (ADR-0015:46-51), with jq's text "Exceeds depth limit for
  parsing at line L, column C". It never accepts a document jq-ref rejects for depth.

## Considered options

- jq's `null` for an index out of range and for a missing key: hides host bugs and cannot tell a
  present `null` from an absent key.
- A generation number per view: rejected by ADR-0004.
- Views holding the exported `JsonDoc`: needs public accessors on an exported class, or a static
  bridge.
- Enforcing no depth limit until spec 2: jqj would accept documents jq-ref rejects.
