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

/** The name of step 0, which records what arrived: it times nothing, so it has no wall or CPU time to show. */
export const INGRESS = 'ingress';

const INSTANT = /^(.+T\d{2}:\d{2}:\d{2})(?:\.(\d{1,9}))?(Z|[+-]\d{2}:\d{2})$/;

function instant(iso: string): { millis: number; nanos: number } {
  const parts = INSTANT.exec(iso);
  if (parts === null) throw new Error(`not an ISO-8601 instant: ${iso}`);
  return { millis: Date.parse(`${parts[1]}${parts[3]}`), nanos: Number((parts[2] ?? '').padEnd(9, '0')) };
}

/** The nanoseconds from the ISO-8601 instant `from` to `to`, negative when `to` is earlier; the fraction of a second is read to the nanosecond. */
export function nanosBetween(from: string, to: string): number {
  const a = instant(from);
  const b = instant(to);
  return (b.millis - a.millis) * 1_000_000 + (b.nanos - a.nanos);
}

/** Why a message waited before a step: saved after its arrival until a worker took it, before an attempt after the first, or between two steps. */
export type WaitReason = 'worker' | 'retry' | 'between';

/** A row of an attempt's timeline: a recorded step, or the time from the end of the row before it to the start of the next step. */
export type Row = { kind: 'step'; step: StepRecord } | { kind: 'wait'; reason: WaitReason; nanos: number };

/** A message's whole duration, row by row. */
export interface Account {
  /** The attempts in order, each with its steps in step order and the waits shown between them. */
  attempts: { attempt: number; rows: Row[] }[];
  /** The time no row shows: the total less every wall time and every wait shown. */
  otherNanos: number;
  /** From the start of the arrival to the end of the step that started last, as the list of messages has it. */
  totalNanos: number;
}

/** The shortest wait shown between two steps, in nanoseconds; a wait shown is also at least 1% of the message's duration. */
export const WAIT_SHOWN_NANOS = 100_000;

/**
 * The rows of `steps` with the waits between them that matter: a wait before the first step of a later attempt always, any
 * other wait when it is at least {@link WAIT_SHOWN_NANOS} and 1% of the duration. The wall times, the waits and the other
 * time add up to the duration.
 */
export function account(steps: readonly StepRecord[]): Account {
  const attempts = byAttempt(steps);
  const ordered = attempts.flatMap((a) => a.steps);
  const arrival = ordered.find((s) => s.attempt === 1 && s.step === 0) ?? ordered[0];
  if (arrival === undefined) return { attempts: [], otherNanos: 0, totalNanos: 0 };
  const last = ordered.reduce((latest, s) => {
    const later = nanosBetween(latest.startedAt, s.startedAt);
    return later > 0 || (later === 0 && (s.attempt > latest.attempt || (s.attempt === latest.attempt && s.step > latest.step))) ? s : latest;
  });
  const totalNanos = Math.max(0, nanosBetween(arrival.startedAt, last.startedAt) + last.wallNanos);
  const threshold = Math.max(WAIT_SHOWN_NANOS, totalNanos / 100);

  let previous: StepRecord | undefined;
  let shown = 0;
  const rows = attempts.map(({ attempt, steps: recorded }) => {
    const out: Row[] = [];
    recorded.forEach((step, i) => {
      if (previous !== undefined) {
        const nanos = nanosBetween(previous.startedAt, step.startedAt) - previous.wallNanos;
        const retry = i === 0;
        if (nanos > 0 && (retry || nanos >= threshold)) {
          out.push({ kind: 'wait', reason: retry ? 'retry' : previous.name === INGRESS ? 'worker' : 'between', nanos });
          shown += nanos;
        }
      }
      out.push({ kind: 'step', step });
      shown += step.wallNanos;
      previous = step;
    });
    return { attempt, rows: out };
  });
  return { attempts: rows, otherNanos: totalNanos - shown, totalNanos };
}
