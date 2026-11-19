import { EventEmitter } from 'node:events';
import { mkdir, mkdtemp, rm, writeFile } from 'node:fs/promises';
import { createServer as createHttpServer, request, type Server } from 'node:http';
import type { AddressInfo } from 'node:net';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { createServer, type Plugin, type ViteDevServer } from 'vite';
import { afterEach, describe, expect, test, vi } from 'vitest';
import { moduleServers, type DevServer } from './modules';

const dev: DevServer = { origins: ['http://localhost:5173'], env: {} };
const ui = resolve(import.meta.dirname, '..');

let temp: string | undefined;

async function tree(files: Record<string, string>): Promise<string> {
  temp = await mkdtemp(join(tmpdir(), 'rail8-modules-'));
  const src = join(temp, 'src');
  for (const [path, text] of Object.entries(files)) {
    await mkdir(join(src, path, '..'), { recursive: true });
    await writeFile(join(src, path), text);
  }
  return src;
}

afterEach(async () => {
  if (temp !== undefined) await rm(temp, { recursive: true, force: true });
  temp = undefined;
});

const half = (name: string) => `export default () => ({ name: '${name}' });\n`;

describe('moduleServers', () => {
  test("gives the halves of today's modules, then the plugin that restarts the server", async () => {
    const plugins = await moduleServers(dev);
    expect(plugins.map((plugin) => plugin.name)).toEqual(['rail8-working-folder', 'rail8-mapping-service', 'rail8-module-servers']);
  });

  test('calls each half with the development server, in the order of the folder names, and passes over a module without one', async () => {
    const src = await tree({
      'zeta/module.tsx': '',
      'zeta/server.ts': half('zeta'),
      'beta/module.tsx': '',
      'alpha/module.tsx': '',
      'alpha/server.ts': 'export default (dev: { env: Record<string, string> }) => ({ name: `alpha ${dev.env.WHO}` });\n',
    });
    const plugins = await moduleServers({ origins: [], env: { WHO: 'me' } }, src);
    expect(plugins.map((plugin) => plugin.name)).toEqual(['alpha me', 'zeta', 'rail8-module-servers']);
  });

  test('refuses a folder of src without module.tsx', async () => {
    const src = await tree({ 'alpha/module.tsx': '', 'reports/server.ts': half('reports') });
    await expect(moduleServers(dev, src)).rejects.toThrow('src/reports has no module.tsx: every folder of src is a module of the UI');
  });

  test('refuses a server.ts that does not default-export a function', async () => {
    const src = await tree({ 'alpha/module.tsx': '', 'alpha/server.ts': "export const name = 'alpha';\n" });
    await expect(moduleServers(dev, src)).rejects.toThrow('src/alpha/server.ts does not default-export a function that makes its Vite plugin');
  });

  test('restarts the development server when a half, or a file it imports, changes', async () => {
    const src = await tree({
      'alpha/module.tsx': '',
      'alpha/server.ts': "import { name } from './files/name';\nexport default () => ({ name });\n",
      'alpha/files/name.ts': "export const name = 'alpha';\n",
      'alpha/screen.tsx': '',
    });
    const restarter = (await moduleServers(dev, src)).at(-1) as Plugin;
    const watcher = Object.assign(new EventEmitter(), { add: vi.fn() });
    const restart = vi.fn(async () => undefined);
    (restarter.configureServer as (server: unknown) => void)({ watcher, restart });

    expect(watcher.add).toHaveBeenCalledWith(expect.arrayContaining([join(src, 'alpha/server.ts'), join(src, 'alpha/files/name.ts')]));
    watcher.emit('change', join(src, 'alpha/screen.tsx'));
    expect(restart).not.toHaveBeenCalled();
    watcher.emit('change', join(src, 'alpha/files/name.ts'));
    expect(restart).toHaveBeenCalledTimes(1);
    watcher.emit('change', join(src, 'alpha/server.ts'));
    expect(restart).toHaveBeenCalledTimes(2);
  });
});

describe('the development server of vite.config.ts', () => {
  let upstream: Server | undefined;
  let front: Server | undefined;
  let vite: ViteDevServer | undefined;

  afterEach(async () => {
    await vite?.close();
    await new Promise((done) => (front ? front.close(done) : done(undefined)));
    await new Promise((done) => (upstream ? upstream.close(done) : done(undefined)));
    vi.unstubAllEnvs();
  });

  const listen = (server: Server) =>
    new Promise<number>((done) => server.listen(0, '127.0.0.1', () => done((server.address() as AddressInfo).port)));

  const get = (port: number, path: string) =>
    new Promise<{ status: number; body: string }>((done, fail) => {
      const req = request({ host: '127.0.0.1', port, path, headers: { 'sec-fetch-site': 'same-origin' } }, (res) => {
        let body = '';
        res.setEncoding('utf8');
        res.on('data', (chunk: string) => (body += chunk));
        res.on('end', () => done({ status: res.statusCode ?? 0, body }));
      });
      req.on('error', fail);
      req.end();
    });

  test('answers every route of the shell and of the modules', async () => {
    // one service stands in for the node and the mapping service, and answers with the path it was asked
    upstream = createHttpServer((req, res) => res.end(`upstream ${req.url}`));
    const upstreamPort = await listen(upstream);
    const workspace = await mkdtemp(join(tmpdir(), 'rail8-workspace-'));
    temp = workspace;
    vi.stubEnv('RAIL8_ADMIN_URL', `http://127.0.0.1:${upstreamPort}`);
    vi.stubEnv('QUARKUS_HTTP_PORT', String(upstreamPort));
    vi.stubEnv('RAIL8_WORKSPACE', workspace);

    vite = await createServer({
      configFile: join(ui, 'vite.config.ts'),
      logLevel: 'silent',
      server: { middlewareMode: true, ws: false, watch: null },
    });
    front = createHttpServer(vite.middlewares);
    const port = await listen(front);

    expect(await get(port, '/admin/messages')).toEqual({ status: 200, body: 'upstream /admin/messages' });
    expect(await get(port, '/api/mappings')).toEqual({ status: 200, body: 'upstream /api/mappings' });
    const endpoints = await get(port, '/files/endpoints');
    expect(endpoints.status).toBe(200);
    expect(JSON.parse(endpoints.body)).toMatchObject({ configured: true, items: [] });
    expect(vite.config.server.fs.allow).toEqual(expect.arrayContaining([join(ui, '../mapping/corpus'), join(ui, '../mapping/contract')]));
  });
});
