# Triage Labels

The upstream skills speak in five canonical triage roles; this repo uses them unchanged and adds
the project's own labels below.

| Label in mattpocock/skills | Label in our tracker | Meaning                                  |
| -------------------------- | -------------------- | ---------------------------------------- |
| `needs-triage`             | `needs-triage`       | Maintainer needs to evaluate this issue  |
| `needs-info`               | `needs-info`         | Waiting on reporter for more information |
| `ready-for-agent`          | `ready-for-agent`    | Fully specified, ready for an AFK agent  |
| `ready-for-human`          | `ready-for-human`    | Requires the owner                       |
| `wontfix`                  | `wontfix`            | Will not be actioned                     |

Project labels (`AGENTS.md`, Ticket classes; `docs/WORKFLOW.md`):

| Label | Meaning |
| --- | --- |
| `spec` | A deliverable; parent of its tickets |
| `class:A` · `class:B` · `class:C` · `class:G` | Ticket class: sets model, effort, who writes the red, per-ticket review |
| `oracle:differential` · `oracle:corpus` · `oracle:w3c` | Acceptance decided by an oracle; case ids in the body |
| `wayfinder:map` · `wayfinder:research` · `wayfinder:prototype` · `wayfinder:grilling` · `wayfinder:task` | Wayfinder map and decision tickets |
