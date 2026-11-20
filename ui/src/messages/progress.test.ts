import { describe, expect, it } from 'vitest';
import type { Outcome, StepRecord } from './admin';
import { account, byAttempt, duration, latestState, nanosBetween, type Row } from './progress';

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

describe('nanosBetween', () => {
  it('reads the fraction of a second to the nanosecond, whatever its length', () => {
    expect(nanosBetween('2026-11-20T09:50:08Z', '2026-11-20T09:50:08.535050Z')).toBe(535_050_000);
    expect(nanosBetween('2026-11-20T09:50:08.535050Z', '2026-11-20T09:50:09.1Z')).toBe(564_950_000);
    expect(nanosBetween('2026-11-20T09:50:09.000000001Z', '2026-11-20T09:50:08Z')).toBe(-1_000_000_001);
  });
});

describe('account', () => {
  function at(attempt: number, n: number, name: string, startedAt: string, wallNanos: number): StepRecord {
    return { ...step(attempt, n), name, startedAt, wallNanos };
  }

  /** The nanoseconds the rows of an account show: every wall time and every wait. */
  const shown = (rows: Row[]) => rows.reduce((sum, r) => sum + (r.kind === 'wait' ? r.nanos : r.step.wallNanos), 0);

  it('shows the wait of a saved message for a worker, and the rows with the other time add up to the duration', () => {
    const steps = [
      at(1, 0, 'ingress', '2026-11-20T09:50:08.535050Z', 0),
      at(1, 1, 'validate', '2026-11-20T09:50:08.554629Z', 2_200_000),
      at(1, 2, 'map', '2026-11-20T09:50:08.556850Z', 924_300),
      at(1, 3, 'send', '2026-11-20T09:50:08.557800Z', 4_300_000),
    ];
    const { attempts, otherNanos, totalNanos } = account(steps);
    // 09:50:08.557800 + 4.3 ms - 09:50:08.535050
    expect(totalNanos).toBe(27_050_000);
    const rows = attempts[0]!.rows;
    expect(rows.map((r) => (r.kind === 'wait' ? `wait ${r.reason} ${r.nanos}` : r.step.name))).toEqual([
      'ingress',
      'wait worker 19579000',
      'validate',
      'map',
      'send',
    ]);
    expect(otherNanos).toBe(46_700);
    expect(shown(rows) + otherNanos).toBe(totalNanos);
  });

  it('shows no wait when every gap is under the threshold, and the sum still holds', () => {
    const steps = [
      at(1, 0, 'ingress', '2026-11-20T09:50:08.000000Z', 0),
      at(1, 1, 'validate', '2026-11-20T09:50:08.000050Z', 10_000_000),
      at(1, 2, 'send', '2026-11-20T09:50:08.010080Z', 5_000_000),
    ];
    const { attempts, otherNanos, totalNanos } = account(steps);
    expect(attempts[0]!.rows.every((r) => r.kind === 'step')).toBe(true);
    expect(totalNanos).toBe(15_080_000);
    expect(otherNanos).toBe(80_000);
    expect(shown(attempts[0]!.rows) + otherNanos).toBe(totalNanos);
  });

  it('shows a wait between steps of at least 1% of the duration and 100 µs', () => {
    const steps = [
      at(1, 0, 'ingress', '2026-11-20T09:50:08Z', 0),
      at(1, 1, 'validate', '2026-11-20T09:50:08.000010Z', 1_000_000),
      at(1, 2, 'send', '2026-11-20T09:50:08.003010Z', 7_000_000),
    ];
    const rows = account(steps).attempts[0]!.rows;
    expect(rows.filter((r) => r.kind === 'wait')).toEqual([{ kind: 'wait', reason: 'between', nanos: 2_000_000 }]);
  });

  it('shows no wait under 100 µs, even when it is 1% of the duration or more', () => {
    const steps = [
      at(1, 0, 'ingress', '2026-11-20T09:50:08Z', 0),
      at(1, 1, 'validate', '2026-11-20T09:50:08.000060Z', 1_000_000),
      at(1, 2, 'send', '2026-11-20T09:50:08.001120Z', 2_000_000),
    ];
    const { attempts, otherNanos, totalNanos } = account(steps);
    expect(totalNanos).toBe(3_120_000);
    expect(attempts[0]!.rows.every((r) => r.kind === 'step')).toBe(true);
    expect(otherNanos).toBe(120_000);
  });

  it('always shows the wait before a retry, and the duration runs to the end of the latest attempt', () => {
    const steps = [
      at(1, 0, 'ingress', '2026-11-20T09:50:08Z', 0),
      at(1, 1, 'send', '2026-11-20T09:50:08.000010Z', 1_000_000),
      at(2, 1, 'send', '2026-11-20T09:50:08.001060Z', 1_000_000),
    ];
    const { attempts, otherNanos, totalNanos } = account(steps);
    expect(attempts.map((a) => a.attempt)).toEqual([1, 2]);
    expect(attempts[1]!.rows).toEqual([{ kind: 'wait', reason: 'retry', nanos: 50_000 }, { kind: 'step', step: steps[2] }]);
    expect(totalNanos).toBe(2_060_000);
    expect(shown(attempts.flatMap((a) => a.rows)) + otherNanos).toBe(totalNanos);
  });

  it('is empty when nothing is recorded', () => {
    expect(account([])).toEqual({ attempts: [], otherNanos: 0, totalNanos: 0 });
  });
});
