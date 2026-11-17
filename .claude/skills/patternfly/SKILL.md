---
name: patternfly
description: PatternFly 6 rules for Rail8's UI — build screens only from PatternFly React components and design tokens, looked up through the PatternFly MCP server. Use when writing, reviewing or styling any UI code.
---

<!-- Sources: the PatternFly MCP server, npm `@patternfly/patternfly-mcp` 2.6.0, repository
     github.com/patternfly/patternfly-mcp, MIT licence (Red Hat). The files in `upstream/` are
     PatternFly's rules for AI coders, copied unchanged from github.com/patternfly/patternfly-ai-coding
     `docs/guidelines/` at commit ba8165d906a8dd5ac20fdeb4d7de8e596129b499, MIT licence
     (`upstream/LICENSE`). That repository is archived; its successor is github.com/rh-uxd/ai-helpers
     (MIT), installable as a Claude Code plugin: `claude plugins marketplace add rh-uxd/ai-helpers`,
     then `claude plugins install patternfly@uxd-ai-helpers`. -->

The UI uses PatternFly 6 (`@patternfly/react-core`, `react-icons`, `react-table`; the versions are
in `mapping/ui/package.json`).

## Rules

- Build every screen from PatternFly components. Compose them; write a custom component only when
  no PatternFly component or composition fits, and then build it from PatternFly parts.
- Colours, spacing, fonts, borders and shadows come from PatternFly design tokens
  (`var(--pf-t--global--…)`). No hex, rgb or pixel values of your own, no overriding a
  component's internal classes.
- Use component props and variants for appearance before any CSS.
- Accessibility: every control has a label, works from the keyboard and keeps PatternFly's ARIA
  attributes.

## Look it up

The PatternFly MCP server (`.mcp.json`, server `patternfly-mcp`; needs Node.js 22 or newer) is the
source of truth for components, props and tokens:

- `searchPatternFlyDocs` with a component name or keyword (`"*"` lists everything);
- `usePatternFlyDocs` with the names or URLs found, for the documentation and the props' JSON
  Schema;
- the resource `patternfly://context` for PatternFly's own development rules.

When the server is not reachable, read `upstream/guidelines.md` (the essential rules),
`upstream/component-architecture.md` and `upstream/styling-standards.md`. Their links to other
upstream pages lead nowhere here; look those pages up through the server.
