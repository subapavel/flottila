"""Tests for ci/ledger_check.py — the table-shape guard on docs/archive/agent/BACKLOG.md and docs/DECISIONS.md.

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
        (self.root / "docs" / "archive" / "agent").mkdir(parents=True)

    def tearDown(self):
        self.tmp.cleanup()

    def write(self, backlog_lines=None, decisions_lines=None):
        (self.root / "docs" / "archive" / "agent" / "BACKLOG.md").write_text(
            "\n".join(backlog_lines if backlog_lines is not None else backlog(GOOD_BACKLOG_ROWS)) + "\n")
        (self.root / "docs" / "DECISIONS.md").write_text(
            "\n".join(decisions_lines if decisions_lines is not None else decisions(GOOD_DECISIONS_ROWS)) + "\n")

    def run_check(self):
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = lc.main(["--root", str(self.root)])
        return code, out.getvalue() + err.getvalue()

    @staticmethod
    def summary_lines(output):
        """The run's summary line(s): `ledgers: well-formed` or `ledgers: <n> problem(s)`, where n counts every
        reported problem — each badly shaped row and each duplicated id (one entry per id, not per row)."""
        return [m for m in output.splitlines() if m.startswith("ledgers:")]

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
        for missing in ("docs/DECISIONS.md", "docs/archive/agent/BACKLOG.md"):
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

    # ---- duplicate ids
    #
    # Two parallel sessions each read the same highest id and each allocate the next one; the merge is the
    # first moment one file holds both rows, so the merge is where a machine can see it. Within one ledger
    # file no id may be the id of more than one row. The id of a row is its first cell: an id named in a
    # Depends-on cell, in a title or in prose is not an allocation.
    #
    # Padding: neither real ledger contains a pair of ids differing only by padding (checked by hand over
    # docs/DECISIONS.md and docs/archive/agent/BACKLOG.md on 2026-11-15), but an id cell is the cell's text stripped, so
    # two rows that pad the same id differently are the same id; one case below pins that.

    @staticmethod
    def mentions_id(message, ledger_id):
        """The message names this whole id. `D-020` does not match inside `D-020a`, nor `D-02` inside `D-020`."""
        return re.search(rf"(?<![\w-]){re.escape(ledger_id)}(?![\w-])", message) is not None

    def duplicate_messages(self, output, filename, ledger_id):
        return [m for m in output.splitlines() if filename in m and self.mentions_id(m, ledger_id)]

    def assert_reports_duplicate(self, output, filename, ledger_id, line_nos):
        """At least one message names `filename` and the whole id, and across those messages every one of
        the occurrence line numbers is named — naming one of them is not enough."""
        found = self.duplicate_messages(output, filename, ledger_id)
        self.assertTrue(found, f"no message names {filename} and the duplicate id {ledger_id}; output: {output!r}")
        named = {n for n in line_nos if any(re.search(rf"\b{n}\b", m) for m in found)}
        self.assertEqual(set(line_nos), named,
                         f"messages for duplicate {ledger_id} in {filename} name lines {sorted(named)}, "
                         f"expected every occurrence {sorted(line_nos)}: {found!r}")

    def test_two_decisions_rows_with_the_same_id_are_reported_with_the_id_and_both_line_numbers(self):
        first = "| D-004 | 2026-10-19 | Valkey for the rate limiter | tempo only |"
        second = "| D-004 | 2026-10-19 | Saxon HE for XSLT 3.0 | MPL 2.0, no new runtime |"
        lines = decisions(GOOD_DECISIONS_ROWS + [first, second])
        self.write(decisions_lines=lines)
        code, output = self.run_check()
        self.assertNotEqual(0, code, f"two rows share id D-004, expected non-zero; output: {output!r}")
        self.assert_reports_duplicate(output, "DECISIONS.md", "D-004",
                                      [lines.index(first) + 1, lines.index(second) + 1])

    def test_three_backlog_rows_with_the_same_id_are_all_named(self):
        dups = [f"| P0-09 | Title {n} | P0-01 | ready | the goal | commit {n} |" for n in ("one", "two", "three")]
        lines = backlog(GOOD_BACKLOG_ROWS + dups)
        self.write(lines)
        code, output = self.run_check()
        self.assertNotEqual(0, code, f"three rows share id P0-09, expected non-zero; output: {output!r}")
        self.assert_reports_duplicate(output, "BACKLOG.md", "P0-09", [lines.index(d) + 1 for d in dups])

    def test_d020_and_d020a_are_different_ids_while_a_real_duplicate_beside_them_is_reported(self):
        # D-020 / D-020a is a real pair in docs/DECISIONS.md: a check that compares id prefixes flags it and is
        # wrong. The genuine D-031 duplicate in the same table is the witness that the check ran at all.
        suffixed = ["| D-020 | 2026-10-17 | The Vert.x surface api and core may use | closed and reviewable |",
                    "| D-020a | 2026-10-18 | Primitive arrays count as JDK | correctness fix of D-020 |",
                    "| D-020b | 2026-10-19 | Records count as JDK too | same reason |"]
        dup_a = "| D-031 | 2026-10-20 | Body journal keyed by message id | D-070 |"
        dup_b = "| D-031 | 2026-10-20 | Deployment archive keyed by sha256 | content addressed |"
        lines = decisions(GOOD_DECISIONS_ROWS + suffixed + [dup_a, dup_b])
        self.write(decisions_lines=lines)
        code, output = self.run_check()
        self.assertNotEqual(0, code, f"two rows share id D-031, expected non-zero; output: {output!r}")
        self.assert_reports_duplicate(output, "DECISIONS.md", "D-031",
                                      [lines.index(dup_a) + 1, lines.index(dup_b) + 1])
        for not_a_duplicate in ("D-020", "D-020a", "D-020b"):
            self.assertFalse(self.duplicate_messages(output, "DECISIONS.md", not_a_duplicate),
                             f"{not_a_duplicate} reported though no other row carries that id: {output!r}")

    def test_an_id_carried_only_in_another_rows_depends_on_cell_is_not_a_duplicate(self):
        # P0-01 is the id of one row and the Depends-on of three others; a check that scans whole lines, or any
        # cell rather than the first, turns every dependency edge into a duplicate.
        self.assertGreater(sum(1 for r in GOOD_BACKLOG_ROWS if r.split("|")[3].strip() == "P0-01"), 1)
        self.write()
        code, output = self.run_check()
        self.assertEqual(0, code, f"the well-formed ledgers must stay clean; output: {output!r}")
        self.assertEqual("ledgers: well-formed", output.strip(), f"unexpected extra output: {output!r}")

    def test_the_repeated_id_header_of_several_tables_in_one_file_is_not_a_duplicate_id(self):
        # docs/archive/agent/BACKLOG.md really holds six tables, each headed `| Id | ... |` over the same separator; header
        # and separator rows are not rows that allocate an id. Three tables, so the word `Id` heads three rows:
        # with only two, a check that lets a table stay open across the prose between tables (and so reads the
        # next header as a data row allocating the id `Id`) sees that id once, finds no duplicate and passes.
        lines = (backlog(GOOD_BACKLOG_ROWS)
                 + ["", "## Second table", ""]
                 + backlog(["| P1-01 | Flow builder | P0-05 | ready | flows build | — |"], prelude=())
                 + ["", "## Third table", ""]
                 + backlog(["| P2-01 | Deployments | P1-01 | ready | packages deploy | — |"], prelude=()))
        self.assertEqual(3, lines.count(BACKLOG_HEADER))   # the fixture's whole point: one repeated header
        self.write(lines)
        code, output = self.run_check()
        self.assertEqual(0, code, f"three tables with the same header are not duplicate ids; output: {output!r}")
        self.assertEqual("ledgers: well-formed", output.strip(), f"unexpected extra output: {output!r}")

    def test_cell_shape_problems_are_reported_unchanged_beside_a_duplicate_id(self):
        # The new check must not swallow, renumber or reorder the shape output. The two expected strings are the
        # observed output of the guard before the duplicate check existed, with only the line numbers filled in.
        shape_b = "| P0-10 | Title | P0-01 | done | stray | pipe | commit ddd |"          # 7 cells against 6
        dup_b1 = "| P0-09 | Admission bytes | P0-05 | ready | bytes counted | — |"
        dup_b2 = "| P0-09 | Resequencer | P0-03 | ready | order held | — |"
        shape_d = "| D-005 | 2026-10-20 | Decision | Reason | one more |"                  # 5 cells against 4
        dup_d1 = "| D-004 | 2026-10-19 | Valkey for the rate limiter | tempo only |"
        dup_d2 = "| D-004 | 2026-10-19 | Saxon HE for XSLT 3.0 | MPL 2.0 |"
        b_lines = backlog(GOOD_BACKLOG_ROWS + [dup_b1, shape_b, dup_b2])
        d_lines = decisions(GOOD_DECISIONS_ROWS + [dup_d1, shape_d, dup_d2])
        self.write(b_lines, d_lines)
        code, output = self.run_check()
        self.assertNotEqual(0, code, output)
        expected_shape = [
            f"LEDGER SHAPE: docs/archive/agent/BACKLOG.md:{b_lines.index(shape_b) + 1}: expected 6 cells, found 7 "
            f"(wrong cell count)",
            f"LEDGER SHAPE: docs/DECISIONS.md:{d_lines.index(shape_d) + 1}: expected at most 4 cells, found 5 "
            f"(wrong cell count)",
        ]
        self.assertEqual(expected_shape, [m for m in output.splitlines() if "cells, found" in m],
                         f"the cell-shape output changed; full output: {output!r}")
        self.assert_reports_duplicate(output, "BACKLOG.md", "P0-09",
                                      [b_lines.index(dup_b1) + 1, b_lines.index(dup_b2) + 1])
        self.assert_reports_duplicate(output, "DECISIONS.md", "D-004",
                                      [d_lines.index(dup_d1) + 1, d_lines.index(dup_d2) + 1])
        # Four problems, counted off the fixture: the two badly shaped rows (shape_b, shape_d) and the two
        # duplicated ids (P0-09 in BACKLOG, D-004 in DECISIONS — one entry per id, though each spans two rows).
        # A summary that counts only the shape problems says `2` here while the run reports four.
        self.assertEqual(["ledgers: 4 problem(s)"], self.summary_lines(output),
                         f"the summary must count shape problems and duplicate ids together: {output!r}")

    def test_duplicate_ids_in_both_ledgers_are_reported_in_one_run(self):
        # Six-cell rows and four-cell rows: the check covers both files, not only the one it was written for.
        b1 = "| FIX-209 | Duplicate-id guard | P0-01 | ready | duplicates reported | — |"
        b2 = "| FIX-209 | Ledger CI wiring | P0-02 | ready | job runs the guard | — |"
        d1 = "| D-144 | 2026-11-14 | Bodies keyed by message id and step | D-070 |"
        d2 = "| D-144 | 2026-11-14 | Kafka only as an add-on component | no core Kafka client |"
        b_lines = backlog(GOOD_BACKLOG_ROWS + [b1, b2])
        d_lines = decisions(GOOD_DECISIONS_ROWS + [d1, d2])
        self.write(b_lines, d_lines)
        code, output = self.run_check()
        self.assertNotEqual(0, code, output)
        self.assert_reports_duplicate(output, "BACKLOG.md", "FIX-209",
                                      [b_lines.index(b1) + 1, b_lines.index(b2) + 1])
        self.assert_reports_duplicate(output, "DECISIONS.md", "D-144",
                                      [d_lines.index(d1) + 1, d_lines.index(d2) + 1])
        # Two problems, counted off the fixture: every row here is well shaped, so the count is exactly the two
        # duplicated ids, FIX-209 and D-144. A summary blind to duplicates calls this run `well-formed` while it
        # exits non-zero — a report nobody can act on, and the state CI shows for the collision this row guards.
        self.assertEqual(["ledgers: 2 problem(s)"], self.summary_lines(output),
                         f"a run whose only problems are duplicate ids must not be summarised as clean: {output!r}")

    def test_the_same_id_in_two_different_tables_of_one_file_is_a_duplicate(self):
        # The rule is "within one ledger file", not "within one table": docs/archive/agent/BACKLOG.md holds six tables, and
        # two sessions that each append to a different table (a phase table and a fix table) are exactly the
        # collision this guard exists for. A check that keys its occurrences per table never sees this pair.
        first = "| P0-09 | Admission bytes | P0-05 | ready | bytes counted | — |"
        second = "| P0-09 | Resequencer | P0-03 | ready | order held | — |"
        lines = (backlog(GOOD_BACKLOG_ROWS + [first])
                 + ["", "## Phase 1", ""]
                 + backlog([second, "| P1-01 | Flow builder | P0-05 | ready | flows build | — |"], prelude=()))
        # The fixture's whole point: a second header (and its separator) stands between the two rows, so they
        # are rows of two different tables, not two rows of one.
        self.assertLess(lines.index(first), lines.index(BACKLOG_HEADER, lines.index(first)))
        self.assertLess(lines.index(BACKLOG_HEADER, lines.index(first)), lines.index(second))
        self.write(lines)
        code, output = self.run_check()
        self.assertNotEqual(0, code, f"two tables of one file share id P0-09, expected non-zero; output: {output!r}")
        self.assert_reports_duplicate(output, "BACKLOG.md", "P0-09",
                                      [lines.index(first) + 1, lines.index(second) + 1])

    def test_two_rows_padding_the_same_id_differently_are_one_id_and_are_reported(self):
        # The id of a row is its first cell stripped of surrounding whitespace, so `|D-004|` and `| D-004 |`
        # allocate the same id. A check that compares the raw cell text sees two different ids and passes the
        # collision through. The two rows are written with deliberately different padding; nothing else differs.
        tight = "|D-004|2026-10-19|Valkey for the rate limiter|tempo only|"
        padded = "|   D-004   | 2026-10-19 | Saxon HE for XSLT 3.0 | MPL 2.0, no new runtime |"
        self.assertNotEqual(tight.split("|")[1], padded.split("|")[1])  # the fixture's whole point
        lines = decisions(GOOD_DECISIONS_ROWS + [tight, padded])
        self.write(decisions_lines=lines)
        code, output = self.run_check()
        self.assertNotEqual(0, code, f"two rows pad the same id D-004, expected non-zero; output: {output!r}")
        self.assert_reports_duplicate(output, "DECISIONS.md", "D-004",
                                      [lines.index(tight) + 1, lines.index(padded) + 1])

    def test_several_rows_with_a_blank_first_cell_are_continuations_and_never_collide_as_one_id(self):
        # A continuation row leaves its id cell empty; its text belongs to the row above. Such rows allocate no
        # id, so any number of them may stand in one table. A check that treats the empty cell as an id reports
        # them as duplicates of each other and fails every ledger that uses continuations.
        blank = ["|  | 2026-10-21 | continued: the second half of the reason | — |",
                 "|  | 2026-10-21 | continued: a third line of the same reason | — |",
                 "|| 2026-10-22 | a continuation with no padding at all | — |"]
        lines = decisions(GOOD_DECISIONS_ROWS + blank)
        self.write(decisions_lines=lines)
        code, output = self.run_check()
        self.assertEqual(0, code, f"blank first cells are continuations, not ids; output: {output!r}")
        self.assertEqual("ledgers: well-formed", output.strip(), f"unexpected extra output: {output!r}")
        # Witness that those three rows really sit inside the table and really reach the duplicate check: give
        # them all the same non-blank id and the very same fixture must be reported. Without this, the silence
        # above would also be the silence of rows the check never looked at.
        named = [r.replace("|  |", "| D-900 |", 1).replace("||", "| D-900 |", 1) for r in blank]
        named_lines = decisions(GOOD_DECISIONS_ROWS + named)
        self.write(decisions_lines=named_lines)
        code, output = self.run_check()
        self.assertNotEqual(0, code, f"three rows share id D-900, expected non-zero; output: {output!r}")
        self.assert_reports_duplicate(output, "DECISIONS.md", "D-900", [named_lines.index(r) + 1 for r in named])


if __name__ == "__main__":
    unittest.main()
