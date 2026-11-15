#!/usr/bin/env python3
"""The Flottila -> Rail8 rename (BRAND-1, D-148, D-149), as one repeatable tool.

Subcommands, each idempotent (a second run changes nothing) and each with --dry-run:
  moves        commit A: `git mv` of every directory in PATH_MAP, then the path strings in the living documents
  identifiers  commit B: `git mv` of the io/flottila package directories and bin/flottila, then IDENTIFIER_MAP
  wire         commit C: persisted and wire names, one entry per literal (WIRE_MAP)
  report       only the final report: every remaining occurrence of the old name outside EXCLUDED, tagged

--root <dir> limits a subcommand to a subtree of the repository (BRAND-3 runs it on mapping/).
The tool never touches .git/, target/ or a file git does not track. Python 3 standard library only.

Usage (from anywhere inside the repository):
  python3 agent/tools/rail8_rename.py moves --dry-run
  python3 agent/tools/rail8_rename.py moves
  python3 agent/tools/rail8_rename.py report --summary
"""
from __future__ import annotations

import argparse
import fnmatch
import re
import subprocess
import sys
from collections import Counter, defaultdict
from pathlib import Path

# ---------------------------------------------------------------------------------------------------------------------
# Data. Paths are repository-relative, POSIX, as `git ls-files` prints them.
# ---------------------------------------------------------------------------------------------------------------------

# Commit A (D-149): old -> new, each one `git mv` of the whole entry. Order matters only for the parent pom, which moves
# after the modules so that platform/ exists.
PATH_MAP: list[tuple[str, str]] = [
    ("flottila-api", "platform/api"),
    ("flottila-core", "platform/core"),
    ("flottila-runtime", "platform/runtime"),
    ("flottila-testkit", "platform/testkit"),
    ("flottila-schemas", "platform/schemas"),
    ("flottila-cli", "platform/cli"),
    ("flottila-sim", "platform/sim"),
    ("flottila-archtest", "platform/archtest"),
    ("flottila-components", "platform/components"),
    ("flottila-steps", "platform/steps"),
    ("pom.xml", "platform/pom.xml"),
    ("packages/demo", "examples/demo"),
    ("prompts", "agent/prompts"),
    ("deploy/claude-cloud", "agent/cloud"),
    ("PROGRESS.md", "docs/archive/PROGRESS-until-2026-11-03.md"),
]

# Directories whose tracked entries move one by one into a directory that already exists (bench/ holds run.sh, bench.py,
# RESULTS.md). Every tracked entry of the source must be listed here; the tool refuses to guess an unlisted one.
MERGE_MAP: dict[str, tuple[str, list[str]]] = {
    "flottila-bench": ("bench", ["pom.xml", "src"]),
}

# The old module directory -> its artifactId (for `-pl`). The artifactIds do not change in commit A.
MODULE_ARTIFACT: dict[str, str] = {
    "flottila-api": "flottila-api",
    "flottila-core": "flottila-core",
    "flottila-runtime": "flottila-runtime",
    "flottila-testkit": "flottila-testkit",
    "flottila-schemas": "flottila-schemas",
    "flottila-cli": "flottila-cli",
    "flottila-sim": "flottila-sim",
    "flottila-archtest": "flottila-archtest",
    "flottila-bench": "flottila-bench",
    "flottila-components": "flottila-components",
    "flottila-steps": "flottila-steps",
    "flottila-components/http": "flottila-component-http",
    "flottila-components/mock": "flottila-component-mock",
    "flottila-components/sftp": "flottila-component-sftp",
    "flottila-steps/validate": "flottila-step-validate",
    "flottila-steps/xslt": "flottila-step-xslt",
}
# `-pl <dir>` becomes `-pl :<artifactId>` (the packet's preference; `mvn -pl :flottila-sim -am -q validate` accepted it,
# BRAND-1 A). False would write the new directory instead.
PL_BY_ARTIFACT = True

# ---------------------------------------------------------------------------------------------------------------------
# Commit B (D-149): identifiers. Applied in this order, after the masks below; no replacement contains the old name, so a
# second run finds nothing to change.
# ---------------------------------------------------------------------------------------------------------------------

# Directory moves of commit B: every `<module>/src/<set>/java/io/flottila` and `<module>/src/<set>/resources/io/flottila`
# becomes `.../io/rail8` (`git mv` of the whole directory), and the launcher.
PACKAGE_DIR = re.compile(r"^(.*/src/[^/]+/(?:java|resources))/io/flottila/")
PACKAGE_DIR_NEW = "io/rail8"
FILE_MOVES: list[tuple[str, str]] = [("bin/flottila", "bin/rail8")]

_B = r"(?<![A-Za-z0-9_])"     # not inside a longer word
_BL = r"(?<![a-z0-9_])"       # not after a lowercase letter or digit (`-Dflottila.it.postgres` still matches)
_E = r"(?![A-Za-z0-9_])"      # not followed by a word character

# (class, pattern, replacement), longest token first. Each pattern is no broader than the strings it renames.
IDENTIFIER_MAP: list[tuple[str, str, str]] = [
    # Maven coordinates (the groupId falls under "package root")
    ("coordinates", r"<artifactId>flottila</artifactId>", "<artifactId>rail8-parent</artifactId>"),
    ("coordinates", _BL + r"flottila-(component-http|component-mock|component-sftp|step-validate|step-xslt|components"
                          r"|archtest|component|mapping(?=-)|runtime|schemas|testkit|control|bench|steps|step|core|api"
                          r"|cli|sim|sap|all)" + _E,
     lambda m: "rail8" if m.group(1) == "all" else "rail8-" + m.group(1)),
    ("coordinates", r"`flottila-`", "`rail8-`"),
    ("image name", _BL + r"flottila-(stack|quickstart)(?=:local)", r"rail8-\1"),
    ("file name", _BL + r"flottila-extension\.json", "rail8-extension.json"),
    # build properties
    ("property", _BL + r"flottila\.(coverage\.line|coverage\.branch|mutation\.threshold|it\.postgres|coverage)" + _E,
     r"rail8.\1"),
    # the Java package root, in code, in strings, in resource paths and in path segments
    ("package root", r'"io", "flottila"', '"io", "rail8"'),
    ("package root", _B + r"io([./])flottila" + _E, r"io\1rail8"),
    # environment keys, the concatenated prefix "FLOTTILA_SECRET_" and a bare FLOTTILA_ included
    ("env key", _B + r"FLOTTILA_", "RAIL8_"),
    # thread, timer and temp-file name prefixes (the node ids "flottila-test" / "flottila-validate" are masked, commit C)
    ("thread/temp prefix", _BL + r"flottila-(?=(?:holder-force-|holder-|test-close|test-|validate-|worker-|child-|clock"
                                 r"|retention|admin|stop|shutdown|superseded|peer-serve|lease-|http-|sftp-|cpu-|ledger-"
                                 r"|node-bench-|stream-heap-|blob-|setup\])|\*\.body)", "rail8-"),
    ("thread/temp prefix", r'\.name\("flottila-" \+ ', '.name("rail8-" + '),
    ("thread/temp prefix", r'\.name\("flottila"\)', '.name("rail8")'),
    # the CLI: launcher, message prefix, command names in text
    ("cli", _BL + r"bin/flottila" + _E, "bin/rail8"),
    ("cli", r'(?<=startsWith\(")flottila(?=:)', "rail8"),
    ("cli", _B + r"flottila(?=: )", "rail8"),
    ("cli", _B + r"flottila(?= (?:(?:validate|test|deploy|lock|help)\b|<command>)|`|\})", "rail8"),
    # identifiers that contain the name
    ("identifier", r"(?<=Logger )flottila(?= = )", "rail8"),
    ("identifier", _B + r"flottila(?=\.(?:addHandler|removeHandler)\()", "rail8"),
    ("identifier", _B + r"flottila(?=[A-Z])", "rail8"),
    # the product name in text, in project names and inside identifiers (`binFlottilaValidatesTheDemo`)
    ("product name", r"Flottila", "Rail8"),
    ("product name", _B + r"flottila(?= jars)", "rail8"),
]

