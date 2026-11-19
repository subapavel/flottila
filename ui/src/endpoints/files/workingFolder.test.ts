import { mkdtemp, readdir, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { Refusal, replaceFile } from './workingFolder';

let root: string;

beforeEach(async () => {
  root = await mkdtemp(join(tmpdir(), 'rail8-replace-'));
});

afterEach(async () => {
  await rm(root, { recursive: true, force: true });
});

describe('replaceFile', () => {
  it('writes the text through a temporary file renamed over the file', async () => {
    const file = join(root, 'flow.yaml');
    await writeFile(file, 'old\n');
    await replaceFile(file, 'new\n');
    expect(await readFile(file, 'utf8')).toBe('new\n');
    expect(await readdir(root)).toEqual(['flow.yaml']);
  });

  it('runs its check just before the rename, and leaves the file as it is when the check fails', async () => {
    const file = join(root, 'flow.yaml');
    await writeFile(file, 'old\n');
    const seen: string[] = [];
    const changed = async () => {
      seen.push(...(await readdir(root)).filter((f) => f.endsWith('.tmp')).map(() => 'temporary file written'));
      await writeFile(file, 'changed by another program\n');
      throw new Refusal(412, 'the file changed since it was read; nothing was saved');
    };
    await expect(replaceFile(file, 'new\n', changed)).rejects.toThrow('the file changed since it was read');
    expect(seen).toEqual(['temporary file written']);
    expect(await readFile(file, 'utf8')).toBe('changed by another program\n');
    expect(await readdir(root)).toEqual(['flow.yaml']);
  });
});
