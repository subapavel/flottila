"""Tests for bench/check_regression.py — fails the bench job when a run is > 20 % worse than the baseline
recorded in bench/RESULTS.md (WORKFLOW §4). Run: python3 -m unittest bench.test_check_regression"""
import contextlib
import io
import json
import tempfile
import unittest
from pathlib import Path

from bench import check_regression as cr

RESULTS = """# Bench results

## Phase 0 baseline — cloud VM, 4 vCPU (2026-10-18, commit abc1234)

<!-- baseline:batched-persistence-sync {"machine": "cloud VM, 4 vCPU", "throughputPerSecond": 2000.0, "p99Ms": 4.0} -->
<!-- baseline:batched-persistence-async {"machine": "cloud VM, 4 vCPU", "throughputPerSecond": 3000.0, "p99Ms": 3.0} -->
<!-- baseline:batched-persistence-sync {"machine": "github-hosted runner", "throughputPerSecond": 1000.0, "p99Ms": 8.0} -->

| run | req/s | p99 |
"""


class CheckRegressionTest(unittest.TestCase):

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        (self.root / "RESULTS.md").write_text(RESULTS)
        (self.root / "out").mkdir()

    def tearDown(self):
        self.tmp.cleanup()

    def run_with(self, name, throughput, p99, machine="cloud VM, 4 vCPU"):
        (self.root / "out" / f"{name}.json").write_text(json.dumps(
            {"name": name, "throughputPerSecond": throughput, "p99Ms": p99, "machine": {"label": machine, "cpus": 4}}))

    def test_baselines_are_read_from_the_marker_comments_per_machine(self):
        b = cr.baselines(self.root / "RESULTS.md")
        self.assertEqual({("cloud VM, 4 vCPU", "batched-persistence-sync"): {"throughputPerSecond": 2000.0, "p99Ms": 4.0},
                          ("cloud VM, 4 vCPU", "batched-persistence-async"): {"throughputPerSecond": 3000.0, "p99Ms": 3.0},
                          ("github-hosted runner", "batched-persistence-sync"): {"throughputPerSecond": 1000.0, "p99Ms": 8.0}}, b)

    def test_throughput_within_twenty_percent_passes_and_beyond_fails(self):
        self.run_with("batched-persistence-sync", 1700.0, 4.7)      # -15 % throughput, +17.5 % p99
        self.assertEqual([], cr.compare(self.root / "out", cr.baselines(self.root / "RESULTS.md")))
        self.run_with("batched-persistence-sync", 1590.0, 4.0)      # -20.5 %
        problems = cr.compare(self.root / "out", cr.baselines(self.root / "RESULTS.md"))
        self.assertEqual(["batched-persistence-sync [cloud VM, 4 vCPU]: throughputPerSecond 1590.0 is 20.5 % below the baseline 2000.0 (limit 20 %)"], problems)

    def test_p99_has_the_wider_fifty_percent_band_of_its_observed_run_to_run_spread(self):
        self.run_with("batched-persistence-sync", 2000.0, 5.9)      # p99 +47.5 %: inside the band (D-037: three baseline runs spread 12.0–18.1 ms)
        self.assertEqual([], cr.compare(self.root / "out", cr.baselines(self.root / "RESULTS.md")))
        self.run_with("batched-persistence-sync", 2000.0, 6.1)      # p99 +52.5 %
        self.assertEqual(["batched-persistence-sync [cloud VM, 4 vCPU]: p99Ms 6.1 is 52.5 % above the baseline 4.0 (limit 50 %)"],
                         cr.compare(self.root / "out", cr.baselines(self.root / "RESULTS.md")))

    def test_a_run_is_compared_only_with_the_baseline_of_its_own_machine(self):
        self.run_with("batched-persistence-sync", 1100.0, 8.0, machine="github-hosted runner")   # -45 % against the VM, +10 % against the runner
        self.assertEqual([], cr.compare(self.root / "out", cr.baselines(self.root / "RESULTS.md")))
        self.run_with("batched-persistence-sync", 790.0, 8.0, machine="github-hosted runner")    # -21 % against the runner
        self.assertEqual(["batched-persistence-sync [github-hosted runner]: throughputPerSecond 790.0 is 21.0 % below the baseline 1000.0 (limit 20 %)"],
                         cr.compare(self.root / "out", cr.baselines(self.root / "RESULTS.md")))

    def test_a_run_without_a_baseline_for_its_machine_is_reported_not_compared(self):
        self.run_with("new-run", 1.0, 100.0)
        self.run_with("batched-persistence-async", 1.0, 100.0, machine="github-hosted runner")
        self.assertEqual([], cr.compare(self.root / "out", cr.baselines(self.root / "RESULTS.md")))
        self.assertEqual(["batched-persistence-async [github-hosted runner]", "new-run [cloud VM, 4 vCPU]"],
                         cr.unbaselined(self.root / "out", cr.baselines(self.root / "RESULTS.md")))

    def test_a_marker_without_a_machine_is_rejected_loudly(self):
        (self.root / "RESULTS.md").write_text('<!-- baseline:x {"throughputPerSecond": 1.0, "p99Ms": 1.0} -->\n')
        with self.assertRaises(ValueError):
            cr.baselines(self.root / "RESULTS.md")

    def test_no_baselines_means_the_check_is_not_yet_armed(self):
        (self.root / "RESULTS.md").write_text("# nothing yet\n")
        self.run_with("x", 1.0, 1.0)
        with contextlib.redirect_stdout(io.StringIO()) as out:
            code = cr.main(["--results", str(self.root / "RESULTS.md"), "--out", str(self.root / "out")])
        self.assertEqual(0, code)
        self.assertIn("regression check not armed", out.getvalue())

    def test_main_exits_one_on_a_regression_and_zero_once_the_run_is_back_inside_the_band(self):
        self.run_with("batched-persistence-async", 1000.0, 3.0)
        self.assertEqual(1, cr.main(["--results", str(self.root / "RESULTS.md"), "--out", str(self.root / "out")]))
        self.run_with("batched-persistence-async", 3100.0, 3.0)
        self.assertEqual(0, cr.main(["--results", str(self.root / "RESULTS.md"), "--out", str(self.root / "out")]))


if __name__ == "__main__":
    unittest.main()