# The literals commit B must leave as they are (commit C renames them: the scout's lists 1 and 2, BRAND-1 packet "Not in
# B"). (file globs, pattern, reason). A match is masked before IDENTIFIER_MAP runs; the report names them as [C].
ANYWHERE = ("*",)   # a literal that cannot mean anything else: kept in code, tests and documents alike
WIRE_KEEP: list[tuple[tuple[str, ...], str, str]] = [
    (ANYWHERE, r"flottila-journal-v1|flottila-spool-v1|flottila-peer-v1", "HKDF info labels"),
    (ANYWHERE, r"flottila-package-content 1", "package content digest input"),
    (ANYWHERE, r"flottila-flow-hash", "flow hash input"),
    (ANYWHERE, r"written by flottila lock", "package.lock header text"),
    (ANYWHERE, r"flottila journal loss v1", "journal-loss file text"),
    (ANYWHERE, r"urn:flottila:schema:", "schema $id URN and base URI"),
    (ANYWHERE, r"flottila_work", "NOTIFY channel"),
    (ANYWHERE, r"pg_logical_emit_message\(true, 'flottila'", "WAL message prefix"),
    (ANYWHERE, r"flottila:poller:", "Valkey key prefix"),
    (ANYWHERE,
     r'"flottila-" \+ role|flottila-(?:intake|work|membership|listen|<role>)@',
     "PG application_name prefix"),
    (ANYWHERE, r"flottila-bench-sampler", "PG application_name of the bench sampler"),
    (ANYWHERE, r"X-Flottila-Actor", "HTTP header, stored as the audit actor"),
    (ANYWHERE, r'io\.flottila\.(?:Step|CpuSamplerDrain)(?![\w])', "JFR event names"),
    (("platform/core/*", "platform/runtime/*"),
     r'@Label\("Flottila (?:Step|CPU Sampler Drain)"\)'
     r'|@Category\("Flottila"\)|List\.of\("Flottila"\)|category Flottila|recording\.setName\("flottila-cpu-sampler"\)',
     "JFR event names, labels, categories, recording name"),
    (("platform/runtime/*/NodeConfig.java",),
     r'(?<=_PG_DATABASE", )"flottila"|(?<=_PG_USER", )"flottila"|(?<=_S3_PREFIX", )"flottila"|(?<=\}) \(flottila\)', "NodeConfig defaults: database, user, S3 prefix"),
    (("platform/cli/*",), r'"flottila-(?:test|validate)"', "node ids of the CLI's in-process nodes"),
    (("platform/runtime/*/Retention.java",), r'the bytes of "flottila"', "Retention.LOCK_KEY, ASCII of the name"),
    # deploy/, the compose files, bench/run.sh, ci.yml: database, role, password, bucket, volume, service name, network
    # alias, container user, /var/lib/flottila, /opt/flottila
    (ANYWHERE,
     r"/var/lib/flottila|/opt/flottila|flottila-data|flottila-pg(?![\w-])|flottila-postgres|flottila_bench"
     r"|flottila-bench-clickhouse|password=\"flottila-bench\"|http://flottila(?=:)", "deploy names and default paths"),
    (("deploy/compose/docker-compose.yml", "docker-compose.yml", "deploy/quickstart/Dockerfile",
      "platform/runtime/*/ComposeStackTest.java"),
     r"(?<![\w/.:])flottila(?![\w-])(?! deploy)", "compose: database, user, password, S3 identity, bucket, service"),
    ((".github/workflows/ci.yml",), r"ROLE flottila|PASSWORD 'flottila'|DATABASE flottila OWNER flottila", "CI role"),
    (("bench/run.sh",), r"\(flottila\)|:=flottila\}", "bench role and password defaults"),
    (("bench/*",), r'(?<="user", )"flottila"|(?<="password", )"flottila"|(?<="--db", )"flottila"|contains\("flottila"\)'
                   r"|\"'flottila'\"", "bench database, role and password"),
    (("deploy/compose/README.md",), r"defaults to flottila|\(default `flottila`\)|bucket `flottila`", "documents a C name"),
    (("agent/NOTES.md",), r"role flottila|db flottila_bench|create role flottila login createdb password 'flottila'",
     "documents a C name"),
    (("HANDOVER.md", "deploy/compose/README.md"), r"`flottila` \(the node\)|compose-flottila-1",
     "documents a C name (service, container)"),
    (("agent/BACKLOG.md",), r"depends_on kafka on `flottila`", "documents a C name (service)"),
    (("docs/QUICKSTART.md",), r"docker compose logs flottila", "documents a C name (service)"),
    # test-only names (scout list 1, last line, and the same class: buckets, data dirs, hosts, users, roles, databases)
    (ANYWHERE,
     r"/srv/flottila|/etc/flottila/|/run/secrets/flottila-master|/tmp/flottila|flottila-archives(?:-it)?"
     r"|flottila-(?:node|restore|ledger)-it|flottila_orders|flottila_ha|db\.flottila\.test|flottila-1\.internal"
     r"|CN=Flottila|FLOTTILA-PAYLOAD-MARKER|redis://flottila(?=:)|http://flottila(?=:)", "test-only name"),
    (("platform/*",),
     r'(?<="user", )"flottila"|(?<=put\("user", )"flottila"|assertEquals\("flottila", (?:options|s3)|USER = "flottila"'
     r'|sftp://flottila@|user: flottila|NodeConfig\.Pg\("[^"]*", \d+, "flottila"(?:, "flottila")?'
     r'|(?<=Arbitraries\.of\("intake", )"flottila"|writePrivateKey\(key, "flottila-test"|defaults to flottila',
     "test-only user, role, database; expectations of a NodeConfig default"),
]

# Never rewritten by any subcommand and not reported: names that are not ours to change (the repository and its checkout,
# renamed by the owner) and the names of modules that no longer exist. (file globs, pattern, reason).
KEPT_NAMES: list[tuple[tuple[str, ...], str, str]] = [
    (("*",), r"subapavel/flottila(?:-mapping)?(?![\w-])", "repository name"),
    (("*",), r"(?<![\w-])flottila-mapping(?![\w-])", "the mapping repository's name"),
    (("*",), r"(?:/home/user|~)/flottila(?![\w-])", "the checkout directory (the repository's name)"),
    (("*",), r"flottila-m1|flottila-proto", "modules that no longer exist, named as they were"),
    (("agent/cloud/README.md",), r"environment `flottila`|\| Name \| `flottila` \|", "the cloud environment's name"),
]

# Files `identifiers` leaves untouched (instruction, BRAND-1 B): the coverage guard's fixtures name synthetic modules.
B_UNTOUCHED: list[str] = ["ci/test_coverage_check.py"]

# Text kinds the script rewrites; any other tracked file is not classified and is never rewritten.
TEXT_SUFFIXES = {".java", ".md", ".yaml", ".yml", ".xml", ".json", ".py", ".sh", ".txt", ".tsv", ".xsl", ".xsd", ".svg",
                 ".sql", ".diff", ".patch", ".dockerignore"}
TEXT_NAMES = {"Dockerfile", "LICENSE", "NOTICE", ".gitignore", ".gitattributes", ".dockerignore", "flottila", "rail8"}

# Commit C: (class, files, old, new), one entry per literal of the scout's wire list (agent/work/BRAND-1-scout.md lists 1
# and 2, the architect's rulings for C), each with the files it lives in (repository globs). `old` is a literal; where a
# bare name is only a name in one position (a compose service key, a network alias) it is a pattern anchored to that
# position. `flottila` -> `rail8`, `Flottila` -> `Rail8`, `FJ..` -> `RJ..`, version digits kept; no alias is kept (D-148).
# Within one file the longest literal applies first. The golden constants recomputed from these literals (PeerKnownAnswerTest,
# ReplFramesTest) are not here: they are computed outside the code (agent/work/BRAND-1-C-golden.py) and edited by hand.
_RT = "platform/runtime/src/main/java/io/rail8/runtime/"
_RTT = "platform/runtime/src/test/java/io/rail8/runtime/"
_SCH = "platform/schemas/src/main/"
_CLI = "platform/cli/src/"
_BENCH = "bench/src/main/java/io/rail8/bench/"
_BENCHT = "bench/src/test/java/io/rail8/bench/"
_COMPOSE = ("deploy/compose/docker-compose.yml", "docker-compose.yml")
_STEP_EVENT = "platform/core/src/main/java/io/rail8/core/record/StepEvent.java"
_STEP_EVENT_TEST = "platform/core/src/test/java/io/rail8/core/flow/FlowBuilderStepEventTest.java"
_CPU = _RT + "node/CpuSampler.java"
_NODE_CONFIG = _RT + "node/NodeConfig.java"
_PG_TESTS = (_RTT + "node/NodeConfigTest.java", _RTT + "node/NodeConfigPoolWaitTest.java", _RTT + "node/NodeConfigPoolsTest.java",
             _RTT + "node/NodeConfigPoolsInProcessTest.java")
