# flottila-schemas

Published JSON Schemas (draft 2020-12) for the v1 file model, each carrying `schemaVersion: 1` and
`kind`, designed in P0-12 as `draft — review at hand-over` (ARCHITECTURE amendment 4, D-040):

| kind | file | schema |
| --- | --- | --- |
| `package` | `package.yaml` | `src/main/resources/schemas/package.schema.json` |
| `endpoint` | `endpoints/<id>.yaml` | `src/main/resources/schemas/endpoint.schema.json` |
| `flow` | `flows/<id>.yaml` | `src/main/resources/schemas/flow.schema.json` |
| `test` | `flows/<id>.test.yaml` | `src/main/resources/schemas/test.schema.json` |

The jar packages them under `schemas/`; `io.flottila.schemas.Schemas` validates a document
(`Schemas.forKind(doc.getString("kind")).validate(doc)` → `Report` with JSON-pointer paths),
`Documents` reads YAML or JSON into the `JsonObject` the validator takes, and `Package.load(dir)`
plus `crossCheck()` says what one file cannot: dangling endpoints, tests of unknown flows, missing
fixtures. `packages/demo` is the reference package and is validated by this module's tests; every
rule the schemas enforce has a negative fixture under `src/test/resources/invalid/<kind>/` (37 today; add one with
every new rule — `InvalidDocumentsTest` picks them up by folder). Fixture and stylesheet paths inside a document are
relative to the file that names them (D-040).
