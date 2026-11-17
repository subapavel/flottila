import { defineConfig } from 'vitest/config';

// Unit tests run in node; component tests (*.browser.test.tsx) are left out here and run in Chromium on their own.
export default defineConfig({
  test: {
    include: ['src/**/*.test.ts'],
    exclude: ['src/**/*.browser.test.*', 'node_modules/**'],
  },
});
