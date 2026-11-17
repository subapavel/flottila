# JsonWriter sinks: one line per result, drained per result, never flushed

Status: accepted (2026-11-11, architect, on the designer's draft for #33 "S1.11 `.` end to end, compact"); amends ADR-0016 (`JsonWriter.sink`)

- **One line per result.** Each `emit` writes the result's text and one LF, jq's default output.
  `-j`, `--seq` and `--raw-output0` arrive later as `OutputOptions` factories or withers (ADR-0024).
- **Drained per result, never flushed.** A result's bytes reach the target before `emit` returns, in
  chunks of the Workspace's 64 KiB print buffer. The sink never calls `flush()`; the host or the
  command line does. A Stub met in the middle of a result may leave a prefix written.
- **Errors.** A target's `IOException` leaves `emit` as `UncheckedIOException` with it as the cause
  (ADR-0016's pass-through, for a checked exception).
- **Staleness.** After each drain the writer re-checks its document (ADR-0026): the target is host
  code and may read into the same Workspace.

## Considered options

- Buffering until a flush or close: `JqSink` has neither, so the last results would never leave.
- `flush()` after every result: a system call per result; that is `--unbuffered` (ADR-0023).
- Holding a whole result before writing: memory grows with the largest result (G6 is 10 MB).
