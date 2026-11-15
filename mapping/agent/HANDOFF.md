# Handoff

state: GATE_MET — milestone 1, gate ticket #226, on main 53415fbf (gate branch `gate/m1`). The gate-reviewer returned `gate: MET`: checks 1–9 are all MET. The previous run #198 (NOT_MET) is superseded.
evidence:
- CI run 43530093762 on 53415fbf: backend, ui, stack and windows all succeeded.
- `MACHINE_LABEL=claude-cloud-4vcpu scripts/gate-spec.sh gate`: all 21 steps VERIFIED_PASS.
  - Oracles: golden corpus 618, differential 323, 0 failed.
  - PIT floors held.
  - `RATCHET BENCH OK`; the numbers are in `bench/RESULTS.md` ("Milestone 1 gate, second run (#226)").
  - Check 9: `.logs/gate-9.log` on a fresh worktree. Dev mode and the `-Pui` jar both listen on 127.0.0.1:8080.
- `HANDOVER.md` is at the repo root.
drift: ADR-0004, 0006, 0018, 0025 and 0026 were amended in #212. Each amendment is justified by a finding (F-37 to F-53) with an owner decision. None contradicts FOUNDING §4/§6.
non-blocking points from the gate review (they go into the cleanup ticket unless noted):
- `/gate` step 2: run `ratchet.java check` after spec-gate step 1b, not before the spec gate. `gate-8-ratchet.log` is all NOT_RUN.
- GATE check 8 names "the nightly run of the gate commit". Add the nightly run id to #226 when it lands, or amend check 8 through a finding.
- PR #210 `trailers` job: the owner's exception for eight imported commits has no finding file.
- F-51 (major, open): ADR-0004:65 is contradicted and has no corpus case. First post-gate ticket: a red case from obs-lift-reuse lift-4, then the fix.
- F-53 point 4: the ADR-0026 re-pin rule.
- The bench gains are uncommitted (F-40). The next spec gate's regen raises `ratchet/bench.tsv`.
- HANDOVER drops F-12 without a closure note; check that spec 4 resolved it.
- FOUNDING §1/§6 still list Java UDFs in milestone 1. Add "Refined by ADR-0030".
- ui lint: 2 warnings (react-hooks/incompatible-library).
- The HANDOFF line for the #212 review has no PASS/FAIL words.
carried (cleanup ticket), from HANDOFF #212:
- SingleValueBindingTest:99 name.
- Stale comments: LiftVerifierTest:41 and StatisticFunctionsTest:148.
- The statistic.xsl counter header says "assumed".
- F-45:173 and F-44:59 wording.
- averageDividesTheTotalByTheCount needs a value-only generator.
- No export test for average raising on a SUPPRESS-only context.
- The fixValues note order (ADR-0028).
- ADR-0006 Status line → a dated Amendments list.
- Node.Repeat → a sealed pair (JMH decides).
- FidelityAgreesWithTheCasesTest witness.
- BooleanFunctionsTest:199 skip(10).
- The FunctionCatalogue.Entry 3-arg constructor is used by tests only.
- engine/mapping PIT sits at its floor, and ModelCheck:79 flips between runs.
- The carried list of #205.
open: #222 (observation request, deferred past milestone 1), #191 (optional), #129 (ADR wording, owner).
rule (owner, 2026-11-10): every grilling round goes through the `grilling-adviser` (Fable 5.2) before the owner (`AGENTS.md`).
next action:
1. Owner: merge the `gate/m1` PR.
2. Architect: the cleanup ticket (the lists above, F-51 first as its own class-B ticket with a red case).
3. The Rail8 rename (R8, rail8.dev): docs first; packages at embedding (ADR).
4. Grill jq/jqj shared layers: limits/host SPI, the Class-File tier, the JSON tokenizer, tooling; and where XPath 3.1 lives.
5. Next spec from GATE "After milestone 1": JSON output (ADR-0008), via grilling.
