# Issues — things only the owner can resolve

One file per issue, `ISSUE-<n>.md`, numbered in order of creation. Written by the coordinator
when a task is blocked for an owner-only reason (`docs/WORKFLOW.md §6, §8`): money, credentials,
external accounts, a dependency under AGPL/GPL/BSL/SSPL, a phase gate missed after two honest
attempts, rewriting or deleting git history. The blocked task gets status `blocked` in
`agent/BACKLOG.md` and work continues with the next ready task.

Format:

```
# ISSUE-<n> — <title>
Task: <backlog id>
Date: <YYYY-MM-DD>

## What is needed
## Why the agent cannot decide it
## What was tried
## Default if the owner does not answer
## Owner's answer
(left empty; the owner fills it in and the next session reads it)
```

The owner answers in the file, commits, and the next session unblocks the task.
