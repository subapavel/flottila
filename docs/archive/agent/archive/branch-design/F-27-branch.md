# F-27 — §5 refuses the exception as a property and §6 puts nothing in its place

Date: 2026-11-14 · Task: CONSIDER-11 · ARCHITECTURE section: §6 (version 3.4)

- **Assumption in the document:** §5 "Not carried over from Camel" rejects the exception as a
  property, and §6 describes a pipeline in which a step either succeeds or fails. The document
  reads as if the built-in failure paths were the whole answer: retry (D-045), then sync → the
  caller with the status `Fault.kind()` maps to and the exception text as `text/plain`,
  at-least-once → the DLQ, at-most-once → log and drop (D-049). Nowhere does either section say
  what a flow author does when none of those three is the right answer.
- **How it fails in practice:** for an integration platform the three built-in paths are not a
  superset of what an integration needs, they are the floor under it. A partner that speaks SOAP
  expects a SOAP Fault, not `text/plain` with a Java exception message in it; a partner that speaks
  JSON expects its own error shape and its own status, not the one `Fault.Kind` happens to map to;
  a failed booking usually has to be compensated, and a failed batch usually has to be announced
  somewhere a human looks. None of this can be expressed today. The author's only escape is to make
  every step succeed and carry the error by hand through the rest of the pipeline — which is the
  exception-as-a-property pattern §5 refused, rebuilt badly in user space. So the refusal in §5 is
  right and the gap it leaves is real: the document removed a bad mechanism without supplying a
  good one. The owner called this "a hole in the architecture" (2026-11-10) and that is accurate.
- **Severity:** major — not a defect in the code, which does exactly what the document says, but a
  missing capability that every competitor has (SAP CPI's Exception Subprocess, Camel's
  `onException`) and that a CPI migration cannot be done without. It is the reason CONSIDER-11 was
  given priority over the P2 work in flight.
- **Evidence:** observed, not remembered — `Fault.java:20` has exactly four kinds (`MALFORMED`,
  `INVALID`, `DOWNSTREAM`, `INTERNAL`), with no way to attach an author's reply to any of them;
  `flow.schema.json` has no `onError` and no `errorHandlers` (grep, 2026-11-14), so the capability
  is absent from the config surface and not merely undocumented; `Replay.java:101` matches on
  `Snapshot.Outcome.OK`, and `Snapshot.Outcome` before D-102 was `OK | FILTERED | FAILED`, so the
  timeline had no way to say "this failed and was dealt with" either.
- **Change made:** no code change in this task. §6 gains a new subsection, **User-defined error
  handling**, which specifies the mechanism; D-102 fixes the durable marker (`HANDLED`, the
  `onError:<handler>` row, the at-least-once write-before-handled rule); D-103 fixes the one
  failure a handler never sees (an interrupt). The implementation rows follow in `agent/BACKLOG.md`.
- **ARCHITECTURE.md edit:** changelog row for 2026-11-14, version stays 3.4. The subsection adds a
  capability the body did not describe; it contradicts nothing already written, which is why the
  version does not move.
- **Correction inside the design itself:** the drafted §6 text asserted that "an interrupt is
  neither retried nor handled (D-098)". D-098 does not say that — it covers parking, the Delivery
  sync path, and Retry suppressing earlier failures on attempt ≥ 2. The rule is wanted, so it was
  given its own row (D-103) instead of being attributed to a decision that does not contain it.
  Recorded here because a design citation that cannot be checked is the same class of defect as the
  one this finding is about.
- **PROGRESS entry:** CONSIDER-11 marked `Deviation: F-27`
