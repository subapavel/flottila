import react from '@vitejs/plugin-react';
import { defineConfig, type Plugin } from 'vite';
import { adminProxy } from './server/adminProxy.ts';
import { mappingServiceTarget } from './server/mappingService.ts';

// The development server listens on localhost only, on a fixed port, so the origins its own pages send are known.
const port = 5173;
const origins = [`http://localhost:${port}`, `http://127.0.0.1:${port}`];

/**
 * Sends the UI's `/admin` requests on to a node's admin API, which answers no browser. The node is RAIL8_ADMIN_URL
 * (default http://127.0.0.1:8081); RAIL8_ADMIN_TOKEN, when set, is the token the proxy adds. It is read here, on the
 * server, and never reaches the browser.
 */
function adminApi(): Plugin {
  return {
    name: 'rail8-admin-api',
    configureServer(server) {
      server.middlewares.use(
        adminProxy({
          target: process.env.RAIL8_ADMIN_URL ?? 'http://127.0.0.1:8081',
          origins,
          token: process.env.RAIL8_ADMIN_TOKEN?.trim() || undefined,
        }),
      );
    },
  };
}

export default defineConfig({
  plugins: [react(), adminApi()],
  server: {
    host: 'localhost',
    port,
    strictPort: true,
    // the mapping studio's /api requests go to the mapping service
    proxy: { '/api': mappingServiceTarget(process.env) },
    // the mapping studio's fake service reads its fixtures from the mapping corpus and contract
    fs: { allow: ['.', '../mapping/corpus', '../mapping/contract'] },
  },
});
