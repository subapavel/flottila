# Flottila Mapping

Open-source mapping engine and editor for enterprise integration. Draw a mapping between two
message structures the way SAP PO graphical mappings work — queues, contexts, standard functions,
Java UDFs — and run it compiled on Java 25. One intermediate representation will also carry
XSLT 3.0 / XPath 3.1, so a drawn mapping exports to readable XSLT and a stylesheet runs on the
same engine. The editor is React + PatternFly.

Status: **Milestone 1 — spec 1 (walking skeleton) built: one field link from the editor through
the engine, the service and the XSLT export.** See `docs/wayfinder/phase-0-map.md` and
`docs/GATE.md` (milestone 1).

Built AI-first: agents write the code under the rules in `CLAUDE.md`, `AGENTS.md` and
`docs/WORKFLOW.md`; work is tracked in GitHub Issues.

## Run the Mapping studio and test a mapping

1. Start the service: `./mvnw -pl service -am quarkus:dev`. It serves the mappings of `corpus/`.
2. Start the UI: `npm run dev` in `ui/` (run `npm ci` there first).
3. Open http://localhost:5173. The start page lists the mappings.
4. Choose **Open in studio** on a mapping, for example `order-id-copy`. It opens read-only.
5. Choose **Edit**, then the **Test** tab.
6. Paste the mapping's corpus input into the Source pane, for example
   `corpus/cases/link/direct-copy/001/input.xml` for `order-id-copy`.
7. Choose **Run simulation**. The Result pane shows the output, and the processing log shows the run.

If port 8080 is taken, set `QUARKUS_HTTP_PORT` for both (PowerShell `$env:QUARKUS_HTTP_PORT=8081`,
bash `QUARKUS_HTTP_PORT=8081`) and the dev proxy follows. `?api=fake` in the address runs the UI on
its built-in fake service, with no backend.

Licence: Apache 2.0 (`LICENSE`). Third-party notices: `NOTICE`.
