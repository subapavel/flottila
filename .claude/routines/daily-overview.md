# Daily overview

Runs once a day in the morning in a fresh session. It writes one short report for the owner as a
comment in the standing issue "Daily overview" of `subapavel/rail8`, and opens an issue when the
nightly stack run failed. It changes no code and asks nobody anything. "Yesterday" is the 24 hours
before the run.

## 1. Gather

- **Built**: pull requests merged yesterday (`list_pull_requests`, state closed, `merged_at` in the
  window): number and title.
- **Owner-proxy**: comments the owner-proxy wrote yesterday on `owner-question` issues, and issues
  it closed. Note each decision in a few words, and every assumption it says waits for the owner's
  confirmation.
- **Open questions**: open issues labelled `owner-question` without `later`: number and title.
- **Nightly run**: the latest scheduled run of each workflow `ci`, `mapping-nightly` and
  `jq-nightly` (`actions_list`, then the run's jobs): each job green or red.
- **Usage**: the number of sessions whose title starts with `dispatcher-worker:` created yesterday
  (`list_sessions` with `mine: true`), and `external_metadata.rate_limit_info` from `get_session`
  with no `session_id` (status, and the reset time from `resetsAt`).

## 2. A failed stack start

If the job whose name starts with `nightly · stack` in the latest scheduled `ci` run is red, and no
open issue already reports that run, open an issue:

- title: `Nightly stack start failed on <date>`
- labels: `jádro`, `krok-1`, `priority`, `class:B`
- body: the link to the run, the failed step, and the last lines of its log that show the error.

The dispatcher takes `priority` issues first, so the fix is the first task of the day.

## 3. Write

Write in Czech, at most ten lines, no abbreviations, plain words a person reads in a minute:
what was built, what the owner-proxy decided and which assumptions wait for confirmation, the open
questions, the nightly run job by job (with the new issue number if step 2 opened one), and usage.
Say "nic" for an empty part instead of leaving it out.

Find the open issue titled "Daily overview" with label `owner-report`; if there is none, create it
with that title, that label and the body "Denní přehled pro vlastníka, jeden komentář za den."
Add the report as a comment, headed with today's date.