_BENCH_MAINS = tuple(_BENCH + m for m in ("BenchMain.java", "LedgerMain.java", "NodeBenchMain.java", "StreamHeapMain.java"))
_NOTES = "agent/NOTES.md"
WIRE_MAP: list[tuple[str, tuple[str, ...], object, str]] = [
    # hash and KDF inputs
    ("HKDF info label", (_RT + "journal/RecordFormat.java", _RTT + "journal/JournalRecordFormatTest.java"),
     "flottila-journal-v1", "rail8-journal-v1"),
    ("HKDF info label", (_RT + "blob/SpoolCipher.java",), "flottila-spool-v1", "rail8-spool-v1"),
    ("HKDF info label", (_RT + "journal/peer/PeerHandshake.java", _RTT + "journal/peer/PeerKnownAnswerTest.java",
                         _RTT + "journal/peer/ScriptedPeer.java"), "flottila-peer-v1", "rail8-peer-v1"),
    ("digest input", (_SCH + "java/io/rail8/schemas/Package.java", _SCH + "resources/schemas/lock.schema.json",
                      "platform/schemas/src/test/java/io/rail8/schemas/PackageDependenciesTest.java",
                      _CLI + "test/java/io/rail8/cli/DepsFixture.java", _RTT + "deploy/PackageDraft.java"),
     "flottila-package-content 1", "rail8-package-content 1"),
    ("digest input", (_RT + "deploy/FlowHash.java", _RTT + "deploy/FlowHashTest.java"), "flottila-flow-hash 2", "rail8-flow-hash 2"),
    # the advisory lock key: the ASCII bytes of the name (the architect's ruling for C)
    ("advisory lock key", (_RT + "store/Retention.java",), "0x666c6f7474696c61L", "0x7261696C38000000L"),
    ("advisory lock key", (_RT + "store/Retention.java",), 'the bytes of "flottila" (D-088)',
     'the ASCII bytes of "rail8" in the high five bytes, zero below (D-088)'),
    # on-disk magics of the journal segment and identity files (FJ -> RJ)
    ("segment magic", (_RT + "journal/SegmentFormat.java", _RTT + "journal/peer/ReplWire.java"), "0x464A5331", "0x524A5331"),
    ("segment magic", (_RT + "journal/SegmentFormat.java", _RTT + "journal/LargeBodyTest.java"), "0x464A5345", "0x524A5345"),
    ("segment magic", (_RT + "journal/SegmentFormat.java",), "0x464A4E31", "0x524A4E31"),
    ("segment magic", (_RT + "journal/SegmentFormat.java", _RTT + "journal/JournalSegmentRowsTest.java",
                       _RTT + "journal/peer/ReplWire.java"), "FJS1", "RJS1"),
    ("segment magic", (_RT + "journal/SegmentFormat.java", _RTT + "journal/LargeBodyTest.java"), "FJSE", "RJSE"),
    ("segment magic", (_RT + "journal/SegmentFormat.java", _RTT + "journal/JournalSegmentRowsTest.java",
                       _RTT + "journal/NodeJournalIT.java"), "FJN1", "RJN1"),
    ("segment magic", (_RTT + "journal/JournalStartLossTest.java",), "new byte[] {0x46, 0x4A, 0x53}",
     "new byte[] {0x52, 0x4A, 0x53}"),
    # text written to disk
    ("file text", (_CLI + "main/java/io/rail8/cli/Lock.java",), "written by flottila lock", "written by rail8 lock"),
    ("file text", (_RT + "journal/JournalStart.java",), "flottila journal loss v1", "rail8 journal loss v1"),
    # schema identity
    ("schema URN", (_SCH + "java/io/rail8/schemas/Schemas.java", _SCH + "resources/schemas/*.schema.json",
                    "platform/schemas/src/test/java/io/rail8/schemas/SchemasTest.java"), "urn:flottila:schema:", "urn:rail8:schema:"),
    # Postgres, Valkey, HTTP
    ("NOTIFY channel", (_RT + "queue/PgWorkSignals.java", _RTT + "journal/PgWorkSignalsIT.java", _RTT + "journal/TwoNodeWorkEventsIT.java",
                        _RTT + "NodePoolWaitIT.java", "docs/ARCHITECTURE.md", ".claude/agent-memory/test-author/MEMORY.md"),
     "flottila_work", "rail8_work"),
    ("WAL message prefix", (_RT + "store/PgIntake.java", _RTT + "PgPromotionLedgerIT.java", "docs/ARCHITECTURE.md"),
     "pg_logical_emit_message(true, 'flottila', '')", "pg_logical_emit_message(true, 'rail8', '')"),
    ("Valkey key prefix", (_RT + "lease/ValkeyPollerLeases.java", _RTT + "lease/ValkeyPollerLeasesIT.java", _RTT + "NodeIT.java",
                           _RTT + "deploy/DeploymentsPollerLeaseTest.java"), "flottila:poller:", "rail8:poller:"),
    ("application_name", (_RT + "node/Node.java", _RTT + "node/NodeConnectOptionsTest.java"), '"flottila-" + role', '"rail8-" + role'),
    ("application_name", (_RT + "node/Node.java", _RTT + "NodePoolRolesIT.java", _RTT + "node/NodeConnectOptionsTest.java",
                          "docs/ARCHITECTURE.md", ".claude/agent-memory/test-author/MEMORY.md"), "flottila-<role>@", "rail8-<role>@"),
    ("application_name", (_RTT + "NodePoolRolesIT.java", _RTT + "NodePoolWaitIT.java"), '"flottila-intake@"', '"rail8-intake@"'),
    ("application_name", (_RTT + "NodePoolRolesIT.java", _RTT + "NodePoolWaitIT.java"), '"flottila-work@"', '"rail8-work@"'),
    ("application_name", (_RTT + "NodePoolRolesIT.java",), '"flottila-membership@"', '"rail8-membership@"'),
    ("application_name", (_RTT + "NodePoolRolesIT.java",), "flottila-membership@<node>", "rail8-membership@<node>"),
    ("application_name", (_RTT + "NodePoolRolesIT.java",), '"flottila-listen@"', '"rail8-listen@"'),
    ("application_name", (_RTT + "NodePoolRolesIT.java",), "flottila-listen@<node>", "rail8-listen@<node>"),
    ("application_name", (_BENCH + "DbSampler.java",), '"flottila-bench-sampler"', '"rail8-bench-sampler"'),
    ("HTTP header", (_RT + "admin/AdminServer.java", _RTT + "admin/AdminServerDiscardTest.java", _RTT + "journal/LostWorkAdminIT.java",
                     "docs/ARCHITECTURE.md"), "X-Flottila-Actor", "X-Rail8-Actor"),
    # JFR: event names, labels, categories, the recording name
    ("JFR", (_STEP_EVENT, _STEP_EVENT_TEST, _RTT + "NodeIT.java", _RTT + "node/CpuSamplerIT.java", "docs/ARCHITECTURE.md"),
     "io.flottila.Step", "io.rail8.Step"),
    ("JFR", (_CPU,), '"io.flottila.CpuSamplerDrain"', '"io.rail8.CpuSamplerDrain"'),
    ("JFR", (_STEP_EVENT,), '@Label("Flottila Step")', '@Label("Rail8 Step")'),
    ("JFR", (_CPU,), '@Label("Flottila CPU Sampler Drain")', '@Label("Rail8 CPU Sampler Drain")'),
    ("JFR", (_STEP_EVENT, _CPU), '@Category("Flottila")', '@Category("Rail8")'),
    ("JFR", (_STEP_EVENT_TEST,), 'List.of("Flottila")', 'List.of("Rail8")'),
    ("JFR", (_STEP_EVENT_TEST,), "category Flottila;", "category Rail8;"),
    ("JFR", (_CPU,), 'recording.setName("flottila-cpu-sampler")', 'recording.setName("rail8-cpu-sampler")'),
    # NodeConfig defaults and their javadoc
    ("NodeConfig default", (_NODE_CONFIG,), '"RAIL8_PG_DATABASE", "flottila"', '"RAIL8_PG_DATABASE", "rail8"'),
    ("NodeConfig default", (_NODE_CONFIG,), '"RAIL8_PG_USER", "flottila"', '"RAIL8_PG_USER", "rail8"'),
    ("NodeConfig default", (_NODE_CONFIG,), '"RAIL8_S3_PREFIX", "flottila"', '"RAIL8_S3_PREFIX", "rail8"'),
    ("NodeConfig default", (_NODE_CONFIG,), '"RAIL8_DATA_DIR", "/var/lib/flottila"', '"RAIL8_DATA_DIR", "/var/lib/rail8"'),
    ("NodeConfig default", (_NODE_CONFIG,), "{@code RAIL8_PG_DATABASE} (flottila)", "{@code RAIL8_PG_DATABASE} (rail8)"),
    ("NodeConfig default", (_NODE_CONFIG,), "{@code RAIL8_PG_USER} (flottila)", "{@code RAIL8_PG_USER} (rail8)"),
    ("NodeConfig default", (_NODE_CONFIG,), "{@code RAIL8_S3_PREFIX} (flottila)", "{@code RAIL8_S3_PREFIX} (rail8)"),
    ("NodeConfig default", (_NODE_CONFIG,), "{@code RAIL8_DATA_DIR} (/var/lib/flottila)", "{@code RAIL8_DATA_DIR} (/var/lib/rail8)"),
    ("NodeConfig default", (_RTT + "node/NodeConfigTest.java",), "/var/lib/flottila", "/var/lib/rail8"),
    ("NodeConfig default", (_RTT + "node/NodeConfigTest.java",),
     'assertEquals("flottila", s3.prefix(), "RAIL8_S3_PREFIX defaults to flottila")',
     'assertEquals("rail8", s3.prefix(), "RAIL8_S3_PREFIX defaults to rail8")'),
    ("NodeConfig default", _PG_TESTS, '"flottila", "flottila"', '"rail8", "rail8"'),
    ("NodeConfig default", (_RTT + "node/NodeJdbcUrlTest.java",), '5432, "flottila", "node"', '5432, "rail8", "node"'),
    # the CLI's in-process node ids
    ("CLI node id", (_CLI + "main/java/io/rail8/cli/FlowTests.java",), '"flottila-test"', '"rail8-test"'),
    ("CLI node id", (_CLI + "main/java/io/rail8/cli/Validate.java",), '"flottila-validate"', '"rail8-validate"'),
    # the quickstart image
    ("container", ("deploy/quickstart/Dockerfile",), "--home-dir /var/lib/flottila flottila", "--home-dir /var/lib/rail8 rail8"),
    ("container", ("deploy/quickstart/Dockerfile",), "WORKDIR /opt/flottila", "WORKDIR /opt/rail8"),
    ("container", ("deploy/quickstart/Dockerfile",), "USER flottila", "USER rail8"),
    ("container", ("deploy/quickstart/Dockerfile",), "RAIL8_DATA_DIR=/var/lib/flottila", "RAIL8_DATA_DIR=/var/lib/rail8"),
    ("container", ("deploy/quickstart/Dockerfile", "deploy/compose/docker-compose.yml"), "/opt/flottila/rail8-runtime.jar",
     "/opt/rail8/rail8-runtime.jar"),
    ("container", ("deploy/quickstart/Dockerfile",), "docker-compose.yml service `flottila`", "docker-compose.yml service `rail8`"),
    # both compose files: database, role, password, S3 identity and bucket, service, network alias, volumes, mounts
    ("compose", _COMPOSE, "POSTGRES_DB: flottila", "POSTGRES_DB: rail8"),
    ("compose", _COMPOSE, "POSTGRES_USER: flottila", "POSTGRES_USER: rail8"),
    ("compose", ("docker-compose.yml",), "POSTGRES_PASSWORD: flottila", "POSTGRES_PASSWORD: rail8"),
    ("compose", ("docker-compose.yml",), "RAIL8_PG_PASSWORD: flottila", "RAIL8_PG_PASSWORD: rail8"),
    ("compose", ("deploy/compose/docker-compose.yml",), "${RAIL8_PG_PASSWORD:-flottila}", "${RAIL8_PG_PASSWORD:-rail8}"),
    ("compose", _COMPOSE, "pg_isready -U flottila -d flottila", "pg_isready -U rail8 -d rail8"),
    ("compose", ("deploy/compose/docker-compose.yml",), "${RAIL8_S3_ACCESS_KEY:-flottila}", "${RAIL8_S3_ACCESS_KEY:-rail8}"),
    ("compose", ("deploy/compose/docker-compose.yml",), '"name":"flottila"', '"name":"rail8"'),
    ("compose", ("deploy/compose/docker-compose.yml",), "RAIL8_S3_BUCKET: flottila", "RAIL8_S3_BUCKET: rail8"),
    ("compose", ("deploy/compose/docker-compose.yml",), "--admin http://flottila:8081", "--admin http://rail8:8081"),
    ("compose", ("docker-compose.yml",), "container_name: flottila-postgres", "container_name: rail8-postgres"),
    ("compose", ("docker-compose.yml",), "flottila-pg:", "rail8-pg:"),
    ("compose", _COMPOSE, "flottila-data:/var/lib/flottila", "rail8-data:/var/lib/rail8"),
    ("compose", _COMPOSE, re.compile(r"^(  )flottila-data:$"), r"\1rail8-data:"),
    ("compose", _COMPOSE, re.compile(r"^(\s+)flottila:$"), r"\1rail8:"),
    ("compose", _COMPOSE, re.compile(r"^(\s+- )flottila$"), r"\1rail8"),
    # the compose stack test: the same names as the compose file reads them
    ("compose", (_RTT + "ComposeStackTest.java",), '"flottila"', '"rail8"'),
    ("compose", (_RTT + "ComposeStackTest.java",), r'\"flottila\"', r'\"rail8\"'),
    ("compose", (_RTT + "ComposeStackTest.java",), '"flottila-data"', '"rail8-data"'),
    ("compose", (_RTT + "ComposeStackTest.java",), '"/var/lib/flottila"', '"/var/lib/rail8"'),
    ("compose", (_RTT + "ComposeStackTest.java",), '"http://flottila:8081"', '"http://rail8:8081"'),
    ("compose", (_RTT + "ComposeStackTest.java",), "flottila-data is a declared volume", "rail8-data is a declared volume"),
    ("compose", (_RTT + "ComposeStackTest.java",), "Dockerfile (flottila)", "Dockerfile (rail8)"),
    ("compose", (_RTT + "ComposeStackTest.java",), "the old pin on flottila", "the old pin on rail8"),
    ("compose", (_RTT + "ComposeStackTest.java",), "the alias flottila on", "the alias rail8 on"),
    # CI and the bench: role, database, password, container
    ("CI role", (".github/workflows/ci.yml",), "the flottila_bench database", "the rail8_bench database"),
    ("CI role", (".github/workflows/ci.yml",), "CREATE ROLE flottila LOGIN CREATEDB PASSWORD 'flottila';",
     "CREATE ROLE rail8 LOGIN CREATEDB PASSWORD 'rail8';"),
    ("CI role", (".github/workflows/ci.yml",), "CREATE DATABASE flottila OWNER flottila;", "CREATE DATABASE rail8 OWNER rail8;"),
    ("bench default", ("bench/run.sh",), "PGUSER (flottila), PGPASSWORD (flottila)", "PGUSER (rail8), PGPASSWORD (rail8)"),
    ("bench default", ("bench/run.sh",), "BENCH_DB (flottila_bench", "BENCH_DB (rail8_bench"),
    ("bench default", ("bench/run.sh",), '"${PGUSER:=flottila}"', '"${PGUSER:=rail8}"'),
    ("bench default", ("bench/run.sh",), '"${PGPASSWORD:=flottila}"', '"${PGPASSWORD:=rail8}"'),
    ("bench default", ("bench/run.sh",), '"${BENCH_DB:=flottila_bench}"', '"${BENCH_DB:=rail8_bench}"'),
    ("bench default", ("bench/run.sh",), 'name="flottila-bench-clickhouse"', 'name="rail8-bench-clickhouse"'),
    ("bench default", ("bench/run.sh",), 'password="flottila-bench"', 'password="rail8-bench"'),
    ("bench default", _BENCH_MAINS, '"db", "flottila_bench"', '"db", "rail8_bench"'),
    ("bench default", _BENCH_MAINS, '"user", "flottila"', '"user", "rail8"'),
    ("bench default", _BENCH_MAINS, '"password", "flottila"', '"password", "rail8"'),
    ("bench default", (_BENCHT + "BenchRunScriptOpenDbTest.java", _BENCHT + "DbSamplerCasesTest.java", _BENCHT + "DbSamplerTest.java"),
     "in flottila_bench", "in rail8_bench"),
    ("bench default", (_BENCHT + "LedgerMainTest.java", _BENCHT + "StreamHeapMainTest.java", _BENCHT + "NodeBenchMainRunTest.java"),
     '"--db", "flottila"', '"--db", "rail8"'),
    ("bench default", (_BENCHT + "LedgerMainTest.java", _BENCHT + "StreamHeapMainTest.java"), 'contains("flottila")', 'contains("rail8")'),
    ("bench default", (_BENCHT + "NodeBenchMainRunTest.java",), "contains(\"'flottila'\")", "contains(\"'rail8'\")"),
    # the living documents that name one of these (only the name; the wording is not touched)
    ("document", ("deploy/compose/README.md", "HANDOVER.md"), "`flottila` (the node)", "`rail8` (the node)"),
    ("document", ("deploy/compose/README.md",), "RAIL8_S3_ACCESS_KEY defaults to flottila", "RAIL8_S3_ACCESS_KEY defaults to rail8"),
    ("document", ("deploy/compose/README.md",), "(default `flottila`)", "(default `rail8`)"),
    ("document", ("deploy/compose/README.md",), "bucket `flottila`", "bucket `rail8`"),
    ("document", ("HANDOVER.md",), "compose-flottila-1", "compose-rail8-1"),
    ("document", ("agent/BACKLOG.md",), "depends_on kafka on `flottila`", "depends_on kafka on `rail8`"),
    ("document", ("docs/QUICKSTART.md",), "docker compose logs flottila", "docker compose logs rail8"),
    ("document", (_NOTES,), "role flottila has CREATEDB", "role rail8 has CREATEDB"),
    ("document", (_NOTES,), "db flottila_bench", "db rail8_bench"),
    ("document", (_NOTES,), "create role flottila login createdb password 'flottila'", "create role rail8 login createdb password 'rail8'"),
    # test-only names that mirror one of the literals above (ruling 3: a bucket, a data dir, a host, a user, a role, a database)
    ("test-only name", (_RTT + "NodeIT.java", _RTT + "journal/NodeJournalIT.java", _RTT + "deploy/S3ArchiveStoreTest.java",
                        _RTT + "deploy/S3ArchiveStoreIT.java", _RTT + "node/NodeConfigTest.java", _RTT + "node/NodeTest.java"),
     "flottila-archives", "rail8-archives"),
    ("test-only name", (_RTT + "NodeIT.java",), '"flottila-node-it"', '"rail8-node-it"'),
    ("test-only name", (_RTT + "RestoreIT.java",), '"flottila-restore-it"', '"rail8-restore-it"'),
    ("test-only name", (_BENCHT + "LedgerIT.java",), '"flottila-ledger-it"', '"rail8-ledger-it"'),
    ("test-only name", (_RTT + "deploy/SecretsEqualityTest.java", _RTT + "deploy/SecretsTest.java", _RTT + "node/NodeConfigTest.java"),
     '"/tmp/flottila', '"/tmp/rail8'),
    ("test-only name", (_RTT + "journal/MasterKeySourceEnvTest.java",), "/run/secrets/flottila-master", "/run/secrets/rail8-master"),
    ("test-only name", (_RTT + "journal/SchemaIT.java",), '"FLOTTILA-PAYLOAD-MARKER-', '"RAIL8-PAYLOAD-MARKER-'),
    ("test-only name", (_RTT + "node/NodeConfigJournalTest.java", _RTT + "node/NodeConfigTest.java"), '"/srv/flottila', '"/srv/rail8'),
    ("test-only name", (_RTT + "node/NodeConfigPeerTest.java",), '"flottila-1.internal"', '"rail8-1.internal"'),
    ("test-only name", _PG_TESTS[1:3] + (_RTT + "node/NodeConnectOptionsTest.java", _RTT + "node/NodeJdbcUrlTest.java"),
     '"/etc/flottila/', '"/etc/rail8/'),
    ("test-only name", (_RTT + "node/NodeConfigTest.java",), '"redis://flottila:"', '"redis://rail8:"'),
    ("test-only name", (_RTT + "node/NodeConfigTest.java",), '"http://flottila:"', '"http://rail8:"'),
    ("test-only name", (_RTT + "node/NodeConnectOptionsTest.java", _RTT + "node/NodeConnectOptionsCacheLimitTest.java"),
     '"flottila_orders"', '"rail8_orders"'),
    ("test-only name", (_RTT + "node/PgDurabilityTest.java",), '"flottila_ha"', '"rail8_ha"'),
    ("test-only name", (_RTT + "node/PgDurabilityTest.java",), 'Arbitraries.of("intake", "flottila")', 'Arbitraries.of("intake", "rail8")'),
    ("test-only name", (_RTT + "node/PgTlsIT.java",), "db.flottila.test", "db.rail8.test"),
    ("test-only name", (_RTT + "node/TestCertificates.java",), '"CN=Flottila ', '"CN=Rail8 '),
    ("test-only name", (_RTT + "deploy/DeploymentsSecretsTest.java",), '.put("user", "flottila")', '.put("user", "rail8")'),
    ("test-only name", (_RTT + "deploy/DeploymentsSecretsTest.java",), 'assertEquals("flottila", options.get("user")',
     'assertEquals("rail8", options.get("user")'),
    ("test-only name", ("platform/components/mock/src/test/java/io/rail8/components/mock/MockPollingContractTest.java",
                        "platform/components/sftp/src/test/java/io/rail8/components/sftp/SftpComponentTest.java"),
     '"user", "flottila"', '"user", "rail8"'),
    ("test-only name", ("platform/components/sftp/src/test/java/io/rail8/components/sftp/EmbeddedSftp.java",),
     'USER = "flottila"', 'USER = "rail8"'),
    ("test-only name", ("platform/components/sftp/src/test/java/io/rail8/components/sftp/SftpComponentTest.java",),
     '"sftp://flottila@', '"sftp://rail8@'),
    ("test-only name", ("platform/components/sftp/src/test/java/io/rail8/components/sftp/SftpConsumerEdgeTest.java",),
     'writePrivateKey(key, "flottila-test"', 'writePrivateKey(key, "rail8-test"'),
    ("test-only name", (_CLI + "test/java/io/rail8/cli/TestCommandTest.java", _CLI + "test/resources/packages/feed/endpoints/drop.yaml",
                        _CLI + "test/resources/packages/feed/endpoints/drop-keyed.yaml"), "user: flottila", "user: rail8"),
    # the coverage guard's own test: synthetic module names and package paths (ruling 8, left untouched by B)
    ("coverage fixture", ("ci/test_coverage_check.py",), '"flottila-', '"rail8-'),
    ("coverage fixture", ("ci/test_coverage_check.py",), '"io/flottila/x', '"io/rail8/x'),
    ("coverage fixture", ("ci/test_coverage_check.py",), '"io" / "flottila" / "x"', '"io" / "rail8" / "x"'),
]

