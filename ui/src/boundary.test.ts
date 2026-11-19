import { resolve } from 'node:path';
import { ESLint } from 'eslint';
import { describe, expect, test } from 'vitest';

const eslint = new ESLint({ cwd: resolve(import.meta.dirname, '..') });

/** The messages of the boundary's rules for `code` written in `file`, a path relative to `ui/` that need not exist. */
async function refusals(file: string, code: string): Promise<string[]> {
  const [result] = await eslint.lintText(code, { filePath: resolve(import.meta.dirname, '..', file) });
  return (result?.messages ?? [])
    .filter((message) => message.ruleId === 'no-restricted-imports' || message.ruleId === 'no-restricted-syntax')
    .map((message) => message.message);
}

describe('the boundary between the shell and the modules', () => {
  test.each([
    ['src/endpoints/probe.tsx', "import { DiscardDialog } from '../mapping/studio/DiscardDialog';"],
    ['src/endpoints/probe.tsx', "import { mappingsScreen } from '../mapping';"],
    ['src/endpoints/probe.tsx', "export { httpAdminApi } from '../messages/admin';"],
    ['src/endpoints/probe.tsx', "import { httpAdminApi } from '../../src/messages/admin';"],
    ['src/endpoints/probe.tsx', "const screen = import('../mapping/screen');"],
    ['src/mapping/studio/probe.tsx', "import type { EndpointsApi } from '../../endpoints/api';"],
    ['src/mapping/api/fake/probe.ts', "const admin = import('../../../messages/admin');"],
    ['src/probe.tsx', "import { messagesScreen } from './messages/screen';"],
    ['src/probe.tsx', "import endpoints from './endpoints';"],
    ['src/probe.tsx', "const screen = import('./mapping/screen');"],
    ['server/probe.ts', "import { endpointFiles } from '../src/endpoints/files/endpointFiles.ts';"],
    ['probe.config.ts', "import mapping from './src/mapping/server.ts';"],
  ])('refuses in %s: %s', async (file, code) => {
    expect(await refusals(file, code)).toHaveLength(1);
  });

  test.each([
    ['src/mapping/studio/probe.tsx', "import { describeError } from '../api/client';"],
    ['src/mapping/studio/probe.tsx', "const source = import('./function-source');"],
    ['src/endpoints/probe.tsx', "import { DiscardDialog } from '../DiscardDialog';"],
    ['src/endpoints/probe.tsx', "import type { Screen } from '../screens';"],
    ['src/endpoints/probe.tsx', "import { Shell } from '../Shell';"],
    ['src/endpoints/files/probe.ts', "import { sameValue } from '../same-value.ts';"],
    ['src/endpoints/probe.ts', "import type { ModuleServer } from '../../server/modules.ts';"],
    ['src/mapping/api/fake/probe.ts', "import text from '../../../../../mapping/corpus/cases/x.json?raw';"],
    ['src/probe.tsx', "import { home } from './Home';"],
    ['server/probe.ts', "import { shared } from '../src/shared.ts';"],
    ['probe.config.ts', "import { moduleServers } from './server/modules.ts';"],
  ])('allows in %s: %s', async (file, code) => {
    expect(await refusals(file, code)).toEqual([]);
  });
});
