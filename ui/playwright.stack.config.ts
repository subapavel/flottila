import { defineConfig } from '@playwright/test';
import { stackWorkspace } from './e2e/stack-workspace';

// The @stack scenarios: the UI's development server, whose /api requests it answers itself over a working folder and the
// real node. The node is the quickstart's, started by `docker compose up` from the repository root, with its admin API
// on 127.0.0.1:8081 (RAIL8_ADMIN_URL to change it) and the token exported in RAIL8_ADMIN_TOKEN. The working folder is a
// temporary copy of the mapping corpus that e2e/stack-setup.mjs prepares.
const port = 5173;

export default defineConfig({
  testDir: 'e2e',
  testMatch: '**/*.stack.spec.ts',
  // One studio at a time, as one person uses it: the node runs at most 4 calls of the mapping module at once and answers
  // any more with 503 busy, which two studios loading together can reach.
  workers: 1,
  use: {
    baseURL: `http://localhost:${port}`,
  },
  webServer: {
    command: `rm -rf ${stackWorkspace} && cp -r ../mapping/corpus ${stackWorkspace} && node e2e/stack-setup.mjs ${stackWorkspace} && exec npm run dev`,
    url: `http://localhost:${port}/`,
    env: { RAIL8_WORKSPACE: stackWorkspace },
    reuseExistingServer: false,
  },
});
