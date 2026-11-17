# Handoff

state: STOPPED 2026-11-17 on the owner's stop request, relayed by his other session ("Rail8 projekt restructuring"); it was not typed in this
  session, which reported to him and waits. Start nothing from the old order: the way of working is being restructured (RELAYED below).
last commit: the state saved at the stop. Before it: MAP-12b (the ratchet check runs on GitHub), FIX-265 (replay is out of the code).
DONE, on main: replay out of code and documents (FIX-262 to 267, 275), FIX-240, 270, 280; MAP-9, 10, 12a, 12b, 14; BRAND-6; D-156 to D-164.
UNFINISHED 1 - FIX-268 [G]: check 7 and condition 6 measured on 77b2c587, every part VERIFIED_PASS (agent/work/FIX-268-evidence.txt, logs
  beside it, ci run 255 green). NOT judged: no gate-reviewer ran; HANDOVER.md row 7 and bench/RESULTS.md unwritten - drafts agent/wip/FIX-268-close.py.
UNFINISHED 2 - MAP-11 [B]: the 90/90 floor (three poms) is NOT on main: agent/wip/MAP-11.patch, with what steps 0 and 1 observed. Steps 2, 3 not done.
WAS ABOUT TO START: FIX-269 [B] (facts only: agent/work/FIX-269-scout.md); after it FIX-276, 277, CONSIDER-17, 278; MAP-12c, 13, 15, MAP-1; FIX-271-273.
RELAYED with the stop, the owner's, not confirmed in this session, NO CI CHANGED: per change only the build with unit and integration tests and
  the sign-off check will block; coverage floors, no-decrease, mutation, the second Postgres, Windows and benchmarks become a nightly report that
  blocks nothing. So MAP-11, 12c, 13, 15 are not to be built as their rows say. GitHub Issues replace agent/BACKLOG.md; one task per fresh session.
  ASK HIM: mapping/scripts/ratchet-enforced-from holds 2026-11-25 - from that day MAP-12b's check fails mapping-ci's backend job on a decrease.
MAPPING: D-153 stands until MAP-1's design is accepted - root Design rules do not bind mapping/; nothing outside mapping/ depends on it. D-159.
Build: mapping `./mvnw -B -ntp -f mapping/pom.xml -Pbench clean verify` (2.5 min); platform `mvn -B -ntp -f platform/pom.xml clean verify -Pit` (26 min).
OWNER: applies agent/patches/BRAND-3-steering.patch and FIX-262-claude-replayable.patch; answers MAP-16, ISSUE-8, 9 (rest), 10, 11 (2 to 6),
  CONSIDER-27; About text, Pages and DNS for `rail8.dev`, archiving `subapavel/flottila-mapping` - never a session. D-110 open.
TRAPS: `-Dtest=` on runtime OOMs; one Maven at a time (`flock /tmp/rail8-maven.lock`); a container restart kills dockerd, Postgres, agents; fetch first.
open issues: ISSUE-1, 6, 8, 9, 10, 11. next free ids: D-166, FIX-282, BRAND-9, OPS-13, CONSIDER-31, ISSUE-14, F-61, MAP-17.
