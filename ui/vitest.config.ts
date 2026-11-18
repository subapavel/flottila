import { defineConfig } from 'vitest/config';

// Unit tests run in node; component tests (*.browser.test.tsx) are left out here and run in Chromium on their own.
export default defineConfig({
  test: {
    include: ['src/**/*.test.ts', 'server/**/*.test.ts'],
    exclude: ['src/**/*.browser.test.*', 'node_modules/**'],
    // `npm run test:coverage`: line and branch coverage of the unit tests, per file in coverage/coverage-summary.json
    coverage: {
      provider: 'v8',
      include: ['src/**/*.{ts,tsx}'],
      exclude: ['src/mapping/generated/**', 'src/**/*.test.{ts,tsx}', 'src/**/*.browser.test.{ts,tsx}'],
      reporter: ['text', 'json-summary'],
      reportsDirectory: 'coverage',
      // Floors per directory: a global threshold lets a weak directory hide behind a strong one.
      thresholds: {
        'src/mapping/model/**': { lines: 90 },
        'src/mapping/studio/store/**': { lines: 90 },
      },
    },
  },
});
