---
name: react
description: React rules for Rail8's UI — component composition and performance. Use when writing, reviewing or refactoring React components.
---

<!-- Source: vercel-labs/agent-skills, skills `composition-patterns` and `react-best-practices`,
     commit 1a2c79d4e6c4a2283dc2c614b6538cbe1ed5ca62 (2026-10-27), MIT licence (the repository's
     README and each skill's front matter). Rule files copied unchanged. Left out: the Next.js and
     server-rendering rules (`server-*`, `async-api-routes`, `bundle-dynamic-imports`,
     `bundle-defer-third-party`, `rendering-hydration-*`, `rendering-script-defer-async`) and the
     rules that add a library (`async-dependencies`, `client-swr-dedup`, `client-event-listeners`). -->

The UI is built from PatternFly components and tokens (skill `patternfly`); these rules shape the
React code around them. A new npm dependency is a question for the owner-proxy.

When you write or review a component, read the rule files below whose title matches what the code
does; each file has the rule, a wrong example and a right example. Higher impact first when rules
conflict.

## Composition

- `composition/architecture-avoid-boolean-props.md`: Avoid Boolean Prop Proliferation (CRITICAL)
- `composition/architecture-compound-components.md`: Use Compound Components (HIGH)
- `composition/patterns-children-over-render-props.md`: Prefer Composing Children Over Render Props (MEDIUM)
- `composition/patterns-explicit-variants.md`: Create Explicit Component Variants (MEDIUM)
- `composition/react19-no-forwardref.md`: React 19 API Changes (MEDIUM)
- `composition/state-context-interface.md`: Define Generic Context Interfaces for Dependency Injection (HIGH)
- `composition/state-decouple-implementation.md`: Decouple State Management from UI (MEDIUM)
- `composition/state-lift-state.md`: Lift State into Provider Components (HIGH)

## Performance

- `performance/advanced-effect-event-deps.md`: Do Not Put Effect Events in Dependency Arrays (LOW)
- `performance/advanced-event-handler-refs.md`: Store Event Handlers in Refs (LOW)
- `performance/advanced-init-once.md`: Initialize App Once, Not Per Mount (LOW-MEDIUM)
- `performance/advanced-use-latest.md`: useEffectEvent for Stable Callback Refs (LOW)
- `performance/async-cheap-condition-before-await.md`: Check Cheap Conditions Before Async Flags (HIGH)
- `performance/async-defer-await.md`: Defer Await Until Needed (HIGH)
- `performance/async-parallel.md`: Promise.all() for Independent Operations (CRITICAL)
- `performance/async-suspense-boundaries.md`: Strategic Suspense Boundaries (HIGH)
- `performance/bundle-analyzable-paths.md`: Prefer Statically Analyzable Paths (HIGH)
- `performance/bundle-barrel-imports.md`: Avoid Barrel File Imports (CRITICAL)
- `performance/bundle-conditional.md`: Conditional Module Loading (HIGH)
- `performance/bundle-preload.md`: Preload Based on User Intent (MEDIUM)
- `performance/client-localstorage-schema.md`: Version and Minimize localStorage Data (MEDIUM)
- `performance/client-passive-event-listeners.md`: Use Passive Event Listeners for Scrolling Performance (MEDIUM)
- `performance/js-batch-dom-css.md`: Avoid Layout Thrashing (MEDIUM)
- `performance/js-cache-function-results.md`: Cache Repeated Function Calls (MEDIUM)
- `performance/js-cache-property-access.md`: Cache Property Access in Loops (LOW-MEDIUM)
- `performance/js-cache-storage.md`: Cache Storage API Calls (LOW-MEDIUM)
- `performance/js-combine-iterations.md`: Combine Multiple Array Iterations (LOW-MEDIUM)
- `performance/js-early-exit.md`: Early Return from Functions (LOW-MEDIUM)
- `performance/js-flatmap-filter.md`: Use flatMap to Map and Filter in One Pass (LOW-MEDIUM)
- `performance/js-hoist-regexp.md`: Hoist RegExp Creation (LOW-MEDIUM)
- `performance/js-index-maps.md`: Build Index Maps for Repeated Lookups (LOW-MEDIUM)
- `performance/js-length-check-first.md`: Early Length Check for Array Comparisons (MEDIUM-HIGH)
- `performance/js-min-max-loop.md`: Use Loop for Min/Max Instead of Sort (LOW)
- `performance/js-request-idle-callback.md`: Defer Non-Critical Work with requestIdleCallback (MEDIUM)
- `performance/js-set-map-lookups.md`: Use Set/Map for O(1) Lookups (LOW-MEDIUM)
- `performance/js-tosorted-immutable.md`: Use toSorted() Instead of sort() for Immutability (MEDIUM-HIGH)
- `performance/rendering-activity.md`: Use Activity Component for Show/Hide (MEDIUM)
- `performance/rendering-animate-svg-wrapper.md`: Animate SVG Wrapper Instead of SVG Element (LOW)
- `performance/rendering-conditional-render.md`: Use Explicit Conditional Rendering (LOW)
- `performance/rendering-content-visibility.md`: CSS content-visibility for Long Lists (HIGH)
- `performance/rendering-hoist-jsx.md`: Hoist Static JSX Elements (LOW)
- `performance/rendering-resource-hints.md`: Use React DOM Resource Hints (HIGH)
- `performance/rendering-svg-precision.md`: Optimize SVG Precision (LOW)
- `performance/rendering-usetransition-loading.md`: Use useTransition Over Manual Loading States (LOW)
- `performance/rerender-defer-reads.md`: Defer State Reads to Usage Point (MEDIUM)
- `performance/rerender-dependencies.md`: Narrow Effect Dependencies (LOW)
- `performance/rerender-derived-state-no-effect.md`: Calculate Derived State During Rendering (MEDIUM)
- `performance/rerender-derived-state.md`: Subscribe to Derived State (MEDIUM)
- `performance/rerender-functional-setstate.md`: Use Functional setState Updates (MEDIUM)
- `performance/rerender-lazy-state-init.md`: Use Lazy State Initialization (MEDIUM)
- `performance/rerender-memo-with-default-value.md`: Extract Default Non-primitive Parameter Value from Memoized Component to Constant (MEDIUM)
- `performance/rerender-memo.md`: Extract to Memoized Components (MEDIUM)
- `performance/rerender-move-effect-to-event.md`: Put Interaction Logic in Event Handlers (MEDIUM)
- `performance/rerender-no-inline-components.md`: Don't Define Components Inside Components (HIGH)
- `performance/rerender-simple-expression-in-memo.md`: Do not wrap a simple expression with a primitive result type in useMemo (LOW-MEDIUM)
- `performance/rerender-split-combined-hooks.md`: Split Combined Hook Computations (MEDIUM)
- `performance/rerender-transitions.md`: Use Transitions for Non-Urgent Updates (MEDIUM)
- `performance/rerender-use-deferred-value.md`: Use useDeferredValue for Expensive Derived Renders (MEDIUM)
- `performance/rerender-use-ref-transient-values.md`: Use useRef for Transient Values (MEDIUM)
