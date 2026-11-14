"""Tests for ci/ledger_check.py — the table-shape guard on agent/BACKLOG.md and docs/DECISIONS.md (FIX-139).

Run: python3 -m unittest ci.test_ledger_check  (from the repository root; also run by the CI build job)

Fixtures are strings built here; the tests never read the real ledgers. Every red case is a mutation of
one shared well-formed fixture, so the unmodified fixture exiting 0 is the witness that the failing
variants fail because of their one changed row.
"""
import contextlib
import io
import re
import tempfile
import unittest
from pathlib import Path

from ci import ledger_check as lc

BACKLOG_HEADER = "| Id | Title | Depends on | Status | Done when | Evidence |"   # 6 cells, 7 pipes
BACKLOG_SEP = "| --- | --- | --- | --- | --- | --- |"
DECISIONS_HEADER = "| Id | Date | Decision | Reason |"                          # 4 cells, 5 pipes
DECISIONS_SEP = "| --- | --- | --- | --- |"

GOOD_BACKLOG_ROWS = [
    "| P0-01 | Root pom | none | done | build is green | commit abc |",
    "| P0-02 | CI finalised | P0-01 | done | jobs green | commit def |",
    "| P0-03 | Envelope record | P0-01 | done | tests pass | commit 123 |",
    "| P0-04 | Body over BlobStore | P0-03 | done | contract suite passes | commit 456 |",
    "| P0-05 | Step and Stage | P0-03 | done | pipeline runs | commit 789 |",
    "| P0-06 | Admission semaphore | P0-05 | done | bytes counted | commit aaa |",
    "| P0-07 | Fake clock | P0-01 | done | no sleeps | commit bbb |",
    "| P0-08 | Mock registry | P0-04 | done | mocks load | commit ccc |",
]

GOOD_DECISIONS_ROWS = [
    "| D-001 | 2026-10-17 | Public repository, Apache 2.0 | owner's choice |",
    "| D-002 | 2026-10-17 | Same repository from M1 | history survives |",
    "| D-003 | 2026-10-18 | Python 3 for CI helpers | no new toolchain |",
]


# Eight prose lines first: every table row sits on a line number above any cell count in play (2..7), so a
# line number can never be mistaken for a cell count in a message.
PRELUDE_BACKLOG = ("# Backlog", "") + ("Prose before the table.",) * 5 + ("",)
PRELUDE_DECISIONS = ("# Decisions", "") + ("Prose before the table.",) * 5 + ("",)


def backlog(rows, prelude=PRELUDE_BACKLOG):
    return list(prelude) + [BACKLOG_HEADER, BACKLOG_SEP] + list(rows)


def decisions(rows, prelude=PRELUDE_DECISIONS):
    return list(prelude) + [DECISIONS_HEADER, DECISIONS_SEP] + list(rows)


