# Hand-over

This file tells you how to start the Mapping studio, draw and run a mapping, and run the gates
and benchmarks. It covers GATE check 9. You need only JDK 25 and Node 22 (22.12 or newer,
`ui/package.json`).

## Start the stack on Linux

Start from a clean clone. The Maven wrapper downloads Maven; you install nothing else.

An installed Maven (`mvn`) within the enforcer's range (3.9.11 up to, not including, 4) works the
same: replace `./mvnw` with `mvn` in any command below.

### Development: service and UI

The service serves the mappings of `corpus/` on port 8080. It binds to `127.0.0.1`. It runs over
a workspace, a directory of `*.mapping.json` files (ADR-0014). Start it:

```bash
./mvnw -pl service -am quarkus:dev
```

In a second terminal, start the UI. Vite serves on port 5173 and sends `/api` to the service:

```bash
cd ui
npm ci
npm run dev
```

To check that the service runs, print the status code of two GETs (each gives `200`):

```bash
curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/api/mappings
curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/api/catalogue
```

`/api/structures` takes POST only, so a GET gives `405`. To confirm the `127.0.0.1` bind, run
`ss -ltn`. Where `ss` is missing, read `/proc/net/tcp`: the line `0100007F:1F90` is `127.0.0.1:8080`
(`1F90` is 8080 in hex).

Open http://localhost:5173. If port 8080 is taken, set `QUARKUS_HTTP_PORT` for both processes
(for example `QUARKUS_HTTP_PORT=8081`) and the dev proxy follows.

`?api=fake` in the address runs the UI on its built-in fake service, with no backend.

### One origin: the `-Pui` jar

The jar serves the studio and `/api` from one origin (ADR-0017). `clean` is mandatory with `-Pui`.

```bash
cd ui
npm ci
npm run build
cd ..
./mvnw -B -Pui -pl service -am clean package -DskipTests
```

Start it over a workspace. It binds to `127.0.0.1`:

```bash
java -Dquarkus.http.host=127.0.0.1 -Dflottila.mapping.workspace=corpus -jar service/target/quarkus-app/quarkus-run.jar
```

Open http://127.0.0.1:8080.

## Start the stack on Windows PowerShell

This section is not verified by the gate. The ticket and spec gates are bash scripts for Linux and the VM.

Install JDK 25, Node 22 and a Maven within the enforcer's range. Start the service:

```powershell
mvn -pl service -am quarkus:dev
```

In a second PowerShell window, start the UI:

```powershell
cd ui
npm ci
npm run dev
```

Open http://localhost:5173. For another service port, run `$env:QUARKUS_HTTP_PORT=8081` in both
windows first.

The benchmarks run on Windows for information only. Build `benchmarks.jar`, then run it:

```powershell
mvn -B -Pbench -pl bench/jmh -am package -DskipTests
```

```powershell
java -jar bench\jmh\target\benchmarks.jar
```

