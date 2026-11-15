# Packet BRAND-6 (class B) — the website at rail8.dev

Row: `agent/BACKLOG.md` BRAND-6. The owner, 2026-11-15, in the session: the domain `rail8.dev` is registered, the
repository is renamed to `subapavel/rail8`, "přidej mi tam rovnou stránku website na github rail8.dev" - a website for
`rail8.dev`, served by GitHub Pages from this repository.

What a session does: the page, its checks and the workflow that deploys it. What only the owner does: switch Pages on
(Settings, Pages, Source "GitHub Actions"), set the custom domain there, and the DNS records at the registrar. A custom
domain of a Pages site deployed by a workflow is a repository setting; no `CNAME` file is needed and none is added.

## The page

One static page, English, no build tool, no framework, no script.

- `site/index.html`, `site/style.css`, `site/404.html`. Nothing is loaded from another origin: no font, script, style,
  image or tracker from elsewhere. System font stack. Light and dark by `prefers-color-scheme`. Readable from 320 px.
- Brand files are not copied into `site/`: bytes exist once. `ci/site_build.py <out-dir>` assembles the site - the
  files of `site/` and, under `<out-dir>/brand/`, the files of `docs/brand/` the page uses (logo for light and dark,
  mark, favicons, touch icon). The page refers to them as `brand/<file>`.
- The copy, and nothing beyond it (source: the owner's line below, `README.md`, `docs/QUICKSTART.md`, the design rules):
  - name and one line - the owner's choice of 2026-11-15, word for word, the same text as the repository's description
    on GitHub (the owner refused "where monitoring is the product" as the line; do not write that phrase on the page):
    "Rail8 — a modern open-source alternative to legacy integration middleware: Java 25 virtual threads, Vert.x 5, fast
    by design, every message traceable end to end. Apache 2.0." It is the `<h1>`'s sentence (name as the heading, the
    rest as its lead) and, whole, the description meta.
  - what that means, two paragraphs, these sentences (review round 1: the first wording said more than the design -
    a body below the tree limit may be held whole, and a body above the recording threshold is carried, not
    recorded, D-110): "Every message in flight runs on its own virtual thread, and a large message body is streamed,
    not held whole in memory. “Fast by design” describes how Rail8 is built, not a measured number." and "Every
    message and every step is recorded and searchable, with its payload up to a recording limit, so each message can
    be followed from the moment it arrives to the moment it leaves." Do not write "replay" in any form (D-156: it
    left the architecture). Name no other product, and compare with none.
  - the stack: Java 25, Vert.x 5.1, PostgreSQL, ClickHouse, Valkey, S3. Licence: Apache 2.0.
  - the status, plainly: under construction, no release yet, not usable in production.
  - links: the repository `https://github.com/subapavel/rail8`, the quick start
    `https://github.com/subapavel/rail8/blob/main/docs/QUICKSTART.md`, the architecture document the same way.
  - a footer: the licence and the year.
  Claim nothing beyond that line and the README: no customer, no benchmark number, no "fastest", no feature list, no
  screenshot, no date.
- `<html lang="en">`, a `<title>`, a description meta, a viewport meta, `<link rel="canonical" href="https://rail8.dev/">`,
  the favicons, every image with `alt`, headings in order, colour contrast at WCAG AA. The logo's colours are in
  `docs/brand/rail8-logo.svg` (blue `#0066cc`, near-black `#151515`); take the palette from the brand files.

## The checks — red first (test-author)

`ci/site_check.py <dir>` prints one line per problem and exits 1 when there is one, 0 otherwise. Rules, each with a
red and a green case in `ci/test_site.py` (unittest, as `ci/test_name_guard.py`; fixtures built in a temp dir):

1. `index.html` exists in `<dir>`.
2. Every local reference of every HTML and CSS file resolves to a file inside `<dir>`: `href`, `src`, `srcset`, CSS
   `url(...)`. Fragments and `mailto:` are not files.
