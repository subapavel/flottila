"""Tests for ci/coverage_check.py — the JaCoCo no-decrease gate (CLAUDE.md: "No decrease on any push").

Run: python3 -m unittest ci.test_coverage_check  (from the repository root; also run by the CI build job)
"""
import contextlib
import io
import json
import subprocess
import tempfile
import unittest
from pathlib import Path

from ci import coverage_check as cc

JACOCO_XML = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<!DOCTYPE report PUBLIC "-//JACOCO//DTD Report 1.1//EN" "report.dtd">
<report name="{name}">
  <package name="io/flottila/x"><class name="io/flottila/x/A" sourcefilename="A.java"/></package>
  <counter type="INSTRUCTION" missed="1" covered="9"/>
  <counter type="LINE" missed="{lm}" covered="{lc}"/>
  <counter type="BRANCH" missed="{bm}" covered="{bc}"/>
</report>
"""


JACOCO_XML_NO_COUNTERS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<report name="{name}"><package name="io/flottila/x"/></report>
"""


def git(root: Path, *args: str) -> str:
    env = {"GIT_AUTHOR_NAME": "t", "GIT_AUTHOR_EMAIL": "t@x", "GIT_COMMITTER_NAME": "t", "GIT_COMMITTER_EMAIL": "t@x",
           "HOME": str(root), "PATH": "/usr/bin:/bin"}
    return subprocess.run(["git", *args], cwd=root, check=True, capture_output=True, text=True, env=env).stdout


def module(root: Path, name: str, *, classes=("A.class",), report=None):
    m = root / name
    (m / "target" / "classes" / "io" / "flottila" / "x").mkdir(parents=True)
    for c in classes:
        (m / "target" / "classes" / "io" / "flottila" / "x" / c).write_bytes(b"\xca\xfe")
    if report is not None:
        lm, lc, bm, bc = report
        (m / "target" / "site" / "jacoco").mkdir(parents=True)
        (m / "target" / "site" / "jacoco" / "jacoco.xml").write_text(
            JACOCO_XML.format(name=name, lm=lm, lc=lc, bm=bm, bc=bc))
    return m