# Never rewritten, in any commit (BRAND-1 packet "Exclusions"). History keeps the old name as written (D-148).
EXCLUDED: list[str] = [
    "docs/PROGRESS.md",
    "docs/DECISIONS.md",
    "docs/archive/**",
    "docs/findings/**",
    "docs/review/**",
    "agent/packets/**",
    "agent/archive/**",
    "agent/work/**",
    "agent/review/**",
    "agent/patches/**",
    "agent/wip/**",
    "issues/**",
    "bench/RESULTS.md",
    "PROGRESS.md",                      # before commit A; it moves to docs/archive/ unedited
    "deploy/postgres/V*.sql",           # applied migrations stay byte-identical
    "agent/tools/rail8_rename.py",      # this script
]
# In agent/BACKLOG.md every table row whose status cell (the fourth) starts with `done` is history, and so is every
# BRAND-* row (it names the old name as the thing it renames; the architect words it).
BACKLOG = "agent/BACKLOG.md"
# In docs/ARCHITECTURE.md the changelog table (from `## Changelog` to the next heading) is history: its rows keep the
# names they were written with.
ARCHITECTURE = "docs/ARCHITECTURE.md"
# Every github.com/subapavel/flottila URL stays (the owner renames the repository), `flottila-mapping` in one too.
KEPT_URL = re.compile(r"(?:https?://)?github\.com/subapavel/flottila[^\s)\]>`'\"|]*")

