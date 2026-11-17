import { defineConfig } from 'vitest/config';

// Unit tests run in node; component tests (*.browser.test.tsx) run in Chromium through vitest.browser.config.ts.
export default defineConfig({
  test: {
    include: ['src/**/*.test.ts'],
    exclude: ['src/**/*.browser.test.*', 'node_modules/**'],
  },
});