3. Nothing is loaded from another origin: a `<script src>`, `<link href>` other than `rel="canonical"`, `<img src>`,
   `<source>`, `<iframe>`, CSS `url(...)` or `@import` that names `http://`, `https://` or `//` is a problem.
   `<a href>` to another origin is allowed.
4. No `<script>` element at all.
5. Each HTML file has `<html lang>`, a non-empty `<title>`, a viewport meta; `index.html` has the canonical link
   `https://rail8.dev/` and a non-empty description meta; every `<img>` has an `alt` attribute.
6. No file of the site holds the word "replay" (any case).
One more case builds the real site with `ci/site_build.py` into a temp dir and checks it: red today (no `site/`,
no build script). Standard library only (`html.parser`); no dependency.

## The implementation (implementer)

`site/` as above; `ci/site_build.py`; `ci/site_check.py` if the test-author left only its contract (the test-author
writes the tests, the implementer the two scripts); `.github/workflows/site.yml`:
- on `push` to `main` with paths `site/**`, `docs/brand/**`, `ci/site_*.py`, `ci/test_site.py`, the workflow file; and
  `workflow_dispatch`.
- one job checks: `python3 -m unittest ci.test_site`, `python3 ci/site_build.py _site`, `python3 ci/site_check.py _site`;
  then the Pages deployment with GitHub's own actions (`actions/configure-pages`, `actions/upload-pages-artifact`,
  `actions/deploy-pages`), pinned by major version as `ci.yml` pins its actions; `permissions`: `contents: read`,
  `pages: write`, `id-token: write`; `concurrency` group `pages`, no cancel in progress; environment `github-pages`.
  Look the actions' current major versions up (their README on GitHub, or Context7); if unreachable say so and name
  the versions you used and why.
- `ci.yml` also runs `python3 -m unittest ci.test_site` beside the name guard's test, so a broken page fails `ci`.
The name guard (`python3 ci/name_guard.py`) stays clean.

## Paths

`site/**`, `ci/site_build.py`, `ci/site_check.py`, `ci/test_site.py`, `.github/workflows/site.yml`, the step in
`.github/workflows/ci.yml` that runs the guard's self-test (its name and one line), one line in `.gitignore`
(`/_site/`, review round 1). Nothing under `platform/`, `mapping/`, `docs/` (the brand files are read, not changed).

## Hand-back (at most 40 lines)

- Test-author: the cases' names; the red (`python3 -m unittest ci.test_site`, its last lines, the log path under
  `agent/work/`), `EXECUTED_FAIL`; what in this packet could not be checked mechanically.
- Implementer: the files; the green of the unit tests and of the check on the built site (log paths, `VERIFIED_PASS`);
  a screenshot of the built page at 1280 px and at 390 px, light and dark (Chromium and Playwright are installed:
  `require('playwright')`, `chromium.launch()`; serve the built dir with `python3 -m http.server`), saved under
  `agent/work/BRAND-6/`; the actions' versions and where they were looked up; `git diff --stat`; the diff saved as
  `agent/work/BRAND-6.diff` (include untracked files: `git add -N` first).

## The owner's steps (never a session's)

Given to the owner in the session, in Czech, on 2026-11-15. The DNS values are GitHub's documented ones, written from
knowledge: `docs.github.com` and DNS are not reachable from the session, so the owner compares them with the page
"Managing a custom domain for your GitHub Pages site" before saving.

1. GitHub, repository `subapavel/rail8`: Settings, Pages, "Build and deployment", Source: "GitHub Actions".
2. Actions, workflow `site`, "Run workflow" on `main` (or the next push that touches `site/`). When it is green the page
   is at `https://subapavel.github.io/rail8/` - without styles on the 404 page until the custom domain is set.
3. Cloudflare, DNS of `rail8.dev`, every record "DNS only" (grey cloud), not proxied:
   - `A` `@` to `185.199.108.153`, `185.199.109.153`, `185.199.110.153`, `185.199.111.153` (four records);
   - `AAAA` `@` to `2606:50c0:8000::153`, `2606:50c0:8001::153`, `2606:50c0:8002::153`, `2606:50c0:8003::153`;
   - `CNAME` `www` to `subapavel.github.io`.
