# F-26 — §5 puts `http.status` in `headers`; the code carries it as a prop

Date: 2026-11-14 · Task: CONSIDER-11 · ARCHITECTURE section: §5 (version 3.4)

- **Assumption in the document:** the §5 Envelope table (line 197) describes the `headers` field as
  "HTTP/JMS/mail headers from ingress, `http.status` from egress", placing the egress status among
  the headers — the one field whose row says it reaches producers, and then only through the flow's
  `forwardHeaders` allow-list.
- **How it fails in practice:** the egress status is a **prop**, not a header, and always has been.
  `HttpComponent.java:77` declares `public static final Key<Integer> STATUS = new Key<>("http.status")`;
  `:373` writes it with `e.props().with(STATUS, status)`; `:216` reads it back with
  `r.reply().get(STATUS).orElse(200)` to choose the sync reply status. Nothing writes it into
  `headers`. Two consequences: the table describes a propagation path that does not exist (as a prop
  it reaches producers *never*, per the `props` row, so no `forwardHeaders` entry can leak it), and a
  reader designing against §5 will reach for the wrong field. This surfaced while designing
  CONSIDER-11, whose `onError` `reply.status` must read exactly this value.
- **Severity:** minor — the code follows the documented intent (the egress status rides on the
  envelope and is not transport metadata of the next hop); only the field naming in the table is wrong,
  and no mechanism changes.
- **Evidence:** observed, not remembered —
  `grep -n "STATUS" flottila-components/http/src/main/java/io/flottila/components/http/HttpComponent.java`
  gives the declaration at :77, the prop write at :373 and the prop read at :216;
  `flottila-runtime/src/test/java/io/flottila/runtime/flow/InvoiceSyncHttpTest.java:240` asserts
  `props().asMap().get("http.status")` on the reply snapshot; `HttpStatusTest.java:39-40` and
  `HttpComponentTest.java:290` both use `HttpComponent.STATUS` as a prop key.
- **Change made:** no code change — the code is right. The §5 table is corrected: `http.status` moves
  from the `headers` row to the `props` row. The `headers` row keeps "HTTP/JMS/mail headers from ingress".
- **ARCHITECTURE.md edit:** changelog row added for 2026-11-14, version stays 3.4 (a table correction,
  no mechanism change).
- **PROGRESS entry:** CONSIDER-11 marked `Deviation: F-26`
- **For the owner's review:** CONSIDER-11's proposed `reply.status` reads this prop, which would make
  `core` hardcode a prop name owned by the http component — a layering smell under the
  `api` <- `core` <- `runtime` rule. The implementation rows should lift the key to an api-level
  constant (e.g. `Envelope.STATUS`) rather than let core name `HttpComponent`'s string. Rejected
  alternative: actually moving the status into `headers` to match the document — that would expose it
  to `forwardHeaders` and let an egress status leak to the next hop, which no one wants.
