import { existsSync, readdirSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { runnerImport, type Plugin } from 'vite';

/** What a module's half of the development server is told. */
export interface DevServer {
  /** The development server's own origins (scheme, host and port); a request with any other `Origin` is from elsewhere. */
  origins: readonly string[];
  /** The environment the development server runs in; a half reads its settings here. */
  env: Readonly<Record<string, string | undefined>>;
}

/** What a module's `server.ts` default-exports: given the development server, the Vite plugin that serves its routes. */
export type ModuleServer = (dev: DevServer) => Plugin;

/**
 * The halves of the development server that the modules of the UI bring, made for `dev`: the plugin of each
 * `src/<name>/server.ts`, in the order of the folder names, then one plugin that restarts the development server when
 * a half, or a file it imports, changes. A module without a `server.ts` brings none.
 *
 * Every folder of `src` is a module, so a folder without a `module.tsx` is refused, and so is a `server.ts` that does
 * not default-export a function. Each half is compiled as the UI's own code is.
 */
export async function moduleServers(dev: DevServer, src = resolve(import.meta.dirname, '../src')): Promise<Plugin[]> {
  const folders = readdirSync(src, { withFileTypes: true })
    .filter((entry) => entry.isDirectory())
    .map((entry) => entry.name)
    .sort();
  const plugins: Plugin[] = [];
  const files = new Set<string>();
  for (const name of folders) {
    if (!existsSync(join(src, name, 'module.tsx'))) {
      throw new Error(`src/${name} has no module.tsx: every folder of src is a module of the UI`);
    }
    const file = join(src, name, 'server.ts');
    if (!existsSync(file)) continue;
    const { module, dependencies } = await runnerImport<{ default?: unknown }>(file, { root: resolve(src, '..'), logLevel: 'silent' });
    if (typeof module.default !== 'function') {
      throw new Error(`src/${name}/server.ts does not default-export a function that makes its Vite plugin`);
    }
    plugins.push((module.default as ModuleServer)(dev));
    files.add(file);
    for (const dependency of dependencies) files.add(dependency);
  }
  plugins.push(restartOnChange(files));
  return plugins;
}

/** Restarts the development server when one of `files` changes, so that the halves are made again from it. */
function restartOnChange(files: ReadonlySet<string>): Plugin {
  return {
    name: 'rail8-module-servers',
    configureServer(server) {
      server.watcher.add([...files]);
      server.watcher.on('change', (file) => {
        if (files.has(file)) void server.restart();
      });
    },
  };
}