4. GitHub, Settings, Pages, "Custom domain": `rail8.dev`, Save; wait for "DNS check successful".
5. The same page: tick "Enforce HTTPS" once GitHub has issued the certificate (minutes to an hour). `.dev` is
   HTTPS-only in every browser, so the page does not open before that.
6. The repository's About box: Description - the line of this packet; Website - `https://rail8.dev`.
Optional, recommended by GitHub: verify the domain for Pages (account Settings, Pages, "Add a domain") so that no
other account can claim it.

## Review round 1 (FAIL, 2026-11-15) - the fix packet

Blocking, both the test-author's (`ci/test_site.py`); no rule of the checker is wrong:
1. `test_rule2_a_root_relative_reference_resolves_against_the_site_directory` (:224-229) passes for the wrong reason:
   both references come from `404.html` at the site's root, where the root and the referrer's directory are one. Add
   a page in a subdirectory (`docs/page.html`) whose `/style.css` and `/index.html` are clean and whose
   `/nothere.html` is the only problem, named `docs/page.html: `.
2. `test_the_description_meta_of_index_holds_the_whole_line` (:518-523) pins four phrases, not the line: assert that
   the whitespace-normalised description equals the owner's line, a literal copied from this packet.
Taken from the remarks, the test-author's: a case each for what the checker reads beyond the first contract - an inline
`<style>` and a `style=""` attribute whose `url()` names another origin are problems (rule 3) and a local one that is
missing is a problem (rule 2); a `url()` inside a CSS comment is no reference. The module docstring says so.
Taken from the remarks, the implementer's:
- the copy: the two paragraphs as "The page" now has them (above);
- `.gitignore`: `/_site/`, so a local build cannot be added by accident;
- `site.yml` in two jobs: `build` (the self-test, the build, the check, `actions/configure-pages`,
  `actions/upload-pages-artifact`) and `deploy` (`needs: build`; `pages: write`, `id-token: write`; the environment;
  `actions/deploy-pages`) - the tests no longer run with the deployment's token. This supersedes "The implementation"
  above (one job, the three permissions at the top): do not restore it. As built, the build job has `contents: read`
  and `pages: read` - the second because `actions/configure-pages` reads the Pages site
  (`GET /repos/{owner}/{repo}/pages`, read in its source at tag v6); a run with `contents: read` alone was not tried;
- the `ci.yml` step's name covers the site's self-test.
Accepted as they are, stated in the evidence: `ci/site_build.py`'s refusals (a non-empty out-dir, a `site/brand/`
directory, a `CNAME`, a missing brand file) have no case; the 404 page's root-relative references break at
`subapavel.github.io/rail8/` until the custom domain is set.
A row, not this task (BRAND-8): what the checker would miss if the page grew - `<base href>`, `poster`, `object data`,
`embed`/`audio`/`track`/`input src`, `xlink:href`, `imagesrcset`, `image-set()`, `on*=` handlers and `javascript:` URLs,
an inline `<svg><title>` as a page title, upper-case schemes, a `srcset` URL with a comma.
Evidence owed to round 2 (verifier): hand mutants of the real `ci/site_check.py`, one run of the unit tests each, the
file restored after each - M1 :58 `base = referrer.parent`; M2 :42 without the `"//"` test; M3 :92 without the
canonical exemption; M4 :96 `not a.get("alt")`; M5 :163 without `if rel == "index.html":`; M6 :187 `FORBIDDEN in data`;
M7 :50 `[value]` (line numbers as in round 1's file); the tags of the three Pages actions as `git ls-remote --tags`
prints them; the build and the check with their command lines; `git status --short`.
The reviewer's request that the architect write its memory file: declined, as before - a session does not edit
`.claude/`; the lesson is in this section.

Round 2 (2026-11-15): PASS. Nine hand mutants of the checker, all killed (`agent/work/BRAND-6-evidence.txt`, section 4).
Not checkable before the first run: whether `actions/deploy-pages@v5` needs more than `pages: write` and `id-token: write`.