# The living documents whose path strings commit A rewrites (paths after the moves). The packet's list, plus the
# documents it does not name that cite a moved path (marked +).
LIVING_DOCS: list[str] = [
    "CLAUDE.md", "AGENTS.md", "README.md", "HANDOVER.md",
    "docs/WORKFLOW.md", "docs/ARCHITECTURE.md", "docs/CORE-REWORK.md", "docs/OPERATIONS-BRIEF.md", "docs/GATE.md",
    "docs/QUICKSTART.md", "docs/PROTOTYPE-VS-ARCHITECTURE.md",
    "agent/NOTES.md", "agent/HANDOFF.md", BACKLOG,
    ".claude/agents/*.md", ".claude/skills/**", ".claude/agent-memory/**",
    "deploy/compose/README.md",          # +
    "agent/cloud/README.md",             # + (was deploy/claude-cloud/README.md)
    "platform/schemas/README.md",        # +
    "agent/prompts/*",                   # +
]

# ---------------------------------------------------------------------------------------------------------------------
# Path-string rules (commit A). Each is (class, pattern, replacement). No rule is broader than the strings it fixes:
# a module name without a following `/` is an artifactId in commit A and stays.
# ---------------------------------------------------------------------------------------------------------------------

_NOT_AFTER = r"(?<![\w.-])"   # not inside a longer name or a dotted/hyphenated word; a preceding `/` is allowed (`../`)


