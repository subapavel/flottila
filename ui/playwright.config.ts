import { defineConfig } from '@playwright/test';

// The end-to-end scenarios that need no real service: the shell's Mappings screen over the fake mapping service, on
// the page e2e/fake/index.html. They run against a production build of that page alone (e2e/fake/vite.config.ts), so
// that the tree scenario measures what a user gets; the application's own build leaves the page out.
const port = 4173;

export default defineConfig({
  testDir: 'e2e',
  testMatch: '**/*.spec.ts',
  testIgnore: '**/*.stack.spec.ts',
  use: {
    baseURL: `http://localhost:${port}`,
  },
  webServer: {
    command: 'vite build --config e2e/fake/vite.config.ts && vite preview --config e2e/fake/vite.config.ts',
    url: `http://localhost:${port}/e2e/fake/`,
    reuseExistingServer: false,
  },
});
