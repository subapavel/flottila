import { defineConfig } from '@playwright/test';

// The end-to-end scenarios that need no real service: the shell's Mappings screen over the fake mapping service, on
// the development server's page /e2e/fake/ (e2e/fake/index.html), which the production build leaves out.
const port = 5173;

export default defineConfig({
  testDir: 'e2e',
  testMatch: '**/*.spec.ts',
  testIgnore: '**/*.stack.spec.ts',
  use: {
    baseURL: `http://localhost:${port}`,
  },
  webServer: {
    command: 'npm run dev',
    url: `http://localhost:${port}/e2e/fake/`,
    reuseExistingServer: false,
  },
});
