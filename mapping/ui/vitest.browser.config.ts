import { playwright } from '@vitest/browser-playwright';
import { defineConfig, mergeConfig } from 'vitest/config';
import viteConfig from './vite.config';

// Component tests that need a real DOM run in Chromium (ADR-0013). jsdom is not used.
// The `playwright` devDependency is pinned to 1.63.0 (package.json): the provider takes `playwright` as a
// peer, and an unpinned peer resolves to a newer release that wants a Chromium build the image lacks.
// The Vite config brings the React plugin and the corpus/ allowance for the fake ApiClient's fixtures.
export default mergeConfig(
  viteConfig,
  defineConfig({
    test: {
      include: ['src/**/*.browser.test.{ts,tsx}'],
      browser: {
        enabled: true,
        headless: true,
        provider: playwright(),
        instances: [{ browser: 'chromium' }],
      },
    },
  }),
);