Numbers from a PC judge nothing and write nothing (spec #181).

## Draw and run a mapping in the studio

ADR-0028 describes the studio. The tabs are Overview, Definition, Test, Signature and Functions.
Definition and Test show only a link to Signature until the mapping has both a source and a
target message, so you choose the messages first. The corpus example is `order-id-copy`: it
copies a field of `corpus/structures/skeleton/order.xsd` to the same field of the same schema.

1. Open the start page. It is a table of the mappings with the columns Name, Package, Source,
   Target and Description.
2. Choose **New mapping**. Enter a Name and, if you like, a Description. The Package is empty, or
   the path of a folder that already exists in the workspace. The service does not create
   folders: a Package that does not exist gives "no such mapping or directory in the workspace"
   (a 404). Create. The studio opens in edit mode on the **Signature** tab.
3. On the Signature tab, the source side says "No source message yet." Choose **Add source
   message**. The "Choose source message" dialog opens. In **Bundle**, write one schema path per
   line, relative to the mapping's package (the workspace root when the Package is empty). For the
   example: `structures/skeleton/order.xsd`. Leave **Root** as "XML element" and fill **Root
   element** (`Q{urn:flottila:corpus:skeleton}Order`). Choose **Apply**. Do the same with **Add
   target message**.
4. Open the **Definition** tab. Drag a source field onto a target field for a direct connection.
   In the expression of a target field, drag from an output to an input to connect boxes. To add
   a box, choose **Add node** in the canvas toolbar and pick a function.
5. Choose **Save**. It writes the mapping file to the workspace.
6. Open the **Test** tab. Paste a source message into the Source pane, for example
   `corpus/cases/link/direct-copy/001/input.xml`. Choose **Run simulation**. The Result pane shows
   the output. The **Processing log** shows the run.
7. To see a queue, go back to the Definition tab. Right-click a node of the expression (a function
   box or the target field's node), then choose **Display Queue**. Or select the node and choose
   the **Display Queue** button of the canvas toolbar. The dialog shows the queues of the last
   test run. Without a run it says "No test data yet" and, in edit mode, offers **Load test
   file**.

To compare with the finished example, choose **Open in studio** on `order-id-copy` in the list.

## Run the gates and benchmarks

Run the ticket gate from a worktree. `<ticket>` names the logs and `<modules>` are the touched
modules, comma-separated (`docs/WORKFLOW.md` section 6):

```bash
scripts/gate-ticket.sh <ticket> <modules> origin/<integration-branch>
```

Run the spec gate once per spec:

```bash
scripts/gate-spec.sh <spec> [pit-modules]
```

Step 5 of the spec gate runs JMH. It judges the benchmarks, and step 4 writes `ratchet/bench.tsv`,
only on the VM with this label and `nproc` equal to 4. Without the label it measures and prints:

```bash
MACHINE_LABEL=claude-cloud-4vcpu scripts/gate-spec.sh <spec> [pit-modules]
```

A target and a regression are judged on the median of three runs. Baselines come from the spec
gate only (ADR-0021, Notes of 2026-11-11; F-36).

Build and run the benchmarks yourself with `-Pbench`:

```bash
./mvnw -B -Pbench -pl bench/jmh -am package -DskipTests
```

```bash
java -jar bench/jmh/target/benchmarks.jar -f 3 -wi 3 -i 5 -w 1s -r 1s -prof gc
```

The numbers of spec 6 are in `bench/RESULTS.md`: "Spec 6 first measurement (#189)" and "Orders
allocation (#194)". A run off the VM is informative only.

The `bench` workflow (`.github/workflows/bench.yml`) is a manual smoke test at `-f 1`. It is never
a gate.

Check this file with its script test:

```bash
bash scripts/test/handover-test.sh
```

## Open findings

Findings are in `docs/findings/`. On 53415fbf these are open or deferred (the others are closed,
accepted or decided):

- F-13: the ticket-gate budget is missed once the engine has code; the profiling moved to a
  process spec after milestone 1.
- F-16: the mutation ratchet counts timed-out mutants as killed; deferred to the same process spec.
- F-18: the XSLT export's first-read rule rests on Saxon laziness; deferred to the export spec.
- F-27, F-28, F-30, F-31, F-32: wording questions for the owner on ADR-0013 and ADR-0027.
- F-38: a mandatory target field reads a context its queue lacks; open, deferred past milestone 1
  (#220). `target/minOccurs/004` stays `assumed`.
- F-51: a constant fed into a context function is written into every Item (ADR-0004:65); open. The
  fix and a corpus case come after milestone 1 (#221).

The observation request #222 is deferred past milestone 1 (F-53); it does not hold the gate.
F-53 point 4 (the re-pin rule in ADR-0026) is the next step after the gate.

## ADRs to revisit

- ADR-0013 and ADR-0027: the amendments that F-27, F-28, F-30, F-31 and F-32 ask for.
- ADR-0004: the observation requests, and line 65 (a constant fed into a context function; F-51).
- ADR-0006: the readability of its amendment list, and the assumed readings that #222 asks about.
- ADR-0026: the re-pin rule, a pinned-71 re-pin also when fidelity or an execution kind changes
  (F-53, point 4).
- ADR-0021: the bytecode tier, and the optional `po-owner-local` and `is-owner-local`
  observation requests (#191). They never gate.
- ADR-0017: the ticket-gate budget (F-13).

## Next three specs

In this order (`docs/GATE.md`, "After milestone 1", grilling #19):

1. JSON output: constructor records, serializer and export (ADR-0008).
2. The bytecode tier: `engine/bytecode` and the fifth metamorphic variant (ADR-0021).
3. `formatVersion` 2 and the PO importer (ADR-0010).

The findings of #212 do not move these three specs.

The XSLT front end follows them. UDFs, lookups, parameters, headers, properties, attachments and
several messages wait for Flottila's data model (ADR-0030).
