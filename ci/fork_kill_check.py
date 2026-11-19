#!/usr/bin/env python3
"""Fork kill check: a build whose test JVM dies fails, in every module of a Maven reactor.

Builds the listener of ci/fork_kill (a JUnit Platform listener that ends the test JVM, with exit code 0 and no
goodbye, as soon as its first test finishes), then runs `mvnw -B -fn clean verify -Djacoco.skip=true` on the
reactor with the listener's jar on every Surefire and Failsafe test class path (`-Dmaven.test.additionalClasspath`),
so every test JVM that runs a test dies mid-run. `-fn` builds every module whatever the others did; `clean` keeps
an earlier run's Failsafe summary from failing a module that would pass. The listener writes the base directory
of each module whose test JVM it ended; the module's artifactId is read from that directory's pom.xml.

Each module of the reactor, in build order, is then one line:
  killed, failed     a test JVM died and Surefire or Failsafe failed the module (what must happen)
  killed, PASSED     a test JVM died and the module still passed: a finding
  ran no test        no test of the module finished, so nothing was killed (a pom, or a module without tests)
The last line counts the findings. A run in which no module ran a test is a finding too: the listener was not
loaded, and the check proved nothing.

Surefire stops a module at its first failure, so in a module with unit tests the Failsafe JVMs never start. To
kill those, run the check again with the unit tests deselected (see the second usage line).

Exit 0 no finding, 1 findings, 2 the listener did not build or Maven itself failed.

Usage (from anywhere inside the repository; extra arguments go to Maven). The platform is checked from the root, whose
build brings in the engines its steps depend on, with the engines' own tests skipped:
  python3 ci/fork_kill_check.py pom.xml -Drail8.engines.skipTests
  python3 ci/fork_kill_check.py pom.xml -Drail8.engines.skipTests -Pit -Dtest=NoUnitTest -Dsurefire.failIfNoSpecifiedTests=false
"""
from __future__ import annotations

import re
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
LISTENER_POM = ROOT / "ci" / "fork_kill" / "pom.xml"
LISTENER_JAR = ROOT / "ci" / "fork_kill" / "target" / "rail8-fork-kill.jar"
# the line Maven prints before it builds a module: [INFO] ----< groupId:artifactId >----
MODULE = re.compile(r"^\[INFO\] -+< [^:\s]+:([^\s>]+) >-+$")
# the error Maven prints for a module whose Surefire or Failsafe goal failed
TEST_GOAL_FAILED = re.compile(
    r"^\[ERROR\] Failed to execute goal org\.apache\.maven\.plugins:maven-(?:surefire|failsafe)-plugin:\S+ \(\S+\) "
    r"on project ([^\s:]+)")
POM = "{http://maven.apache.org/POM/4.0.0}"


def modules(log: str) -> list[str]:
    """The artifactIds of the reactor's modules, in the order Maven built them."""
    return [m.group(1) for line in log.splitlines() if (m := MODULE.match(line))]


def failed(log: str) -> set[str]:
    """The artifactIds of the modules whose Surefire or Failsafe goal failed."""
    return {m.group(1) for line in log.splitlines() if (m := TEST_GOAL_FAILED.match(line))}


def killed(marks: Path) -> set[str]:
    """The artifactIds of the modules whose test JVM the listener ended, from the files it wrote into `marks`."""
    found = set()
    for mark in marks.glob("fork-*"):
        for line in mark.read_text(encoding="utf-8").splitlines():
            base = Path(line.split("\t", 1)[0])
            found.add(ET.parse(base / "pom.xml").getroot().findtext(f"{POM}artifactId"))
    return found


def verdict(order: list[str], killed_modules: set[str], failed_modules: set[str]) -> tuple[list[str], int]:
    """One line per module of `order` and the number of findings."""
    lines, findings = [], 0
    for module in order:
        if module not in killed_modules:
            lines.append(f"{module}: ran no test")
        elif module in failed_modules:
            lines.append(f"{module}: killed, failed")
        else:
            lines.append(f"{module}: killed, PASSED (a test JVM died and the build of the module passed)")
            findings += 1
    if not killed_modules & set(order):
        lines.append("no module ran a test: the listener was not loaded, so nothing was checked")
        findings += 1
    return lines, findings


def main(argv: list[str]) -> int:
    if not argv or argv[0].startswith("-"):
        print(__doc__.split("Usage", 1)[1], file=sys.stderr)
        return 2
    pom, maven_args = argv[0], argv[1:]
    mvnw = str(ROOT / "mvnw")
    if subprocess.run([mvnw, "-B", "-q", "-f", str(LISTENER_POM), "package", "-Dmaven.test.skip=true"]).returncode:
        print("fork_kill_check: the listener did not build", file=sys.stderr)
        return 2
    with tempfile.TemporaryDirectory(prefix="fork-kill-") as marks:
        command = [mvnw, "-B", "-fn", "-f", pom, "clean", "verify", "-Djacoco.skip=true",
                   f"-Dmaven.test.additionalClasspath={LISTENER_JAR}", f"-Drail8.forkkill.dir={marks}", *maven_args]
        print("fork_kill_check: " + " ".join(command), flush=True)
        # Maven's output is passed through as it comes and kept for the verdict
        out = []
        with subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True) as maven:
            for line in maven.stdout:
                sys.stdout.write(line)
                out.append(line)
        if maven.returncode:
            print(f"fork_kill_check: Maven itself failed with exit code {maven.returncode}", file=sys.stderr)
            return 2
        log = "".join(out)
        lines, findings = verdict(modules(log), killed(Path(marks)), failed(log))
    print("\nfork_kill_check: " + pom + " " + " ".join(maven_args))
    for line in lines:
        print("  " + line)
    print(f"{findings} finding(s)")
    return 1 if findings else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
