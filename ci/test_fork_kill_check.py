"""Tests for ci/fork_kill_check.py: how it reads Maven's log and the listener's files, and its verdict per module.

Run: python3 -m unittest ci.test_fork_kill_check  (from the repository root; also run by the CI build job)

The sample log is cut from a real `mvnw -B -fn verify` of the repository with the listener on the test class path.
"""
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import fork_kill_check as check  # noqa: E402

LOG = """\
[INFO] -----------------------< dev.rail8:rail8-parent >-----------------------
[INFO] Building Rail8 0.1.0-SNAPSHOT                                     [1/5]
[INFO] ------------------------< dev.rail8:rail8-api >-------------------------
[INFO] Running dev.rail8.api.FaultTest
[INFO] --------------< dev.rail8.mapping:rail8-mapping-udf-api >---------------
[INFO] ---------------------< dev.rail8.jq:rail8-jq-core >---------------------
[INFO] -----------------------< dev.rail8:rail8-step-jq >-----------------------
[INFO] Reactor Summary for Rail8 :: all 0.1.0-SNAPSHOT:
[INFO] Rail8 :: api ....................................... FAILURE [ 10.942 s]
[INFO] BUILD FAILURE
[ERROR] Failed to execute goal org.apache.maven.plugins:maven-surefire-plugin:3.6.0:test (default-test) on project rail8-api:
[ERROR] ExecutionException The forked VM terminated without properly saying goodbye. VM crash or System.exit called?
[ERROR] Process Exit Code: 0
[ERROR] Failed to execute goal org.apache.maven.plugins:maven-failsafe-plugin:3.6.0:verify (verify) on project rail8-jq-core:
[ERROR] Failed to execute goal org.apache.maven.plugins:maven-enforcer-plugin:3.6.3:enforce (enforce) on project rail8-step-jq:
[INFO] Build failures were ignored.
"""


class Log(unittest.TestCase):
    def test_the_modules_are_read_in_build_order(self):
        self.assertEqual(["rail8-parent", "rail8-api", "rail8-mapping-udf-api", "rail8-jq-core", "rail8-step-jq"],
                         check.modules(LOG))

    def test_only_a_failed_surefire_or_failsafe_goal_counts_as_failed(self):
        self.assertEqual({"rail8-api", "rail8-jq-core"}, check.failed(LOG))


class Killed(unittest.TestCase):
    def test_the_modules_are_named_by_the_artifact_id_of_the_base_directory_each_killed_jvm_ran_in(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for name, artifact in (("api", "rail8-api"), ("core", "rail8-core")):
                (root / name).mkdir()
                (root / name / "pom.xml").write_text(
                    '<project xmlns="http://maven.apache.org/POM/4.0.0"><parent><artifactId>rail8-parent</artifactId>'
                    f"</parent><artifactId>{artifact}</artifactId></project>", encoding="utf-8")
            marks = root / "marks"
            marks.mkdir()
            (marks / "fork-11").write_text(f"{root / 'api'}\t[engine:junit-jupiter]/[class:A]/[method:a()]\n")
            (marks / "fork-12").write_text(f"{root / 'api'}\t[engine:junit-jupiter]/[class:B]/[method:b()]\n")
            (marks / "fork-13").write_text(f"{root / 'core'}\t[engine:junit-jupiter]/[class:C]/[method:c()]\n")
            self.assertEqual({"rail8-api", "rail8-core"}, check.killed(marks))


class Verdict(unittest.TestCase):
    def test_every_killed_module_that_failed_is_no_finding(self):
        lines, findings = check.verdict(["rail8-parent", "rail8-api"], {"rail8-api"}, {"rail8-api"})
        self.assertEqual(0, findings)
        self.assertEqual(["rail8-parent: ran no test", "rail8-api: killed, failed"], lines)

    def test_a_killed_module_that_passed_is_a_finding(self):
        lines, findings = check.verdict(["rail8-api", "rail8-core"], {"rail8-api", "rail8-core"}, {"rail8-api"})
        self.assertEqual(1, findings)
        self.assertTrue(lines[1].startswith("rail8-core: killed, PASSED"), lines)

    def test_a_run_in_which_no_module_ran_a_test_is_a_finding(self):
        lines, findings = check.verdict(["rail8-parent", "rail8-api"], set(), set())
        self.assertEqual(1, findings)
        self.assertIn("the listener was not loaded", lines[-1])


if __name__ == "__main__":
    unittest.main()
