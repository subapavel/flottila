# reviewer-spec memory

Durable facts only (AGENTS.md, Memory). No ticket state. Under 120 lines.

## Carried over from r8-mapping (2026-11-10; observed there, re-verify on first use here)

- A ticket's scope can contradict the spec's Implementation Decisions: compare them.
- A case built with a stand-in for a function that did not exist yet keeps it after that
  function lands: check old cases when a function arrives.
- A general "accepted" clause in an ADR can hide exceptions the code keeps: grep the code for them.
- An implementer's "decision" can swap a component the spec names for a reason the spec never
  imposed; check it against the spec and the licence list.
- (#34) Probe a design's Stub token list with the token followed by a valid one (`..a`), not alone.
- (#36) A design departure in an algorithm is a carry, not a finding, when a property test compares it with an independent oracle that follows the ADR's rule. A commit claiming architect approval: look for it on the issue.
- (#62) A ticket body's count can be the spec story's total: take the ticket's share from the census output and the architect's census comment on the spec issue.
- (#62) DiffRunner compares no stderr when an expected line is a syntax error; recovery-message text is checked only by unit tests and jqtest's last-block rule.
- (#63) An ADR's list of tape walks can omit a walk the ticket adds (object `.[]`'s duplicate check): grep the views' loops in new paths.
- (#63) Binding decisions sometimes live only in commits or the brief; ask that the evidence comment records them.
- (#64) A constant measured after red leaves only relative tests: check an absolute pin was added after the measurement commit. Every "moves to #N" in an approval needs a hand-over comment on #N.
- (#64) Compare the resolver's new built-ins against the spec's natives table (`empty` was #72's).

- (#65) Count the ids in a .cases file against every "N cases" in hand-over comments. Check ADR tick-rate prose against the pinned test deltas.
- (#66) For a case left out "for #N", check that #N has a hand-over comment and that the id is in its require/N.txt; the .cases file may not exist on #N's branch yet.
- (#66) If a unit test runs the same filter as a differential case left out of the require file, that case is already green: ask why it was left out.
- (#67) A test cited as the witness of an error order may produce only one error (one name declared, the other not). Check that it has two items to order.
- (#69) Count the ids in a require file against the evidence's "N of N". When an issue body gains a blocker edge, check that census.java's BLOCKED_BY mirrors it.
- (#70) A new global bound can stub other tickets' constructs (operator chains, wide objects); grep what it counts and compare DEVIATIONS widths with the finding, not the ruling's prose.
- (#71) An acceptance line in the ticket body (a bench/RESULTS.md measurement) can be missing from every approval comment: check the body's checklist, not only the rulings.
- (F-46) A ratcheted compile-mode PASS can be vacuous (both runs fail to compile identically). When a ticket changes an error into a Stub, expect compile-mode ratchet fallout the ticket gate does not see (until #89).