class CoverageCheckTest(unittest.TestCase):

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def test_measures_line_and_branch_percent_per_module(self):
        module(self.root, "flottila-api", report=(10, 90, 5, 15))
        current = cc.measure(self.root)
        self.assertEqual({"flottila-api": {"line": 90.0, "branch": 75.0}}, current)

    def test_package_info_only_module_without_report_is_not_a_problem(self):
        module(self.root, "flottila-core", classes=("package-info.class",))
        self.assertEqual({}, cc.measure(self.root))
        self.assertEqual([], cc.compare(cc.measure(self.root), {}, self.root))

    def test_report_without_counters_has_no_number(self):
        m = module(self.root, "flottila-api", classes=("package-info.class",))
        (m / "target" / "site" / "jacoco").mkdir(parents=True)
        (m / "target" / "site" / "jacoco" / "jacoco.xml").write_text(JACOCO_XML_NO_COUNTERS.format(name="api"))
        self.assertEqual({}, cc.measure(self.root))

    def test_module_without_measurable_lines_has_no_number(self):
        module(self.root, "flottila-core", report=(0, 0, 0, 0))
        self.assertEqual({}, cc.measure(self.root))

    def test_nested_module_is_keyed_by_its_path(self):
        module(self.root, "flottila-components/http", report=(0, 10, 0, 0))
        self.assertEqual({"flottila-components/http": {"line": 100.0, "branch": 100.0}}, cc.measure(self.root))

    def test_module_with_classes_but_no_report_is_a_failure(self):
        module(self.root, "flottila-core")  # classes, no jacoco.xml: tests never ran here
        problems = cc.compare(cc.measure(self.root), {}, self.root)
        self.assertEqual(["flottila-core: has classes but no JaCoCo report (no test ran)"], problems)

    def test_an_explicitly_ignored_module_may_lack_a_report_and_the_default_ignores_nothing(self):
        module(self.root, "flottila-legacy")
        self.assertEqual([], cc.compare(cc.measure(self.root), {}, self.root, ignore=frozenset({"flottila-legacy"})))
        self.assertEqual(["flottila-legacy: has classes but no JaCoCo report (no test ran)"],
                         cc.compare(cc.measure(self.root), {}, self.root))
        self.assertEqual(frozenset(), cc.IGNORED, "nothing is exempt from the coverage report since P0-09")

    def test_decrease_against_baseline_fails(self):
        module(self.root, "flottila-api", report=(20, 80, 5, 15))
        baseline = {"flottila-api": {"line": 90.0, "branch": 75.0}}
        problems = cc.compare(cc.measure(self.root), baseline, self.root)
        self.assertEqual(["flottila-api: line 80.0 % < baseline 90.0 % - 2.0"], problems)

    def test_equal_or_higher_passes(self):
        module(self.root, "flottila-api", report=(5, 95, 5, 15))
        baseline = {"flottila-api": {"line": 90.0, "branch": 75.0}}
        self.assertEqual([], cc.compare(cc.measure(self.root), baseline, self.root))

    def test_new_module_without_baseline_passes(self):
        module(self.root, "flottila-core", report=(0, 10, 0, 2))
        self.assertEqual([], cc.compare(cc.measure(self.root), {}, self.root))

    def test_rounds_to_one_decimal(self):
        module(self.root, "flottila-api", report=(1, 2, 2, 1))
        self.assertEqual({"flottila-api": {"line": 66.7, "branch": 33.3}}, cc.measure(self.root))

    def test_a_drop_inside_the_noise_band_is_not_a_decrease(self):
        # timing-dependent branches (testkit detector) move by one branch ≈ 1.2 pp between runs; D-029 band = 2.0 pp
        module(self.root, "flottila-api", report=(2, 98, 11, 89))      # line 98.0, branch 89.0
        baseline = {"flottila-api": {"line": 99.9, "branch": 90.5}}
        self.assertEqual([], cc.compare(cc.measure(self.root), baseline, self.root))

    def test_a_drop_beyond_the_noise_band_is_a_decrease(self):
        module(self.root, "flottila-core", report=(21, 979, 3, 97))    # line 97.9, branch 97.0
        baseline = {"flottila-core": {"line": 100.0, "branch": 99.1}}
        problems = cc.compare(cc.measure(self.root), baseline, self.root)
        self.assertEqual(["flottila-core: line 97.9 % < baseline 100.0 % - 2.0",
                          "flottila-core: branch 97.0 % < baseline 99.1 % - 2.0"], problems)

    def test_check_baseline_catches_a_forgotten_write_and_a_missing_module(self):
        module(self.root, "flottila-api", report=(0, 100, 0, 10))      # 100 / 100
        module(self.root, "flottila-core", report=(50, 50, 5, 5))      # 50 / 50
        baseline = {"flottila-api": {"line": 97.0, "branch": 100.0}}   # api rose by 3 pp, core absent
        problems = cc.check_baseline(cc.measure(self.root), baseline)
        self.assertEqual(["flottila-api: line 100.0 % is above the committed baseline 97.0 % by more than 2.0 — run --write",
                          "flottila-core: not in the committed baseline — run --write"], problems)

    def test_check_baseline_accepts_noise_in_both_directions_and_flags_removed_modules(self):
        module(self.root, "flottila-api", report=(2, 98, 11, 89))      # 98.0 / 89.0
        baseline = {"flottila-api": {"line": 99.9, "branch": 90.5}, "flottila-old": {"line": 50.0, "branch": 50.0}}
        self.assertEqual(["flottila-old: in the committed baseline but not measured — run --write"],
                         cc.check_baseline(cc.measure(self.root), baseline))
        baseline.pop("flottila-old")
        self.assertEqual([], cc.check_baseline(cc.measure(self.root), baseline))

    def test_a_drop_of_exactly_the_band_is_still_noise(self):
        module(self.root, "flottila-api", report=(2, 98, 0, 10))       # line 98.0
        self.assertEqual([], cc.compare(cc.measure(self.root), {"flottila-api": {"line": 100.0, "branch": 100.0}}, self.root))

    def test_check_baseline_flags_a_committed_value_far_above_the_measurement(self):
        module(self.root, "flottila-api", report=(10, 90, 0, 10))      # line 90.0
        problems = cc.check_baseline(cc.measure(self.root), {"flottila-api": {"line": 95.0, "branch": 100.0}})
        self.assertEqual(["flottila-api: line 90.0 % is below the committed baseline 95.0 % by more than 2.0 — coverage dropped; run --write and say why"], problems)

    def test_write_writes_the_measured_value_inside_the_band(self):
        # D-050: --write records the measurement, never a remembered higher value; the band is a tolerance of the check, not of the file
        module(self.root, "flottila-api", report=(1, 99, 3, 197))      # 99.0 / 98.5 measured, each inside the 2.0 band below 100.0
        path = self.root / "ci" / "coverage-baseline.json"
        path.parent.mkdir()
        path.write_text(json.dumps({"flottila-api": {"line": 100.0, "branch": 100.0}, "flottila-gone": {"line": 1.0, "branch": 1.0}}))
        cc.write_baseline(cc.measure(self.root), path)
        self.assertEqual({"flottila-api": {"line": 99.0, "branch": 98.5}}, json.loads(path.read_text()),
                         "a drop inside the band is written as measured; a removed module leaves the file")
        report = self.root / "flottila-api" / "target" / "site" / "jacoco" / "jacoco.xml"
        report.write_text(JACOCO_XML.format(name="flottila-api", lm=3, lc=97, bm=20, bc=80))        # 97.0 / 80.0: branch drops beyond the band
        cc.write_baseline(cc.measure(self.root), path)
        self.assertEqual({"flottila-api": {"line": 97.0, "branch": 80.0}}, json.loads(path.read_text()),
                         "a drop beyond the band is written as measured — visible in the diff")
        report.write_text(JACOCO_XML.format(name="flottila-api", lm=2, lc=98, bm=11, bc=89))        # 98.0 / 89.0: a rise
        cc.write_baseline(cc.measure(self.root), path)
        self.assertEqual({"flottila-api": {"line": 98.0, "branch": 89.0}}, json.loads(path.read_text()), "a rise is written as measured")

    def test_write_then_check_baseline_is_current(self):
        module(self.root, "flottila-api", report=(1, 99, 3, 197))      # 99.0 / 98.5 measured
        module(self.root, "flottila-core", report=(19, 981, 15, 985))  # 98.1 / 98.5 measured
        path = self.root / "ci" / "coverage-baseline.json"
        path.parent.mkdir()
        path.write_text(json.dumps({"flottila-api": {"line": 100.0, "branch": 100.0},     # every committed value above the
                                    "flottila-core": {"line": 100.0, "branch": 99.9}}))  # measurement by less than the band
        args = ["--root", str(self.root), "--baseline-file", str(path)]
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            self.assertEqual(0, cc.main(args + ["--write"]))
            self.assertEqual(0, cc.main(args + ["--check-baseline"]))
        self.assertIn("baseline: current", out.getvalue())
        self.assertEqual([], cc.check_baseline(cc.measure(self.root), json.loads(path.read_text())),
                         "the file --write produced describes this very tree")

    def test_main_check_baseline_exit_code(self):
        module(self.root, "flottila-api", report=(0, 100, 0, 10))
        path = self.root / "ci" / "coverage-baseline.json"
        path.parent.mkdir()
        path.write_text(json.dumps({"flottila-api": {"line": 90.0, "branch": 100.0}}))
        self.assertEqual(1, cc.main(["--root", str(self.root), "--baseline-file", str(path), "--check-baseline"]))
        path.write_text(json.dumps({"flottila-api": {"line": 99.0, "branch": 100.0}}))
        self.assertEqual(0, cc.main(["--root", str(self.root), "--baseline-file", str(path), "--check-baseline"]))

    def test_write_baseline_round_trips(self):
        module(self.root, "flottila-api", report=(10, 90, 5, 15))
        path = self.root / "ci" / "coverage-baseline.json"
        path.parent.mkdir()
        cc.write_baseline(cc.measure(self.root), path)
        self.assertEqual({"flottila-api": {"line": 90.0, "branch": 75.0}}, json.loads(path.read_text()))

    def _repo_with_committed_baseline(self, baseline: dict) -> Path:
        git(self.root, "init", "-q")
        path = self.root / "ci" / "coverage-baseline.json"
        path.parent.mkdir()
        path.write_text(json.dumps(baseline))
        git(self.root, "add", "ci/coverage-baseline.json")
        git(self.root, "commit", "-q", "-m", "baseline")
        return path

    def test_baseline_ref_reads_the_committed_file_not_the_working_tree(self):
        path = self._repo_with_committed_baseline({"flottila-api": {"line": 90.0, "branch": 75.0}})
        module(self.root, "flottila-api", report=(20, 80, 5, 15))
        # the working tree already carries the rewritten (lower) baseline; HEAD must still win
        cc.write_baseline(cc.measure(self.root), path)
        self.assertEqual(1, cc.main(["--root", str(self.root), "--baseline-ref", "HEAD"]))

    def test_baseline_ref_where_the_file_does_not_exist_yet_passes(self):
        git(self.root, "init", "-q")
        (self.root / "README").write_text("x")
        git(self.root, "add", "README")
        git(self.root, "commit", "-q", "-m", "first")
        module(self.root, "flottila-api", report=(20, 80, 5, 15))
        self.assertEqual(0, cc.main(["--root", str(self.root), "--baseline-ref", "HEAD"]))

    def test_unreadable_baseline_ref_is_an_error_not_a_pass(self):
        self._repo_with_committed_baseline({"flottila-api": {"line": 90.0, "branch": 75.0}})
        module(self.root, "flottila-api", report=(20, 80, 5, 15))
        self.assertEqual(2, cc.main(["--root", str(self.root), "--baseline-ref", "no-such-ref"]))
        with self.assertRaises(cc.BaselineError):
            cc._load_baseline("no-such-ref", self.root / "ci" / "coverage-baseline.json", self.root)

    def test_main_exit_code_reports_problems(self):
        module(self.root, "flottila-api", report=(20, 80, 5, 15))
        path = self.root / "ci" / "coverage-baseline.json"
        path.parent.mkdir()
        path.write_text(json.dumps({"flottila-api": {"line": 90.0, "branch": 75.0}}))
        self.assertEqual(1, cc.main(["--root", str(self.root), "--baseline-file", str(path)]))
        path.write_text(json.dumps({"flottila-api": {"line": 80.0, "branch": 75.0}}))
        self.assertEqual(0, cc.main(["--root", str(self.root), "--baseline-file", str(path)]))


if __name__ == "__main__":
    unittest.main()
