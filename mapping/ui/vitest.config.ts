import { defineConfig } from 'vitest/config';

// Unit tests run in node (ADR-0013). Component tests run in browser mode (vitest.browser.config.ts).
export default defineConfig({
  test: {
    include: ['src/**/*.test.ts', 'dev/**/*.test.ts'],
    exclude: ['src/**/*.browser.test.ts', 'node_modules/**'],
    passWithNoTests: true,
    coverage: {
      provider: 'v8',
      include: ['src/**/*.{ts,tsx}'],
      exclude: ['src/generated/**', 'src/**/*.test.{ts,tsx}', 'src/**/*.browser.test.{ts,tsx}'],
      reporter: ['text', 'json-summary'],
      reportsDirectory: 'coverage',
      // Per-glob floors (ADR-0019): a global threshold lets a weak directory hide behind a strong one.
      thresholds: {
        'src/model/**': { lines: 90 },
        'src/studio/store/**': { lines: 90 },
      },
    },
  },
});
