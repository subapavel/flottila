# Codex prompt: run every open observation on SAP PO 7.5 and SAP Integration Suite (CPI)

> For the owner, before you start Codex: fill in the three values in "Systems" below. Everything
> after the line "--- PROMPT ---" goes to Codex as it is.

--- PROMPT ---

You are running behavioural observations for the open-source project **flottila-mapping**
(github.com/subapavel/flottila-mapping). The project is a re-implementation of SAP's graphical
message mapping. To match SAP, it needs the real output of small **synthetic** mappings on two SAP
systems. You operate both systems' UIs yourself, the way a person would. You build each mapping
exactly as specified, run each input, and record what the system really did. You do not judge or
fix anything.

Work through the night without asking questions. When something blocks you, record it the way
§9 says and go on to the next run.

## 1. Systems

Use only these. If a value is missing or does not work, record `ENVIRONMENT_BLOCKED` for that
system and continue with the other one.

- **PO 7.5 (ESR, Enterprise Services Builder):** software component version `<PO_SWCV>`,
  namespace `<PO_NAMESPACE>`. Create every object in this namespace and nowhere else.
- **CPI (SAP Integration Suite, Cloud Integration, Design):** integration package `<CPI_PACKAGE>`.
  Create every artifact in this package and nowhere else.
- Only message mappings (PO) and Message Mapping artifacts (CPI). **No channels, ICOs, iFlows or
  any other configuration objects**, on either system.

## 2. Hard rules

1. **Touch nothing that is not yours.** In PO, create, edit and activate only objects in
   `<PO_NAMESPACE>`. Never transport, never edit an object outside that namespace, and never
   change a channel, an ICO, a user or any configuration. In CPI, work only in `<CPI_PACKAGE>`.
   Never deploy to a production tenant, and never change tenant settings or security material.
   Delete only artifacts you created yourself, and only when a step below says so.
2. **Never invent a result.** Every output file holds exactly what the system showed. If you did
   not see it, it is not a result (§9).
3. **No real system data enters the repository.** Replace the following before you write a file:
   - SIDs, host names, ports, user names, tenant ids, URLs and the PO namespace;
   - exchange ids, written as `<exchange-id>`;
   - object hashes such as `Context@1fc9d164`, written as `Context@<id>`.

   The synthetic inputs, outputs and error texts are fine.
4. **Change only these paths:**
   - `corpus/observations/<obs-id>/po75/`
   - `corpus/observations/<obs-id>/cpi/`
   - `corpus/observations/<obs-id>/probes/`
   - `corpus/observations/<obs-id>/meta.json`

   Never edit a `request.md`, an input file, a structure in `corpus/structures/`, `docs/`, code,
   tests or `ratchet/`. If a request is wrong or cannot be drawn, say so in `meta.json` (§8) and go on.
5. **Draw what the request says, not what would work better.** Do not "repair" a mapping to avoid
   an error. The error is the observation.

## 3. Repository

```bash
git clone https://github.com/subapavel/flottila-mapping.git
```

```bash
cd flottila-mapping && git checkout main
```

```bash
git checkout -b codex/observation-run-<YYYY-MM-DD>
```

Read these before the first run:

- `corpus/observations/README.md`: the notation of a `request.md` (boxes, `in1`/`in2` port order,
  `with context X`, node-to-node wires) and what to return.
- `corpus/observations/obs-target-creation/`: a finished observation done by hand. Copy its file
  style: `po75/*.xml`, `po75/*.txt` for errors, `*-queue.txt` for display queues, `probes/`, and
  `meta.json`.
- Each `corpus/observations/<obs-id>/request.md` before you start that obs-id.

The structures (XSD, one JSON schema) are in `corpus/structures/observations/`. The inputs sit next
to each `request.md`.

## 4. Order of work (most important first)

Do each obs-id completely, first on PO and then on CPI, and push before you start the next one.
The order:

| # | obs-id | GitHub request |
| --- | --- | --- |
| 1 | `obs-target-creation-2` | #134 |
| 2 | `obs-lift-reuse` | #32 |
| 3 | `obs-useoneasmany` | #34 |
| 4 | `obs-ifwithoutelse` | #35 |
| 5 | `obs-shared-box` | #37 |
| 6 | `obs-division` | #38 |
| 7 | `obs-structures` | #36 |
| 8 | `obs-context-functions` | #33 (largest: about 37 mappings) |
| 9 | `obs-boolean` | none |
| 10 | `obs-constant` | none |
| 11 | `obs-statistic` | none |
| 12 | `obs-text` | none |
| 13 | `obs-arithmetic` | none |
| 14 | `obs-date` | none |

