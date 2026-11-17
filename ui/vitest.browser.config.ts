import { playwright } from '@vitest/browser-playwright';
import { defineConfig, mergeConfig } from 'vitest/config';
import viteConfig from './vite.config';

// Component tests run in a real Chromium. The `playwright` devDependency is pinned: the provider takes it as a peer,
// and the pinned release decides which Chromium build it wants.
export default mergeConfig(
  viteConfig,
  defineConfig({
    test: {
      include: ['src/**/*.browser.test.tsx'],
      browser: {
        enabled: true,
        headless: true,
        // a desktop window, where the navigation is shown from the start
        viewport: { width: 1280, height: 800 },
        provider: playwright(),
        instances: [{ browser: 'chromium' }],
      },
    },
  }),
);
