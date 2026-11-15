# ISSUE-7 — the Maven namespace `io.rail8` presumes a domain nobody has registered
Task: BRAND-1
Date: 2026-11-15

## What is needed
One of two things, before the first artifact is published anywhere public:
1. the domain `rail8.io` registered by the owner (money, an external account), or
2. the owner's choice of another namespace the project can prove it controls.

## Why the agent cannot decide it
The BRAND-1 row sets the groupId and the Java package root to `io.rail8`, and D-145 records that no
domain is registered for the name. A reverse-domain namespace is a claim on a domain: Maven Central
asks for proof of control of the domain behind a groupId before it accepts a publication (stated
from memory, not looked up — check the current Central rules before acting on it). Registering a
domain costs money and needs the owner's account; choosing a different namespace changes the name
every user of the API will import.

## What was tried
Nothing was registered and nothing was published. The rename (BRAND-1, D-149) uses `io.rail8` as the
row says, marked `draft — review at hand-over`.

## Default if the owner does not answer
The code keeps `io.rail8`. No artifact is published to a public repository until this is answered.
The rename script stays in the repository (`agent/tools/`), so a later change of the namespace is
one more run of it, not hand work. Alternatives that need no purchase, if `rail8.io` is not
available: `io.github.subapavel.rail8` (provable through the GitHub account), or a domain the owner
already holds.

## Owner's answer
