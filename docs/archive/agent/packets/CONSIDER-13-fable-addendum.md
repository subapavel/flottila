# CONSIDER-13 — addendum to the Fable brainstorming prompt

Written 2026-11-14, after the Fable session reported that `agent/packets/CONSIDER-13-design-draft.md`
is not on `main` (`main` is `634983a`, whose last CONSIDER is 11) and that it had not seen the
designer's rejection of the alternatives.

Both are true. The draft is committed in `94fceab` on the branch
`claude/relaxed-archimedes-q2nb4c`, which is pushed, so it can be read from that branch — it is
only absent from `main`. Whether that branch merges to `main` is the owner's open decision.

Everything below the line is pasteable into the Fable session as a follow-up message.

---

Correction to the prompt I gave you, and then the missing text.

**The prompt overstated the rejections.** It said the designer rejected the two alternatives "one
paragraph each". They are not paragraphs. They are two bullet points of one sentence each, quoted
here verbatim and in full, with nothing omitted:

> - Folding it into the snapshots as a `level` + `text` column: one row per step, gated by
>   `recording.level`, cannot hold several sentences nor a severity.
> - Folding it into `System.Logger` with an MDC-style context from `MessageContext`: not retrievable
>   per message, not retained with the message, not queryable.

That is the entire argument against the two cheapest designs. Judge it on that basis — and note
that the thinness is itself evidence about how carefully the premise was examined.

For completeness, the designer's other five rejections, which are about the shape rather than the
premise:

> - Two tables (`message_log` + `flow_log`): same shape, same retention, same screen; a nullable
>   `message_id` with a partial unique index is cheaper.
> - Reusing `PgMessageStore`'s writer: a trace flood would evict snapshot rows from a shared 6400
>   backlog.
> - `saveWaiting` for log lines: it holds the run's admission permit for a line whose loss loses no
>   message.
> - `warning` as the schema value, in favour of `warn`.
> - A per-flow lines-per-second budget: needs a window and a clock; the writer's backlog bounds it.

**What the draft itself lists as expensive to reverse**, which is where I most want your attention:

> - `StepResources` gaining a component and being built per step: public API, every `StepFactory` and
>   every day-1 call site (`SimSteps`, `FlowTests`, `FlowHash`, both step modules).
> - `message_log`'s columns and its unique index: a persisted format; `ordinal` is the public
>   ordering key and appears in the admin JSON.
> - The level names and their order: flow-file wire format (schema v1) and an admin query parameter.
> - `GET /admin/messages/:id/log` and `/admin/flows/:flow/log`, and the `log` object added to the
>   timeline response.

**Three conflicts the designer found with the architecture document**, unresolved:

> - §7's persistence table list already names an `audit` table, so "audit log" is taken; the draft
>   calls this the message log throughout. The name needs confirming before any edit.
> - §8's write path says every snapshot goes "local batch queue → Kafka `monitoring` → ClickHouse".
>   Log lines are Postgres-only today (no ClickHouse client exists), so §8 must say the message log
>   follows the snapshots to ClickHouse in P2-S1/S2, or the deviation is silent.
> - `recording.payload: metadata-only` keeps bodies out of the store; a declarative `log` step whose
>   `message` expression renders a payload defeats that per-flow choice.

Two facts that bear on cost and that the prompt did not give you:

- There is **no benchmark number** anywhere in `bench/RESULTS.md` for this write path. Nothing about
  it has been measured.
- The table is designed to come **after** two migrations that are not written yet (the ones adding
  an `attempt` column and a message-lifecycle table), and it assumes `attempt` exists. So this is
  not a change that can land on its own.

Everything else in my first message stands, including the request that you challenge the premise
before anything else.
