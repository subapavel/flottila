# ISSUE-1 — Context7 monthly quota exceeded; an API key is needed for library documentation lookups
Task: P0-05 (first hit); affects every later task
Date: 2026-10-18

## What is needed
A Context7 API key (free tier at context7.com/dashboard) stored as a network secret of the
`flottila` cloud environment and wired into `.mcp.json` as
`"headers": {"CONTEXT7_API_KEY": "${CONTEXT7_API_KEY}"}` (deploy/claude-cloud/README.md §2).

## Why the agent cannot decide it
Creating an account and a key is the owner's (D-008 names Context7 as the first source; the key
is an account matter — WORKFLOW §6 "owner-only").

## What was tried
`resolve-library-id` worked at session start (P0-01 environment check). The first `query-docs`
call in P0-05 (Vert.x `Future.await()` thread rules) returned "Monthly quota exceeded".

## Default if the owner does not answer
The documented fallback (CLAUDE.md, D-008): `javap -cp <jar>` on the resolved jars in `~/.m2`,
plus the `-sources.jar` fetched with `mvn dependency:sources` for Javadoc. Work continues on
that basis; no task is blocked by this issue.

## Owner's answer
(left empty; the owner fills it in and the next session reads it)
