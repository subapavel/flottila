# ISSUE-6 — commits are unsigned, so GitHub never shows them as Verified
Task: —
Date: 2026-11-15

## What is needed
A commit-signing key the owner controls, registered on their GitHub account, if Verified badges are
wanted on this repository. Signing keys are credentials, so this is owner-only (`issues/README.md`).

## The measured state, so nobody treats this as a regression
Every commit in this repository is unsigned. `git log --format='%G?'` prints `N` for all of them,
including the 119 commits of the core rework and the owner's own merge commits `542087b`, `4ba2937`
and `4d4c62c`. Nothing was broken by any session; the repository has simply never been signed.

The container does carry a signing setup in `/root/.gitconfig` — `commit.gpgsign true`,
`gpg.format ssh`, `user.signingkey /home/claude/.ssh/commit_signing_key.pub`, signer
`/tmp/code-sign` — but **that public key file is 0 bytes**, so no commit made here can be signed.
A commit made with `commit.gpgsign true` still comes out with `%G?` = `N` and no error. Verified
with a throwaway commit in a scratch repository, not inferred.

## Why the agent cannot resolve it
Two reasons, either sufficient.
1. The key is a credential. The agent must not create, fetch or install one.
2. A Stop hook in this environment proposes the fix `git config user.email noreply@anthropic.com`
   plus `git commit --amend --reset-author`. Taking it would make **Claude** the commit author.
   D-004 makes the owner the author precisely because the `Signed-off-by` trailer is the owner's
   DCO statement, and a DCO sign-off that does not match the author is not a sign-off. The hook
   does not know about D-004, so it is declined each time it fires.

## Options for the owner
1. **Leave it.** An unsigned commit carries no badge on GitHub; it is not marked bad. Nothing in
   the build, the CI gates or the DCO depends on signatures. This is the status quo and costs
   nothing. Recommended unless a badge is wanted for its own sake.
2. **Sign as the owner.** The owner adds an SSH signing key to their GitHub account under
   "SSH and GPG keys" as a *signing* key, and the private key is made available to the session's
   environment. Author, committer and signature then all agree, D-004 holds and the badge appears.
   It puts a private key into the session's environment, which is the cost to weigh.
3. **Split author and committer.** Author stays the owner with the DCO sign-off; committer becomes
   the platform identity that holds a working key. GitHub verifies against the committer, so the
   badge would appear without a key of the owner's. Not available today — the key file here is
   empty — and it would amend D-004, so it is the owner's call, not a workaround to apply quietly.

## What was tried
Nothing was changed. The two commits the hook named, `e07692c` and `2dacd53`, are unpushed, so
amending them would not have rewritten published history — but amending them the way the hook asks
would have replaced the owner's authorship on them alone, leaving the repository's history
inconsistent and those two sign-offs invalid. The hook was declined instead and this file written.
