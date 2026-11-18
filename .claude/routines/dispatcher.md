# Dispatcher

Runs once an hour in a fresh session. It starts one worker session per idle stream for the next
ready issue in `subapavel/rail8`, and cleans up workers that stopped. It changes no code. It never
asks the owner anything: what needs the owner carries `owner-question`, and the dispatcher skips it.
All times are UTC.

## 1. Weekly limit

Call `mcp__claude-code-remote__get_session` with no `session_id` and read
`external_metadata.rate_limit_info.status`:

- `allowed`: normal run.
- `allowed_warning`: the same as `allowed`. The owner accepts that the weekly limit may run out
  before it resets.
- anything else, or the field is missing: start nothing. Still do steps 2 and 3 (stalled workers), then stop.

## 2. What is running

Workers are the sessions this routine started. Their title starts with `dispatcher-worker:` and
they carry the tags `dispatcher-worker` and `stream:<stream>`.

- `list_sessions` with `mine: true` (the `tags` filter is not available inside a session), paging
  with `after_id` until the sessions are older than 24 hours. Keep the rows whose title starts with
  `dispatcher-worker:`; when in doubt, `get_session` shows the tags.
- A worker is **running** when its `status_bucket` is working or blocked.
- `list_issues` with label `in-progress`, state open.

A stream is busy when a running worker has its `stream:` tag, or an open issue with its label
carries `in-progress`.

## 3. Stalled and failed workers

For each worker whose issue is still open and labelled `in-progress`:

- **Stalled**: running, and `updated_at` more than 3 hours ago.
- **Failed**: `status_bucket` is failed, or it is completed or review_ready with `updated_at` more
  than 3 hours ago and the issue has no open pull request from it.

For a stalled or failed worker: `archive_session`; on the issue, a comment saying the session
stopped and why (one or two sentences, with its session id), remove `in-progress`, and add
`failed-once`, or `blocked` when the issue already has `failed-once`.

## 4. Pick and start

Streams, each with its label: `jádro`, `ui`, `mapování`, `jq`. For each stream that is not busy,
while fewer than two workers run in total, start one worker as below. When streams have no ready
issue and fewer than two workers run, start a further worker in the stream with the most ready
issues, on an issue that touches a different part of the code than the worker already running there.

1. Pick one ready issue: open, has the stream's label, and has none of `in-progress`,
   `owner-question`, `later`, `blocked`. Order: `priority` first, then `krok-1`, then the lowest
   number. Skip an issue another stream picked in this run.
2. Class: read the issue's `class:A`, `class:B` or `class:C` label. With none, pick the class by
   the table in `AGENTS.md`, add the label, and when torn between two take the higher one.
3. Add `in-progress` to the issue.
4. `create_session` with:
   - `source_url`: `https://github.com/subapavel/rail8`
   - `title`: `dispatcher-worker: #N <stream> <issue title>`
   - `tags`: `["dispatcher-worker", "stream:<stream>"]`
   - `model` and `effort` from the class: `class:A` `claude-sonnet-5-5`, medium; `class:B`
     `claude-opus-5-5`, high; `class:C` `claude-opus-5-5`, xhigh.
   - `prompt` (replace N):

     > Solve issue #N in subapavel/rail8 following AGENTS.md on main. The owner is away: do not
     > ask them; what only the owner can decide goes to the owner-proxy as AGENTS.md says. Open a
     > pull request whose description contains "Closes #N". After it is merged, remove the label
     > `in-progress` from the issue. If you cannot finish, write in the issue why, remove
     > `in-progress`, and add `failed-once`, or `blocked` if the issue already has `failed-once`.

   If `create_session` fails, remove `in-progress` again and leave the issue as it was.

## 5. End

Reply with at most five lines: limit status, workers running, workers started (issue, stream),
workers stopped, and why nothing was started where a stream stayed idle.
