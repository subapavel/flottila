# F-<n> — <short title>

Date: <YYYY-MM-DD> · Task: <backlog id> · ARCHITECTURE section: §<n> (version <v3.x>)

- **Assumption in the document:** <quote or paraphrase the exact claim, with the § reference>
- **How it fails in practice:** <the concrete scenario met while implementing or measuring; what
  breaks, who would notice in production>
- **Severity:** blocking | major | minor
- **Evidence:** <test name, measurement in bench/RESULTS.md, javap output, library behaviour —
  something observed, not remembered>
- **Change made:** <the smallest change that keeps the documented intent; what was implemented
  instead, and what stays as designed>
- **ARCHITECTURE.md edit:** <changelog line added; new version number> — or "none (minor)"
- **PROGRESS entry:** <task id> marked `Deviation: F-<n>`
- **For the owner's review:** <one sentence on what to look at, and whether a better alternative
  was considered and rejected>

Findings are numbered in creation order across the whole project. Severity `blocking` = the
phase gate cannot be met as designed; `major` = a documented mechanism is replaced while the
intent is kept; `minor` = the document is imprecise, code follows the intent, no mechanism changes.
