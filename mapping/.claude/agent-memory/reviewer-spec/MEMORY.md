# reviewer-spec memory

Durable facts only (AGENTS.md, Memory).

- A single-value binder that short-circuits an input (`if`, `and`, `or`) disagrees with the
  export, which evaluates every argument (F-22). Check every binder, not only the one named.
  Seen: #77, found only at the spec-3 review, `and`/`or` only in its second round.
- A ticket's family or scope can contradict the spec's Implementation Decisions: compare them.
  Seen: #83 put fixValues in `constant`; #70 said `conversions`.
- A corpus case built with a stand-in for a function that did not exist yet keeps it after that
  function lands. Seen: target/max/004 used replaceValue for constant until the spec-3 review.
- A general "accepted" clause in an ADR ("attributes in other namespaces") can hide exceptions the
  code keeps: grep the code for them. Seen: spec #89 review, `vc:` attributes other than
  `minVersion` stay refused.
- An implementer's "decision" can swap a component the spec names (Table → DataList) for a reason the
  spec never imposed; check it against the licence list. Seen: #141.
- `stepName` where a spec says "local path" collapses the path to its leaf; grep `stepName(`. Seen: #138.
