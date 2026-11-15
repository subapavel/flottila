import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';
import { apiTarget } from './dev/proxy-target';

export default defineConfig({
  plugins: [react()],
  server: {
    // One origin for the browser, no CORS (ADR-0014, ADR-0017): /api goes to the service.
    proxy: { '/api': apiTarget(process.env) },
    // The fake ApiClient reads its fixtures from corpus/ (ADR-0013) and the packed catalogue from contract/
    // (ADR-0027); the dev server serves ui/ and those only.
    fs: { allow: ['.', '../corpus', '../contract'] },
  },
});