def _pl(match: re.Match) -> str:
    items = []
    for item in match.group(3).split(","):
        bang = "!" if item.startswith("!") else ""
        name = item[len(bang):]
        if name in MODULE_ARTIFACT:
            name = ":" + MODULE_ARTIFACT[name] if PL_BY_ARTIFACT else map_path(name)
        items.append(bang + name)
    return match.group(1) + match.group(2) + ",".join(items)


def _after_root_relative(text: str, start: int) -> bool:
    """A repository-relative `packages/demo` / `prompts/` starts a token or follows `../` or `./`; `/packages/demo`
    (a URL path or a container path) is not a repository path."""
    before = text[max(0, start - 3):start]
    if before.endswith("../") or before.endswith("./"):
        return True
    return start == 0 or not re.match(r"[\w./-]", text[start - 1])


def _root_relative(old: str, new: str):
    pattern = re.compile(re.escape(old))

    def replace(match: re.Match) -> str:
        return new if _after_root_relative(match.string, match.start()) else match.group(0)
    return pattern, replace


PATH_RULES: list[tuple[str, re.Pattern, object]] = [
    ("module dir", re.compile(_NOT_AFTER + r"flottila-(api|core|runtime|testkit|schemas|cli|sim|archtest)/"),
     r"platform/\1/"),
    ("aggregator dir", re.compile(_NOT_AFTER + r"flottila-(components|steps)/(?=http\b|mock\b|sftp\b|validate\b|xslt\b"
                                                r"|pom\.xml|src/|target/|\*|\{)"), r"platform/\1/"),
    ("bench dir", re.compile(_NOT_AFTER + r"flottila-bench/"), "bench/"),
    ("-pl", re.compile(r"(?<![\w-])(-pl(?:\s+|=))(['\"]?)([!\w./,-]+)"), _pl),
    ("demo", *_root_relative("packages/demo", "examples/demo")),
    ("prompts", *_root_relative("prompts/", "agent/prompts/")),
    ("claude-cloud", *_root_relative("deploy/claude-cloud", "agent/cloud")),
]

OLD_NAME = re.compile(r"flottila", re.IGNORECASE)

# ---------------------------------------------------------------------------------------------------------------------


def map_path(path: str) -> str:
    """A repository path under the path map's new layout (longest old prefix wins); unchanged if no entry covers it."""
    entries = list(PATH_MAP) + [(src, dst) for src, (dst, _) in MERGE_MAP.items()]
    for old, new in sorted(entries, key=lambda e: -len(e[0])):
        if path == old or path.startswith(old + "/"):
            return new + path[len(old):]
    return path


def git(root: Path, *args: str, check: bool = True) -> str:
    out = subprocess.run(["git", *args], cwd=root, capture_output=True, text=True)
    if check and out.returncode != 0:
        raise SystemExit(f"git {' '.join(args)} failed: {out.stderr.strip()}")
    return out.stdout


def tracked(root: Path) -> list[str]:
    return [p for p in git(root, "ls-files", "-z").split("\0") if p]


def _glob(path: str, pattern: str) -> bool:
    if pattern.endswith("/**"):
        return path.startswith(pattern[:-3] + "/")
    return fnmatch.fnmatchcase(path, pattern) and ("/" in pattern or "/" not in path)


def excluded(path: str) -> bool:
    return any(_glob(path, p) for p in EXCLUDED)


def living(path: str) -> bool:
    return any(_glob(path, p) for p in LIVING_DOCS) and not excluded(path)


def in_scope(path: str, scope: str | None) -> bool:
    return scope is None or path == scope or path.startswith(scope.rstrip("/") + "/")


def read_text(file: Path) -> str | None:
    return _decoded(file.read_bytes())


def _decoded(data: bytes) -> str | None:
    if b"\0" in data:
        return None
    try:
        return data.decode("utf-8")
    except UnicodeDecodeError:
        return None


def _status_done(line: str) -> bool:
    if not line.startswith("|"):
        return False
    cells = re.split(r"(?<!\\)\|", re.sub(r"`[^`]*`", "``", line))   # a `|` inside a code span is not a cell border
    return len(cells) > 4 and cells[4].strip().startswith("done") and cells[1].strip() not in ("Id", "")


def _brand_row(line: str) -> bool:
    return line.startswith("| BRAND-")


def history_lines(path: str, lines: list[str]) -> set[int]:
    """The 0-based indexes of the lines of `path` that are history inside a living document: never rewritten."""
    if path == BACKLOG:
        return {i for i, line in enumerate(lines) if _status_done(line) or _brand_row(line)}
    if path == ARCHITECTURE:
        out: set[int] = set()
        inside = False
        for i, line in enumerate(lines):
            if line.startswith("#"):
                inside = line.strip() == "## Changelog"
            elif inside:
                out.add(i)
        return out
    return set()


def _matching(entries, path: str) -> list[tuple[re.Pattern, str]]:
    """The patterns of `entries` ((globs, pattern, reason)) that apply to `path`."""
    return [(_compiled(pattern), reason) for globs, pattern, reason in entries
            if any(g == "*" or _glob(path, g) for g in globs)]


_COMPILED: dict[str, re.Pattern] = {}


def _compiled(pattern: str) -> re.Pattern:
    if pattern not in _COMPILED:
        _COMPILED[pattern] = re.compile(pattern)
    return _COMPILED[pattern]


class Masks:
    """What a line keeps for one file: the repository URLs and KEPT_NAMES always, WIRE_KEEP (commit C's literals) when
    `wire` is set. Masked text is replaced by NUL-delimited placeholders that no rule matches."""

    def __init__(self, path: str, wire: bool):
        self.always = _matching(KEPT_NAMES, path)
        self.wire = _matching(WIRE_KEEP, path) if wire else []

    def mask(self, line: str) -> tuple[str, list[str], list[str]]:
        kept: list[str] = []
        wire_hits: list[str] = []

        def hide(m: re.Match) -> str:
            kept.append(m.group(0))
            return f"\0{len(kept) - 1}\0"

        def hide_wire(reason: str):
            def one(m: re.Match) -> str:
                if OLD_NAME.search(m.group(0)):
                    wire_hits.append(reason)
                return hide(m)
            return one
        line = KEPT_URL.sub(hide, line)
        for pattern, _ in self.always:
            line = pattern.sub(hide, line)
        for pattern, reason in self.wire:
            line = pattern.sub(hide_wire(reason), line)
        return line, kept, wire_hits


def _masked(line: str) -> tuple[str, list[str]]:
    kept: list[str] = []

    def hide(m: re.Match) -> str:
        kept.append(m.group(0))
        return f"\0{len(kept) - 1}\0"
    return KEPT_URL.sub(hide, line), kept


def _unmasked(line: str, kept: list[str]) -> str:
    return re.sub(r"\0(\d+)\0", lambda m: kept[int(m.group(1))], line)


def _apply(name: str, pattern: re.Pattern, replacement, text: str, counts: Counter) -> str:
    """pattern.sub, counting only the matches whose replacement differs from the match."""
    def one(m: re.Match) -> str:
        new = replacement(m) if callable(replacement) else m.expand(replacement)
        if new != m.group(0):
            counts[name] += 1
        return new
    return pattern.sub(one, text)


def rewrite_paths(path: str, text: str, counts: Counter) -> str:
    out = []
    lines = text.split("\n")
    history = history_lines(path, lines)
    for i, line in enumerate(lines):
        if i in history:
            out.append(line)
            continue
        masked, kept = _masked(line)
        for name, pattern, replacement in PATH_RULES:
            masked = _apply(name, pattern, replacement, masked, counts)
        out.append(_unmasked(masked, kept))
    return "\n".join(out)


IDENTIFIER_RULES: list[tuple[str, re.Pattern, object]] = [(c, re.compile(p), r) for c, p, r in IDENTIFIER_MAP]


def rewrite_identifiers(path: str, text: str, counts: Counter) -> str:
    """IDENTIFIER_MAP over `text`, outside the masks of `path` and outside its history lines."""
    masks = Masks(path, wire=True)
    out = []
    lines = text.split("\n")
    history = history_lines(path, lines)
    for i, line in enumerate(lines):
        if i in history:
            out.append(line)
            continue
        masked, kept, _ = masks.mask(line)
        for name, pattern, replacement in IDENTIFIER_RULES:
            masked = _apply(name, pattern, replacement, masked, counts)
        out.append(_unmasked(masked, kept))
    return "\n".join(out)


