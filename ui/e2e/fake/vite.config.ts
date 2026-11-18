import { resolve } from 'node:path';
import { defineConfig, mergeConfig } from 'vite';
import viteConfig from '../../vite.config';

// A production build of the fake-service page alone, for the scenarios that measure speed: `vite build` with this
// file writes it to e2e/fake/dist, and `vite preview` with it serves that. The application's own build never holds it.
export default mergeConfig(
  viteConfig,
  defineConfig({
    root: resolve(import.meta.dirname, '../..'),
    build: {
      outDir: resolve(import.meta.dirname, 'dist'),
      emptyOutDir: true,
      rollupOptions: { input: resolve(import.meta.dirname, 'index.html') },
    },
    preview: { host: 'localhost', port: 4173, strictPort: true },
  }),
);
