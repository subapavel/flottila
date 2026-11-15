# UDFs leave milestone 1 and wait for Flottila's data model

Status: accepted (2026-11-10, owner). Supersedes the parts of ADR-0020 and of grilling #19 that put UDFs in milestone 1. ADR-0020's design remains the starting point for the later grilling.

## Context

ADR-0020 designs UDFs as static Java methods over a small API. The design covers how they are compiled in-process from the workspace and how they are guarded. Grilling #19 made them spec 7 of milestone 1, and GATE check 6 required them.

On 2026-11-10 the owner decided that UDFs depend on the data model of Flottila, the platform this engine is written for. They will need that model's messages, headers, properties, attachments and channels. That model does not exist yet. The same holds for lookups, mapping parameters, several messages per side and the test trace level (#165, #167, #168, #171).

## Decision

- **Milestone 1 has no UDFs.** Spec 7 and GATE check 6 are removed. `engine/udf-api` and `engine/udf` are not built, and their coverage and PIT floors leave check 8. `HANDOVER.md` moves to the last ticket of spec 6.
- **A UDF box stays a design-time error**, as lowering refuses it today. The `udfs` member of the mapping model keeps its shape (ADR-0010).
- **The studio's Functions tab is a preview feature** (ADR-0028, #169).
- **When Flottila's data model exists**, UDFs start again with a grilling that takes ADR-0020 as its input. The same applies to the other features listed above.

## Consequences

- The milestone 1 gate can be reached without UDFs.
- The two ADR-0020 observation requests are not opened in milestone 1.
- A PO mapping that uses UDFs cannot run until this is picked up. The PO importer (after milestone 1) must keep refusing UDF boxes, or carry them unresolved.

## Considered options

- **Build ADR-0020 as designed now**: rejected by the owner. The API would be designed before the data it must expose.
- **Build only the engine part (compile, isolate, run single-value UDFs)**: rejected. The container API and `MappingRun` are exactly what Flottila's model shapes.