def text_kind(path: str) -> bool:
    name = path.rsplit("/", 1)[-1]
    return name in TEXT_NAMES or Path(name).suffix in TEXT_SUFFIXES


def package_moves(files: set[str]) -> list[tuple[str, str]]:
    """Commit B's directory moves: every `.../src/<set>/{java,resources}/io/flottila` -> `.../io/rail8`."""
    dirs = sorted({m.group(1) for f in files if (m := PACKAGE_DIR.match(f))})
    return [(f"{d}/io/flottila", f"{d}/{PACKAGE_DIR_NEW}") for d in dirs]


def map_identifier_path(path: str) -> str:
    """A repository path after commit B's moves."""
    m = PACKAGE_DIR.match(path)
    if m:
        return f"{m.group(1)}/{PACKAGE_DIR_NEW}/" + path[m.end():]
    for old, new in FILE_MOVES:
        if path == old:
            return new
    return path


def _snippet(a: str, b: str, context: int = 30) -> str:
    """The changed span of one line, with a little context: `...before[old -> new]after...`."""
    i = 0
    while i < min(len(a), len(b)) and a[i] == b[i]:
        i += 1
    j = 0
    while j < min(len(a), len(b)) - i and a[len(a) - 1 - j] == b[len(b) - 1 - j]:
        j += 1
    return (a[max(0, i - context):i] + "[" + a[i:len(a) - j] + " -> " + b[i:len(b) - j] + "]"
            + a[len(a) - j:len(a) - j + context])


def _count_changes(before: str, after: str) -> int:
    return sum(1 for a, b in zip(before.split("\n"), after.split("\n")) if a != b)


# ---------------------------------------------------------------------------------------------------------------------


def cmd_moves(root: Path, scope: str | None, dry: bool) -> int:
    files = set(tracked(root))
    exists = lambda p: p in files or any(f.startswith(p + "/") for f in files)  # noqa: E731
    moved = 0
    plan: list[tuple[str, str]] = []
    for old, new in PATH_MAP:
        if not (in_scope(old, scope) or in_scope(new, scope)):
            continue
        if exists(old):
            if old == "pom.xml" and exists(new):
                print(f"skip  {old} -> {new}: already moved (the root pom.xml is the new aggregator)")
                continue
            if exists(new):
                raise SystemExit(f"refused: {old} -> {new}: both exist")
            plan.append((old, new))
        elif exists(new):
            print(f"skip  {old} -> {new}: already moved")
        else:
            raise SystemExit(f"refused: {old} -> {new}: neither is tracked")
    for src, (dst, kids) in MERGE_MAP.items():
        if not (in_scope(src, scope) or in_scope(dst, scope)):
            continue
        present = sorted({p[len(src) + 1:].split("/", 1)[0] for p in files if p.startswith(src + "/")})
        unlisted = [k for k in present if k not in kids]
        if unlisted:
            raise SystemExit(f"refused: {src} holds tracked entries the map does not list: {unlisted}")
        for kid in kids:
            old, new = f"{src}/{kid}", f"{dst}/{kid}"
            if exists(old):
                if exists(new):
                    raise SystemExit(f"refused: {old} -> {new}: both exist")
                plan.append((old, new))
            elif exists(new):
                print(f"skip  {old} -> {new}: already moved")
            else:
                raise SystemExit(f"refused: {old} -> {new}: neither is tracked")
    for old, new in plan:
        print(f"git mv {old} {new}")
        if not dry:
            (root / new).parent.mkdir(parents=True, exist_ok=True)
            git(root, "mv", old, new)
            for parent in Path(old).parents:   # an old parent left empty on disk (packages/, flottila-bench/ if empty)
                d = root / parent
                if str(parent) != "." and d.is_dir() and not any(d.iterdir()):
                    d.rmdir()
        moved += 1

    # the path strings in the living documents (read from the tree as it is after the moves, or as it would be)
    rewritten = 0
    counts: Counter = Counter()
    after = {map_path(p) if dry else p for p in tracked(root)}
    for path in sorted(after):
        if not living(path) or not in_scope(path, scope):
            continue
        on_disk = root / (path if not dry else _source_of(path, files))
        if not on_disk.is_file():
            continue
        text = read_text(on_disk)
        if text is None:
            continue
        per_file: Counter = Counter()
        new_text = rewrite_paths(path, text, per_file)
        if new_text == text:
            continue
        lines = _count_changes(text, new_text)
        counts.update(per_file)
        counts["lines"] += lines
        rewritten += 1
        print(f"rewrite {path}: {lines} line(s)")
        if dry:
            for number, (a, b) in enumerate(zip(text.split("\n"), new_text.split("\n")), 1):
                if a != b:
                    print(f"  {number}: {_snippet(a, b)}")
        else:
            on_disk.write_text(new_text, encoding="utf-8")
    print(f"moves: {moved} git mv, {rewritten} file(s) rewritten, {counts['lines']} line(s); "
          + ", ".join(f"{k} {v}" for k, v in sorted(counts.items()) if k != "lines"))
    return 0


def _source_of(new_path: str, files: set[str]) -> str:
    """For a dry run: the tracked path whose move gives `new_path`."""
    for f in files:
        if map_path(f) == new_path:
            return f
    return new_path


def cmd_identifiers(root: Path, scope: str | None, dry: bool) -> int:
    """Commit B: `git mv` of every io/flottila package directory and of the launcher, then IDENTIFIER_MAP over every
    tracked text file outside EXCLUDED, B_UNTOUCHED, the history lines and the masks (WIRE_KEEP, KEPT_NAMES)."""
    files = set(tracked(root))
    exists = lambda p: p in files or any(f.startswith(p + "/") for f in files)  # noqa: E731
    plan: list[tuple[str, str]] = []
    for old, new in package_moves({f for f in files if in_scope(f, scope)}) + FILE_MOVES:
        if not (in_scope(old, scope) or in_scope(new, scope)):
            continue
        if exists(old) and exists(new):
            raise SystemExit(f"refused: {old} -> {new}: both exist")
        if exists(old):
            plan.append((old, new))
        elif exists(new):
            print(f"skip  {old} -> {new}: already moved")
    for old, new in plan:
        print(f"git mv {old} {new}")
        if not dry:
            git(root, "mv", old, new)
    counts: Counter = Counter()
    rewritten = 0
    unclassified: list[str] = []
    current = sorted(tracked(root)) if not dry else sorted(files)
    for source in current:
        path = source if not dry else map_identifier_path(source)
        if excluded(path) or path in B_UNTOUCHED or not in_scope(path, scope) or "/target/" in f"/{path}":
            continue
        file = root / source
        if not file.is_file():
            continue
        text = read_text(file)
        if text is None:
            continue
        if not text_kind(path):
            if OLD_NAME.search(text):
                unclassified.append(path)
            continue
        per_file: Counter = Counter()
        new_text = rewrite_identifiers(path, text, per_file)
        if new_text == text:
            continue
        lines = _count_changes(text, new_text)
        counts.update(per_file)
        counts["lines"] += lines
        rewritten += 1
        print(f"rewrite {path}: {lines} line(s)")
        if dry:
            for number, (a, b) in enumerate(zip(text.split("\n"), new_text.split("\n")), 1):
                if a != b:
                    print(f"  {number}: {_snippet(a, b)}")
        else:
            file.write_text(new_text, encoding="utf-8")
    for path in unclassified:
        print(f"not classified (not rewritten, holds the old name): {path}")
    print(f"identifiers: {len(plan)} git mv, {rewritten} file(s) rewritten, {counts['lines']} line(s); "
          + ", ".join(f"{k} {v}" for k, v in sorted(counts.items()) if k != "lines"))
    return 0


def cmd_path_report(root: Path, scope: str | None) -> None:
    """Every line outside the exclusion list on which a path rule would still fire: after `moves`, each is a hand edit
    still to make, or a string that is deliberately not a repository path (say which in the hand-back)."""
    found = 0
    for path in tracked(root):
        if excluded(path) or not in_scope(path, scope):
            continue
        text = read_text(root / path) if (root / path).is_file() else None
        if text is None:
            continue
        lines = text.split("\n")
        history = history_lines(path, lines)
        for number, line in enumerate(lines, 1):
            if number - 1 in history:
                continue
            new = rewrite_paths(path, line, Counter())
            if new != line:
                found += 1
                print(f"{path}:{number}: {_snippet(line, new)}")
    print(f"remaining old path strings outside the exclusion list: {found} line(s)")


