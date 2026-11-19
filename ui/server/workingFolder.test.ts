import { mkdir, mkdtemp, readdir, readFile, rm, symlink, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { MAX_FILE_BYTES, MAX_FILES, Refusal, replaceFile, walk } from './workingFolder';

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

describe('walk', () => {
  const isJson = (name: string) => name.endsWith('.json');

  it('reads the files it takes, in path order, and names those over the limit apart, unread', async () => {
    await mkdir(join(root, 'b', 'deep'), { recursive: true });
    await mkdir(join(root, '.hidden'));
    await mkdir(join(root, 'node_modules'));
    await writeFile(join(root, 'b', 'deep', 'one.json'), '1');
    await writeFile(join(root, 'a.json'), '2');
    await writeFile(join(root, 'b', 'large.json'), 'x'.repeat(MAX_FILE_BYTES + 1));
    await writeFile(join(root, 'b', 'notes.txt'), 'not taken');
    await writeFile(join(root, '.hidden', 'secret.json'), '3');
    await writeFile(join(root, 'node_modules', 'dependency.json'), '4');
    await symlink(join(root, 'a.json'), join(root, 'linked.json'));

    expect(await walk(root, isJson)).toEqual({
      files: [
        { path: 'a.json', text: '2' },
        { path: 'b/deep/one.json', text: '1' },
      ],
      large: ['b/large.json'],
      truncated: false,
    });
  });

  it(`stops past ${MAX_FILES} files it takes, large ones among them`, async () => {
    for (let i = 0; i <= MAX_FILES; i++) await writeFile(join(root, `${String(i).padStart(4, '0')}.json`), '{}');
    const { files, large, truncated } = await walk(root, isJson);
    expect(files).toHaveLength(MAX_FILES);
    expect(large).toEqual([]);
    expect(truncated).toBe(true);
  });
});
