import type { Outcome, StepRecord } from './admin';

/** The recorded steps of one attempt, in step order. */
export interface Attempt {
  attempt: number;
  steps: StepRecord[];
}

/** The steps grouped by attempt, the attempts in order and each one's steps in step order. */
export function byAttempt(steps: readonly StepRecord[]): Attempt[] {
  const groups = new Map<number, StepRecord[]>();
  for (const step of steps) groups.set(step.attempt, [...(groups.get(step.attempt) ?? []), step]);
  return [...groups.entries()]
    .sort(([a], [b]) => a - b)
    .map(([attempt, recorded]) => ({ attempt, steps: [...recorded].sort((a, b) => a.step - b.step) }));
}

/** Where the latest attempt of a message stands. */
export interface State {
  /** The latest attempt's number, 0 when nothing is recorded. */
  attempt: number;
  /** FAILED when a step of it failed, else FILTERED when one handed nothing on, else OK. */
  outcome: Outcome;
  /** The step of it that failed, else null. */
  failed: StepRecord | null;
}

/** The state of the latest attempt among `steps`. */
export function latestState(steps: readonly StepRecord[]): State {
  const latest = byAttempt(steps).at(-1);
  if (latest === undefined) return { attempt: 0, outcome: 'OK', failed: null };
  const failed = latest.steps.find((s) => s.outcome === 'FAILED') ?? null;
  const outcome = failed !== null ? 'FAILED' : latest.steps.some((s) => s.outcome === 'FILTERED') ? 'FILTERED' : 'OK';
  return { attempt: latest.attempt, outcome, failed };
}

/** A duration in nanoseconds for people: nanoseconds, microseconds, milliseconds or seconds, whichever reads best. */
export function duration(nanos: number): string {
  if (nanos < 1_000) return `${nanos} ns`;
  if (nanos < 1_000_000) return `${(nanos / 1_000).toFixed(1)} µs`;
  if (nanos < 1_000_000_000) return `${(nanos / 1_000_000).toFixed(1)} ms`;
  return `${(nanos / 1_000_000_000).toFixed(2)} s`;
}