class LedgerCheckTest(unittest.TestCase):

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        (self.root / "agent").mkdir()
        (self.root / "docs").mkdir()

    def tearDown(self):
        self.tmp.cleanup()

    def write(self, backlog_lines=None, decisions_lines=None):
        (self.root / "agent" / "BACKLOG.md").write_text(
            "\n".join(backlog_lines if backlog_lines is not None else backlog(GOOD_BACKLOG_ROWS)) + "\n")
        (self.root / "docs" / "DECISIONS.md").write_text(
            "\n".join(decisions_lines if decisions_lines is not None else decisions(GOOD_DECISIONS_ROWS)) + "\n")

    def run_check(self):
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = lc.main(["--root", str(self.root)])
        return code, out.getvalue() + err.getvalue()

    @staticmethod
    def messages_for(output, filename, line_no):
        """Output lines that name this file and this line number as a whole word."""
        return [m for m in output.splitlines() if filename in m and re.search(rf"\b{line_no}\b", m)]

    def assert_rejects_row(self, lines, bad_row, filename, expected_cells=None, actual_cells=None):
        """The check exits non-zero and one message names `filename`, the 1-based line of bad_row and,
        when given, the expected and the actual cell count."""
        line_no = lines.index(bad_row) + 1
        code, output = self.run_check()
        self.assertNotEqual(0, code, f"expected non-zero for {bad_row!r} on line {line_no}, got 0; output: {output!r}")
        found = self.messages_for(output, filename, line_no)
        self.assertTrue(found, f"no message names {filename} line {line_no} for {bad_row!r}; output: {output!r}")
        for n in (expected_cells, actual_cells):
            if n is not None:
                self.assertTrue(any(re.search(rf"\b{n}\b", m) for m in found),
                                f"message for line {line_no} does not name the cell count {n}: {found!r}")

    # ---- the unmodified shapes are clean (these pass against the stub too; they are not evidence of the guard)

    def test_unmodified_backlog_and_decisions_fixtures_exit_zero(self):
        self.write()
        code, output = self.run_check()
        self.assertEqual(0, code, output)

    def test_decisions_legacy_row_without_reason_cell_exits_zero(self):
        rows = GOOD_DECISIONS_ROWS + ["| D-004 | 2026-10-19 | Legacy decision written before Reason existed |"]
        self.write(decisions_lines=decisions(rows))
        code, output = self.run_check()
        self.assertEqual(0, code, output)

    def test_escaped_pipe_inside_a_cell_is_not_a_separator(self):
        rows = GOOD_BACKLOG_ROWS + [r"| P0-09 | Parse a \| b | P0-01 | done | the \| is literal | commit ddd |"]
        self.write(backlog(rows))
        code, output = self.run_check()
        self.assertEqual(0, code, output)

    def test_separator_row_with_alignment_colons_still_opens_the_table(self):
        # The bad row is the witness that the table opened: were the colon separator not recognised, nothing
        # would be checked and the exit would be 0. The good rows prove the separator is not itself flagged.
        bad = "| P0-09 | Title | P0-01 | done | stray | pipe | commit ddd |"
        lines = backlog(GOOD_BACKLOG_ROWS + [bad])
        lines[lines.index(BACKLOG_SEP)] = "|:--- | ---: | :---: | --- | --- | --- |"
        self.write(lines)
        self.assert_rejects_row(lines, bad, "BACKLOG.md", expected_cells=6, actual_cells=7)

    def test_well_formed_rows_with_trailing_or_leading_spaces_exit_zero(self):
        rows = GOOD_BACKLOG_ROWS + ["| P0-09 | Title | P0-01 | done | the goal | commit ddd |   ",
                                    "  | P0-10 | Title | P0-01 | done | the goal | commit eee |"]
        self.write(backlog(rows))
        code, output = self.run_check()
        self.assertEqual(0, code, output)

    def test_header_row_without_its_closing_pipe_fails_and_names_its_line(self):
        bad_header = BACKLOG_HEADER[:-1].rstrip()          # the header defines the cell count; it must be sound
        lines = backlog(GOOD_BACKLOG_ROWS)
        lines[lines.index(BACKLOG_HEADER)] = bad_header
        self.write(lines)
        self.assert_rejects_row(lines, bad_header, "BACKLOG.md")

    def test_each_table_is_measured_against_its_own_header(self):
        second = ["", "## Other table", "", "| Key | Value |", "| --- | --- |", "| a | 1 |"]
        self.write(backlog(GOOD_BACKLOG_ROWS) + second)
        code, output = self.run_check()
        self.assertEqual(0, code, output)

    # ---- the three red cases of the packet

    def test_row_with_an_extra_unescaped_pipe_fails_and_names_its_line(self):
        bad = "| P0-09 | Title | P0-01 | done | stray | pipe | commit ddd |"   # 7 cells, closing pipe present
        lines = backlog(GOOD_BACKLOG_ROWS + [bad])
        self.write(lines)
        self.assert_rejects_row(lines, bad, "BACKLOG.md", expected_cells=6, actual_cells=7)

    def test_row_with_its_closing_pipe_removed_fails_and_names_its_line(self):
        bad = "| P0-09 | Title | P0-01 | done | the goal | commit ddd"          # 6 cells, no closing pipe
        lines = backlog(GOOD_BACKLOG_ROWS + [bad])
        self.write(lines)
        self.assert_rejects_row(lines, bad, "BACKLOG.md")

    def test_fix54_shaped_row_stray_separator_paid_by_missing_closing_pipe_fails(self):
        # 7 pipes, exactly like the header's 7, yet 7 cells against the header's 6: a stray separator in the
        # evidence text, paid for by the dropped closing pipe. A guard that counts pipes passes this row.
        bad = "| FIX-54 | Title | P0-01 | done | the goal | evidence one | evidence two"
        self.assertEqual(BACKLOG_HEADER.count("|"), bad.count("|"))  # the fixture's whole point
        lines = backlog(GOOD_BACKLOG_ROWS + [bad])
        self.write(lines)
        self.assert_rejects_row(lines, bad, "BACKLOG.md", expected_cells=6, actual_cells=7)

    # ---- the rest of the rule

    def test_backlog_row_with_too_few_cells_fails_even_with_its_closing_pipe(self):
        bad = "| P0-09 | Title | P0-01 | done | commit ddd |"                    # 5 cells: BACKLOG is exact
        lines = backlog(GOOD_BACKLOG_ROWS + [bad])
        self.write(lines)
        self.assert_rejects_row(lines, bad, "BACKLOG.md", expected_cells=6, actual_cells=5)

    def test_row_ending_in_an_escaped_pipe_has_no_closing_pipe_and_fails(self):
        bad = r"| P0-09 | Title | P0-01 | done | the goal | ends with a literal \|"   # 6 cells, last pipe escaped
        lines = backlog(GOOD_BACKLOG_ROWS + [bad])
        self.write(lines)
        self.assert_rejects_row(lines, bad, "BACKLOG.md")

    def test_decisions_row_with_too_many_cells_fails_and_names_its_line(self):
        bad = "| D-004 | 2026-10-19 | Decision | Reason | one more |"            # 5 cells against 4
        lines = decisions(GOOD_DECISIONS_ROWS + [bad])
        self.write(decisions_lines=lines)
        self.assert_rejects_row(lines, bad, "DECISIONS.md", expected_cells=4, actual_cells=5)

    def test_decisions_row_with_its_closing_pipe_removed_fails_despite_the_legacy_tolerance(self):
        bad = "| D-004 | 2026-10-19 | Decision | the reason"                     # 4 cells, no closing pipe
        lines = decisions(GOOD_DECISIONS_ROWS + [bad])
        self.write(decisions_lines=lines)
        self.assert_rejects_row(lines, bad, "DECISIONS.md")

    def test_decisions_fix54_shaped_row_fails(self):
        bad = "| D-004 | 2026-10-19 | Decision | reason one | reason two"        # 5 pipes = header's 5, 5 cells
        self.assertEqual(DECISIONS_HEADER.count("|"), bad.count("|"))
        lines = decisions(GOOD_DECISIONS_ROWS + [bad])
        self.write(decisions_lines=lines)
        self.assert_rejects_row(lines, bad, "DECISIONS.md", expected_cells=4, actual_cells=5)

    def test_bad_row_in_a_second_table_is_measured_against_that_tables_header(self):
        bad = "| a | 1 | 2 |"                                                    # 3 cells against 2
        lines = backlog(GOOD_BACKLOG_ROWS) + ["", "## Other table", "", "| Key | Value |", "| --- | --- |", bad]
        self.write(lines)
        self.assert_rejects_row(lines, bad, "BACKLOG.md", expected_cells=2, actual_cells=3)

    def test_every_bad_row_is_reported_in_one_run_across_both_files(self):
        bad_b1 = "| P0-09 | Title | P0-01 | done | stray | pipe | commit ddd |"
        bad_b2 = "| P0-10 | Title | P0-01 | done | the goal | commit eee"
        bad_d = "| D-004 | 2026-10-19 | Decision | Reason | one more |"
        b_lines = backlog(GOOD_BACKLOG_ROWS[:3] + [bad_b1] + GOOD_BACKLOG_ROWS[3:] + [bad_b2])
        d_lines = decisions(GOOD_DECISIONS_ROWS + [bad_d])
        self.write(b_lines, d_lines)
        code, output = self.run_check()
        self.assertNotEqual(0, code, output)
        for filename, lines, bad in (("BACKLOG.md", b_lines, bad_b1), ("BACKLOG.md", b_lines, bad_b2),
                                     ("DECISIONS.md", d_lines, bad_d)):
            line_no = lines.index(bad) + 1
            self.assertTrue(self.messages_for(output, filename, line_no),
                            f"{filename} line {line_no} not reported; output: {output!r}")

    def test_a_missing_ledger_file_fails_rather_than_passing_quietly(self):
        # A guard that exits 0 when its input is renamed, moved or deleted asserts something false.
        for missing in ("docs/DECISIONS.md", "agent/BACKLOG.md"):
            with self.subTest(missing=missing):
                self.write()
                (self.root / missing).unlink()
                code, output = self.run_check()
                self.assertNotEqual(0, code, f"expected non-zero with {missing} absent, got 0; output: {output!r}")
                self.assertIn(Path(missing).name, output, f"output does not name the missing {missing}: {output!r}")

    def test_a_well_formed_row_is_never_reported(self):
        bad = "| P0-09 | Title | P0-01 | done | stray | pipe | commit ddd |"
        lines = backlog(GOOD_BACKLOG_ROWS + [bad])
        self.write(lines)
        code, output = self.run_check()
        self.assertNotEqual(0, code, output)
        for good in GOOD_BACKLOG_ROWS:
            line_no = lines.index(good) + 1
            self.assertFalse(self.messages_for(output, "BACKLOG.md", line_no),
                             f"well-formed row on line {line_no} reported: {output!r}")


if __name__ == "__main__":
    unittest.main()
