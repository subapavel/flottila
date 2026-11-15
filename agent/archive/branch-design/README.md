# The pre-merge session branch's design, kept because the container is not durable

These files were working extracts held in the session scratchpad, which a reboot clears. They are
committed on the owner's instruction of 2026-11-15 ("priority is to get everything into main so
nothing is lost, even where it contradicts the rules"), and they break the rule that `agent/work/`
and scratch material stay out of the repository. That is the intended trade, not an oversight.

What each one is, and whether git could have produced it anyway:

| file | provenance | recoverable from git alone |
| --- | --- | --- |
| `ARCHITECTURE-branch.md` | byte-identical to `99d8d04:docs/ARCHITECTURE.md`, verified with `cmp` on 2026-11-15 | yes, `git show 99d8d04:docs/ARCHITECTURE.md` |
| `decisions-extract.md` | a curated extract of the branch's `docs/DECISIONS.md`, not a whole file | no |
| `design-extract.md` | the branch's error-handling design, quoted clause by clause; `extract:N` in `agent/work/CONSIDER-15-designer.md` refers to its line N | no |
| `F-26-branch.md`, `F-27-branch.md`, `F-29-branch.md` | the branch's findings under their branch ids; every one of those ids names a different finding on `main` | as whole files from the branch, but their ids collide |
| `findings-heads.txt` | the heading line of each branch finding, for the id audit | no |

The branch they come from is `claude/relaxed-archimedes-q2nb4c`, whose pre-merge range is
`634983a..99d8d04`; it is pushed, so those commits exist on the remote. `design-extract.md` is the
one of these that nothing else can reproduce, because the design was never a file on the branch -
it was assembled from the branch's packets and decisions during the merge.