`obs-target-creation` is already done; do not redo it. Some request sections are headed
"Spec 2 cases" or similar. Each row of those sections names a case id, a mapping and an input,
and every row is a run.

**Resume rule:** if `po75/<input>-<mapping>.*` or `cpi/<input>-<mapping>.*` already exists, that
run is done. Skip it.

## 5. Building the mappings

### Both systems

- Source and target structures come from the XSD the request names, with the root element it names.
- Each box table lists a function, its parameters in braces and its inputs in port order.
- Set a source field's context only where the request says `with context X`. Otherwise leave the
  default.
- A target field with no wire stays unmapped.
- Name each mapping after the request's mapping name, with underscores, e.g. `mc_min2`.
- Some requests ask for the label of a parameter or option, its default, or the editor's message.
  Read it off the UI and write it down exactly.

### PO 7.5 (ESR)

- Import each XSD once as an External Definition (category xsd), then reuse it. Some may already
  exist from earlier sessions (`src_orders`, `tgt_create`, `tgt_gen`, `tgt_unmapped`, `tgt_list`,
  `tgt_nested`). Reuse them if they are in your namespace and match the file.
- Message Mapping: set the context with right-click on the source box → Context → element. An
  italic source box means a non-default context.
- After drawing, save and run **Check**. Record every message, or the fact that there was none.
  Activate only if the request asks about activation.
- Run on the **Test** tab: load the input file and run it. Copy the output from the XML source view.
  For an error, copy the dialog text and the log.
- **Display Queue**: right-click a box → Display Queue, after a test run with that input. Turn on
  the row-number column (second toolbar button).
- Function categories: `createIf`, `removeContexts`, `mapWithDefault`, `collapseContexts`,
  `splitByValue`, `useOneAsMany`, `exists`, `sort`, `sortByKey` and similar are under **Node
  Functions**, not Boolean.

### CPI (Message Mapping artifact)

- Create one Message Mapping artifact per mapping in `<CPI_PACKAGE>`, and upload the XSDs as
  source and target messages.
- **CPI cannot copy a mapping.** Either build a new one, or edit an earlier one in place, but only
  after that earlier one's results are saved. Some requests say "on CPI, edit that mapping in
  place"; then do that. Note every in-place edit in `meta.json`.
- After drawing, save and record any message: a red field, a triangle tooltip such as "Target has 1
  errors", or a dialog.
- Run with **Simulate**: upload the input as the test file. Download the output, or copy the error
  dialog text exactly.
- **Display queue**: after a simulation, open the box's "Function Message Queue" and turn on the
  S. No. column.

## 6. Reading a display queue (both systems)

- The first row `<null>` (gray) is the start.
- Every **gray row is a context change**. It repeats the text of the value before it, so it is not
  a value. CPI also sets these rows in italics.
- The last dark-gray row is the end of the queue.
- An empty context shows as two context-change rows in a row. CPI shows its marker as `[ ]`, PO as
  `<null>`.
- SUPPRESS is shown as `SUPPRESS`. PO marks it in cyan.
- An empty-string value is `[]` on PO and `[ ]` on CPI, on a white row.

**Transcribe literally first**, one line per row: row number, each column, shading. Then add a
`Reading:` line that writes the queue in context notation, e.g. `[a] [] [c]`. Example (PO, input
`d2.xml` of obs-target-creation):

```
row  mapWithDefault:in0   shading      mapWithDefault:out   shading
0    <null>               gray         <null>               gray
1    [a]                  white        [a]                  white
2    [a]                  gray         [a]                  gray
3    [a]                  gray         [D]                  white
4    [c]                  white        [D]                  gray
5    [c]                  dark gray    [c]                  white
6    (none)                            [c]                  dark gray

Reading: in0 = [a] [] [c]; out = [a] [D] [c].
```

## 7. Files you write

The base name is `<input-without-extension>-<mapping-name>`, with the request's mapping name in
lowercase and hyphens. Inside `corpus/observations/<obs-id>/`:

| Result | File |
| --- | --- |
| Output XML | `po75/<base>.xml` or `cpi/<base>.xml`, **byte for byte** as the system wrote it: PO pretty-printed, CPI on one line. Never reformat. |
| Error | `po75/<base>.txt`. See below. |
| Display queue | `po75/<base>-queue.txt`. Name the box: `Display Queue of box R1 (removeContexts) ...` |
| Editor or check message | `po75/<base>-check.txt` (or `<mapping>-check.txt` when no input is involved) |
| Probe input | `probes/probe-<topic>-<letter>.xml` (§8) |
| Probe output | `po75/probe-<topic>-<letter>-<mapping>.xml` / `.txt` |

