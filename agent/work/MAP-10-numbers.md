# MAP-10 numbers
Date 2026-11-16; commit 71c3b69e; machine 4 cores, 15 GB RAM.
Form used: ./mvnw -f mapping/pom.xml at repo root, each call behind flock /tmp/rail8-maven.lock.
1. ./mvnw -B -ntp -f mapping/pom.xml -DskipTests install -> VERIFIED_PASS exit 0, 23 s (MAP-10-install.log)
2. ./mvnw -B -ntp -f mapping/pom.xml -pl engine/<m> test-compile org.pitest:pitest-maven:mutationCoverage -Dpit.skip=false
| module | evidence | tests | gen | killed | surv | timeout | nocov | score | PIT time | wall |
| engine/structure | VERIFIED_PASS | 19 classes / 38 runs | 33 | 33 | 0 | 0 | 0 | 100% (strength 100%) | 12 s | 17 s |
| engine/structure-import | EXECUTED_FAIL | - | - | - | - | - | - | - | - | 9 s |
structure: survivors 0 (mutations.xml: 33 mutations, 0 SURVIVED); line coverage of mutated classes 102/104 (98%).
structure-import: exit 1: "126 tests did not pass without mutation when calculating line coverage. Mutation testing
requires a green suite." (JsonSchemaReaderTest cases listed as SEVERE, no cause printed). Pit.skip is lifted (pom line 19
of engine/structure-import/pom.xml sets pit.skip=true; -D overrides it). Logs: agent/work/MAP-10-pit-<name>.log
git status --short mapping/ : empty.

## Second attempt, engine/structure-import (corpus property handed to PIT's JVMs)
Command as run: ./mvnw -B -ntp -f mapping/pom.xml -pl engine/structure-import test-compile
 org.pitest:pitest-maven:mutationCoverage -Dpit.skip=false -DjvmArgs=-Drail8.corpus=/home/user/flottila/mapping/corpus
Log: agent/work/MAP-10-pit-structure-import-2.log. The first attempt's EXECUTED_FAIL above stands as it is.
| structure-import | VERIFIED_PASS | 5425 runs | gen 625 | killed 598 | surv 27 | timeout 2 | nocov 0 | 96% | 78 s | 83 s |
(killed 598 includes the 2 timed out; mutations.xml: 596 KILLED, 2 TIMED_OUT, 27 SURVIVED.) Line coverage 1628/1636 (99%).
Survivors per class (package dev.rail8.mapping.structure.importer.internal unless noted):
 XsdSubsetCheck 7, WsdlCheck 7, JsonSchemaReader 6, XsdReader 3, WsdlReader 1, BundleCheck 1, AttributeCheck 1,
 importer.StructureReader 1.
Ten lines with most survivors: every surviving line has 1 survivor (27 lines); first ten by sort: XsdSubsetCheck 925, 838,
 747, 668, 667, 665, 541; XsdReader 644, 623, 410.
