# Daily overview

Runs once a day in the morning in a fresh session. It writes one short report for the owner as a
comment in the standing issue "Daily overview" of `subapavel/rail8`, and opens an issue when the
daily stack run failed. It changes no code and asks nobody anything. "Yesterday" is the 24 hours
before the run.

## 1. Gather

- **Built**: pull requests merged yesterday (`list_pull_requests`, state closed, `merged_at` in the
  window): number and title.
- **Owner-proxy**: comments the owner-proxy wrote yesterday on `owner-question` issues, and issues
  it closed. Note each decision in a few words, and every assumption it says waits for the owner's
  confirmation.
- **Open questions**: open issues labelled `owner-question` without `later`: number and title.
- **Daily run**: the latest scheduled run on `main` of each workflow `ci`, `mapping-ci`, `jq-ci` and
  `ui-ci` (`actions_list`, then the run's jobs): each job green or red. The job `nightly · quickstart`
  of `ci` runs the start commands of `docs/QUICKSTART.md`; when it is red, the quickstart is broken.
- **Waiting for Codex**: open issues labelled `codex-wanted`: number, title and the time the label
  was added.
- **Usage**: the number of sessions whose title starts with `dispatcher-worker:` created yesterday
  (`list_sessions` with `mine: true`), and `external_metadata.rate_limit_info` from `get_session`
  with no `session_id` (status, and the reset time from `resetsAt`).

## 2. A failed daily run

If a job in the latest scheduled run of `ci`, `mapping-ci`, `jq-ci` or `ui-ci` is red, and no open
issue already reports that run, open an issue (one per run; a red `nightly · stack` or
`nightly · quickstart` job is a start failure):

- title: `Daily run failed on <date>: <workflow> · <job>`
- labels: `jádro`, `krok-1`, `priority`, `class:B`
- body: the link to the run, the failed step, and the last lines of its log that show the error.

The dispatcher takes `priority` issues first, so the fix is the first task of the day.

## 3. Write

Write in Czech, at most ten lines, no abbreviations, plain words a person reads in a minute:
the first line is the state of the daily run on `main`: either "main is fine, this is how to start it"
(then the start commands of `docs/QUICKSTART.md`, in a few words) or "this task broke it, the fix is
running" (then the issue number and its title from step 2); then what was built, what the owner-proxy decided and which assumptions wait for confirmation, the open
questions, the issues waiting for Codex, the daily run job by job (with the new issue number if step 2 opened one), and usage.
Say "nic" for an empty part instead of leaving it out.

Find the open issue titled "Daily overview" with label `owner-report`; if there is none, create it
with that title, that label and the body "Denní přehled pro vlastníka, jeden komentář za den."
Add the report as a comment, headed with today's date.
