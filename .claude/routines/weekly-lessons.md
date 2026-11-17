# Weekly lessons

Runs once a week in a short fresh session. It looks for causes that keep coming back in the last
seven days of work in `subapavel/rail8` and fixes each in the cheapest durable way. It asks the
owner only through an `owner-question` issue.

## 1. Gather the week

- Pull requests where the `reviewer` returned FAIL (a review comment or a fix commit after one).
- Issues that got `failed-once` or `blocked` this week, with the reason written in them.
- Merged pull requests that fix a bug: what broke, and why the earlier change let it through.

## 2. Find what repeats

Group the findings by cause. A cause repeats when it appears in two or more of them. Ignore
one-offs.

## 3. Act on each repeating cause, in this order

1. **An automatic check**, if a test, a lint or a guard in CI can catch it: a small pull request
   with that check and a test that shows it catches the case, following `AGENTS.md`.
2. **A rule in `AGENTS.md`**, if no check can catch it: a small pull request that adds or changes
   one rule (skill `writing-for-agents`).
3. **An owner question**, if neither fits or the fix is a choice only the owner can make: an issue
   labelled `owner-question` stating the cause, the evidence and the options.

At most three actions a week; take the causes that cost the most.

## 4. Report

Add one comment to the open issue "Daily overview" (label `owner-report`; create it as
`daily-overview.md` says if missing): in Czech, at most six lines, the causes found and what was
done for each, with pull request and issue numbers. If nothing repeated, say so in one line.
