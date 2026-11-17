import { describe, expect, it } from 'vitest';
import type { Outcome, StepRecord } from './admin';
import { byAttempt, duration, latestState } from './progress';

function step(attempt: number, n: number, outcome: Outcome = 'OK', error: string | null = null): StepRecord {
  return {
    step: n,
    attempt,
    name: `step-${n}`,
    outcome,
    error,
    startedAt: '2026-11-17T10:00:00Z',
    wallNanos: 1,
    cpuNanos: null,
    body: { hash: 'sha256:0', size: 0, kept: true },
  };
}

describe('byAttempt', () => {
  it('groups the steps by attempt, both in order', () => {
    const attempts = byAttempt([step(2, 0), step(1, 1), step(1, 0), step(2, 1)]);
    expect(attempts.map((a) => [a.attempt, a.steps.map((s) => s.step)])).toEqual([
      [1, [0, 1]],
      [2, [0, 1]],
    ]);
  });
});

describe('latestState', () => {
  it('is the failed step of the latest attempt', () => {
    const failed = step(2, 1, 'FAILED', 'partner refused');
    expect(latestState([step(1, 0), step(1, 1, 'FAILED', 'timeout'), step(2, 0), failed])).toEqual({ attempt: 2, outcome: 'FAILED', failed });
  });

  it('is OK once a later attempt went through, whatever an earlier one did', () => {
    expect(latestState([step(1, 0), step(1, 1, 'FAILED', 'timeout'), step(2, 0), step(2, 1)])).toEqual({ attempt: 2, outcome: 'OK', failed: null });
  });

  it('is FILTERED when a step of the latest attempt handed nothing on', () => {
    expect(latestState([step(1, 0), step(1, 1, 'FILTERED')])).toMatchObject({ outcome: 'FILTERED', failed: null });
  });

  it('is attempt 0 when nothing is recorded', () => {
    expect(latestState([])).toEqual({ attempt: 0, outcome: 'OK', failed: null });
  });
});

describe('duration', () => {
  it('picks the unit that reads best', () => {
    expect(duration(0)).toBe('0 ns');
    expect(duration(999)).toBe('999 ns');
    expect(duration(1_500)).toBe('1.5 µs');
    expect(duration(2_340_000)).toBe('2.3 ms');
    expect(duration(3_210_000_000)).toBe('3.21 s');
  });
});