def cmd_report(root: Path, scope: str | None, summary: bool) -> int:
    """Every remaining line with the old name (any case) outside EXCLUDED, the history lines and KEPT_NAMES, grouped by
    file. Each line is tagged: [C: reason] when every occurrence lies inside a WIRE_KEEP literal (commit C renames it),
    [B untouched] in a B_UNTOUCHED file, [?] otherwise. After commit B no line is [?]; after commit C the list is empty."""
    hits: dict[str, list[tuple[int, str, str]]] = defaultdict(list)
    kept_hits: dict[str, list[tuple[int, str, str]]] = defaultdict(list)
    tags: Counter = Counter()
    for path in tracked(root):
        if excluded(path) or not in_scope(path, scope) or "/target/" in f"/{path}":
            continue
        file = root / path
        if not file.is_file():
            continue
        text = read_text(file)
        if text is None:
            continue
        masks = Masks(path, wire=True)
        lines = text.split("\n")
        history = history_lines(path, lines)
        for number, line in enumerate(lines, 1):
            if number - 1 in history:
                continue
            if not OLD_NAME.search(line):
                continue
            masked, _, wire_hits = masks.mask(line)
            if path in B_UNTOUCHED:
                tag = "B untouched"
            elif OLD_NAME.search(masked):
                tag = "?"
            elif wire_hits:
                tag = "C: " + "; ".join(dict.fromkeys(wire_hits))
            else:   # only kept names on this line (ruling 4: the repository URLs and KEPT_NAMES)
                reasons = (["repository URL"] if KEPT_URL.search(line) else []) + [
                    reason for pattern, reason in masks.always if pattern.search(line)]
                kept_hits[path].append((number, "; ".join(dict.fromkeys(reasons)), line.strip()))
                continue
            tags[tag.split(":")[0]] += 1
            hits[path].append((number, tag, line.strip()))
    kept_total = sum(len(v) for v in kept_hits.values())
    print(f"kept (ruling 4, never rewritten): {kept_total} line(s) in {len(kept_hits)} file(s)")
    for path in sorted(kept_hits):
        print(f"{path} ({len(kept_hits[path])})")
        if not summary:
            for number, reason, line in kept_hits[path]:
                print(f"  {number}: [kept: {reason}] {line[:200]}")
    total = sum(len(v) for v in hits.values())
    print(f"remaining occurrences of the old name outside the exclusion list: {total} line(s) in {len(hits)} file(s)"
          + (": " if tags else "") + ", ".join(f"[{k}] {v}" for k, v in sorted(tags.items())))
    for path in sorted(hits):
        print(f"{path} ({len(hits[path])})")
        if not summary:
            for number, tag, line in hits[path]:
                print(f"  {number}: [{tag}] {line[:200]}")
    return 0


def _wire_entries(path: str) -> list[tuple[int, str, object, str]]:
    """The WIRE_MAP entries (index, class, old, new) whose files cover `path`, the longest literal first (a pattern last)."""
    entries = [(i, cls, old, new) for i, (cls, globs, old, new) in enumerate(WIRE_MAP) if any(_glob(path, g) for g in globs)]
    return sorted(entries, key=lambda e: -len(e[2]) if isinstance(e[2], str) else 0)


def rewrite_wire(path: str, text: str, counts: Counter, used: Counter) -> str:
    """WIRE_MAP over `text` outside the history lines of `path` and outside KEPT_URL / KEPT_NAMES; `used[i]` counts the
    replacements of entry i."""
    entries = _wire_entries(path)
    if not entries:
        return text
    masks = Masks(path, wire=False)
    out = []
    lines = text.split("\n")
    history = history_lines(path, lines)
    for number, line in enumerate(lines):
        if number in history:
            out.append(line)
            continue
        masked, kept, _ = masks.mask(line)
        for i, cls, old, new in entries:
            if isinstance(old, str):
                hits = masked.count(old)
                masked = masked.replace(old, new)
            else:
                masked, hits = old.subn(new, masked)
            counts[cls] += hits
            used[i] += hits
        out.append(_unmasked(masked, kept))
    return "\n".join(out)


# The parent of commit C (BRAND-1): the tree before the wire pass. An entry is live when it rewrites the file as
# this commit holds it or as the tree holds it now; an entry that rewrites neither never matched anything (stale).
WIRE_BASE = "c017e9c"


def _wire_files(paths, scope: str | None) -> list[str]:
    return [p for p in sorted(paths) if not excluded(p) and in_scope(p, scope) and "/target/" not in f"/{p}"
            and _wire_entries(p)]


def _base_used(root: Path) -> Counter | None:
    """`used` of rewrite_wire over every file of WIRE_BASE its entries name, read from the local object store; None
    if the commit is not there (a shallow clone)."""
    if subprocess.run(["git", "cat-file", "-e", WIRE_BASE + "^{commit}"], cwd=root, capture_output=True).returncode:
        return None
    used: Counter = Counter()
    for path in _wire_files(git(root, "ls-tree", "-r", "-z", "--name-only", WIRE_BASE).split("\0"), None):
        blob = subprocess.run(["git", "cat-file", "blob", f"{WIRE_BASE}:{path}"], cwd=root, capture_output=True)
        text = _decoded(blob.stdout) if blob.returncode == 0 else None
        if text is not None:
            rewrite_wire(path, text, Counter(), used)
    return used


def cmd_wire(root: Path, scope: str | None, dry: bool) -> int:
    """Commit C: WIRE_MAP over every tracked text file its entries name, outside EXCLUDED and the history lines. Ends with
    a check that every entry matched something in the files it names, in the tree now or at WIRE_BASE (an entry that
    matched in neither is stale: an error)."""
    counts: Counter = Counter()
    used: Counter = Counter()
    rewritten = 0
    for path in _wire_files(tracked(root), scope):
        file = root / path
        if not file.is_file():
            continue
        text = read_text(file)
        if text is None:
            continue
        new_text = rewrite_wire(path, text, counts, used)
        if new_text == text:
            continue
        lines = _count_changes(text, new_text)
        counts["lines"] += lines
        rewritten += 1
        print(f"rewrite {path}: {lines} line(s)")
        if dry:
            for number, (a, b) in enumerate(zip(text.split("\n"), new_text.split("\n")), 1):
                if a != b:
                    print(f"  {number}: {_snippet(a, b)}")
        else:
            file.write_text(new_text, encoding="utf-8")
    summary = (f"wire: {rewritten} file(s) rewritten, {counts['lines']} line(s); "
               + ", ".join(f"{k} {v}" for k, v in sorted(counts.items()) if k != "lines")
               + f"; {sum(1 for i in range(len(WIRE_MAP)) if used[i])} of {len(WIRE_MAP)} entries applied")
    if scope is not None:
        print(summary)
        return 0
    base = _base_used(root)
    if base is None:
        print(summary + f"; stale check not run: commit {WIRE_BASE} is not in this clone")
        return 1
    stale = [i for i in range(len(WIRE_MAP)) if not used[i] and not base[i]]
    for i in stale:
        cls, globs, old, _ = WIRE_MAP[i]
        print(f"stale wire entry {i} [{cls}] {old if isinstance(old, str) else old.pattern!r}: "
              f"matches nothing in {', '.join(globs)}, neither in the tree nor at {WIRE_BASE}")
    print(summary + f", {sum(1 for i in range(len(WIRE_MAP)) if base[i])} at {WIRE_BASE}; {len(stale)} stale")
    return 1 if stale else 0


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="command", required=True)
    for name in ("moves", "identifiers", "wire", "report"):
        p = sub.add_parser(name)
        p.add_argument("--dry-run", action="store_true", help="print what would change, change nothing")
        p.add_argument("--root", default=None, help="limit to this repository subtree (e.g. mapping)")
        p.add_argument("--summary", action="store_true", help="final report: counts per file, no lines")
        p.add_argument("--paths", action="store_true", help="also list every line where a path rule would still fire")
    args = ap.parse_args(argv)
    root = Path(git(Path.cwd(), "rev-parse", "--show-toplevel").strip())
    scope = args.root.strip("/") if args.root else None
    status = 0
    if args.command == "moves":
        cmd_moves(root, scope, args.dry_run)
    if args.command == "identifiers":
        cmd_identifiers(root, scope, args.dry_run)
    if args.command == "wire":
        status = cmd_wire(root, scope, args.dry_run)
    if args.paths:
        cmd_path_report(root, scope)
    return cmd_report(root, scope, args.summary) or status


if __name__ == "__main__":
    sys.exit(main())