The same file names go under `cpi/`.

An error `.txt` file holds:

- the first line: system, tab or action, input, mapping, case id, and `No output.`;
- one line saying where it fired: Check, activation, compile, or run time on which target field
  or box;
- the dialog text, exactly;
- the exception class and message lines;
- the `Caused by` lines and the first five mapping-tool frames.

Leave out the server frames below the mapping tool (`com.sap.engine...`) and write
`(server frames below the mapping tool omitted)`. See
`obs-target-creation/po75/max-002-spec2-create-if-row.txt`.

Every `.xml` and `.json` file in this tree is parsed by a test. Keep them well-formed, and check
with `xmllint --noout <file>`. A deliberately malformed output goes into a `.txt`.

## 8. Probes, and `meta.json`

### Probes

A run can fail **before it reaches the question its request row asks**, for an incidental reason.
In #31, an input used `no` as a boolean and `createIf` threw "Cannot cast 'no' to boolean" before
any minOccurs logic ran.

1. Record that failure as the result of the run. It is a real observation.
2. Write **one** probe input in `probes/`, with the smallest change that removes the incidental
   cause (e.g. `false` for `no`). Open it with an XML comment that says it is an owner probe,
   which run it stands in for, and what changed.
3. Run the probe on the same mapping, record it, and say in `meta.json` which case it answers.

Do not probe for anything else, and never edit the original input.

### meta.json

Keep one `meta.json` per obs-id. Copy the shape of `obs-target-creation/meta.json`:

- `obsId` and `request` (the issue URL, or `null`);
- the `po75` and `cpi` releases (read them as described below), with the date and the tool used;
- `divergence`: one entry per run where PO and CPI wrote different output. PO is the reference.
- `notes`: one line per observation, saying what was confirmed and what contradicted the assumed
  reading, for every check message, every in-place edit, every probe, every label or default
  asked for, and anything surprising;
- `runs`: one object per run, for example:

```json
{"mapping": "mc-min2", "input": "minOccurs-004.xml", "case": "target/minOccurs/004",
 "po75": "observed", "cpi": "observed", "reading": "contradicted",
 "summary": "Grp 2 gets one Row b on both systems; no error"}
```

The status words are `observed`, `error-observed`, `NOT_RUN` (with a reason) and
`ENVIRONMENT_BLOCKED` (with the blocker). The reading is `confirmed`, `contradicted`, `other` or
`n/a`.

Where to read the releases:

- PO: NWA → System Information, "Java Kernel Version" `7.50.SSPP...` (SP and patch). Release, SP
  and patch only.
- CPI: the user menu → About: Integration Suite version, Cloud Integration, Cloud Integration
  Runtime and the service plan. **Not** the tenant id.

## 9. When something goes wrong

- **The UI blocks you** (a session expires, an editor hangs or a lock is held): retry once, then
  record `ENVIRONMENT_BLOCKED` with what you saw and go on.
- **You cannot draw the mapping as written** (a function or parameter is missing in that editor,
  or the request is ambiguous): build nothing. Write `NOT_RUN` with the exact reason, e.g. "CPI has
  no parameter Else = SUPPRESS on ifWithoutElse; options shown: ...", and go on.
- **A run needs more than a message mapping:** do not build it. Write `NOT_RUN` with the reason
  and go on. This applies to `structure/jsonNull/001` (`obs-structures`, input `nil.json`) on both
  systems. Reason to record: "PO message mapping reads XML only; the case tests the adapter's /
  converter's JSON to XML conversion, not the mapping; channels and iFlows are out of scope for this
  run." The other runs of `obs-structures` (`nil.xml` with `xsi:nil`, choice, attributes) are
  normal mapping runs. Do them.
- **Time-dependent functions (`currentDate`):** record the exact run time and the zone you saw,
  next to the output.
- **Never** write an output you did not see, and never copy a PO result into `cpi/` or the other
  way round.

## 10. Commits and the end

- Commit after each obs-id per system, e.g. `corpus: PO 7.5 observations for obs-lift-reuse (#32)`
  and `corpus: CPI observations for obs-lift-reuse (#32)`. Push the branch after each commit with
  `git push -u origin codex/observation-run-<YYYY-MM-DD>`. Do not open or comment on issues or
  pull requests. The owner does that.
- At the end, or when you stop, your last message is a report:
  - for each obs-id: done, partly done or not started, and the counts of observed, NOT_RUN and
    ENVIRONMENT_BLOCKED runs;
  - every run whose reading is `contradicted` or `other`, one line each;
  - every divergence between PO and CPI;
  - what is left and why;
  - the branch name and the last commit.
