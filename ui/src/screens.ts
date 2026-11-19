import type { ReactNode } from 'react';

/**
 * A screen of the application: its id, the title on its tab and in the navigation, and what it shows. `render` is
 * given `open`, which opens another screen in a tab of its own, or shows the tab it already has. `beforeClose`, when
 * present, is asked when the close button of the screen's tab is chosen: the tab closes when it calls `close`, at
 * once or later, and stays open while it does not. Without it the tab closes at once.
 *
 * A screen of the navigation has a plain name as its id, such as `messages`. The tabs a screen opens have ids of
 * the form `<prefix>:<key>`, such as `message:<id>`, so that they never meet the id of a screen of the navigation.
 */
export interface Screen {
  id: string;
  title: string;
  render: (open: (screen: Screen) => void) => ReactNode;
  beforeClose?: (close: () => void) => void;
}

/**
 * What the `module.tsx` of a module of the UI, a folder `src/<name>/`, default-exports: its place in the navigation,
 * after Home and before every module of a higher `order`, and its screens, in the order the navigation lists them.
 */
export interface UiModule {
  order: number;
  screens: readonly Screen[];
}

const PLAIN_NAME = /^[a-z][a-z0-9-]*$/;

const isRecord = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null;

function isScreen(value: unknown): value is Screen {
  return (
    isRecord(value) &&
    typeof value.id === 'string' &&
    PLAIN_NAME.test(value.id) &&
    typeof value.title === 'string' &&
    typeof value.render === 'function'
  );
}

function isUiModule(value: unknown): value is UiModule {
  return (
    isRecord(value) &&
    typeof value.order === 'number' &&
    Number.isFinite(value.order) &&
    Array.isArray(value.screens) &&
    value.screens.every(isScreen)
  );
}

/**
 * The screens the navigation lists, in its order: `home`, then the screens of each module, the modules by ascending
 * `order`. `found` holds what each module's file exports, keyed by the file, as `import.meta.glob` with `eager: true`
 * gives it, such as `'./messages/module.tsx'`.
 *
 * Throws, naming the files, when a file does not default-export a `UiModule` whose screens have plain names as ids,
 * when two modules have the same order, and when a screen id is taken twice, Home's included.
 */
export function screenList(home: Screen, found: Readonly<Record<string, unknown>>): readonly Screen[] {
  const modules = Object.keys(found)
    .sort()
    .map((file) => {
      const exported = found[file];
      const module = isRecord(exported) ? exported.default : undefined;
      if (!isUiModule(module)) {
        throw new Error(
          `${file} does not default-export a UiModule: { order: a finite number, screens: [{ id: a plain name, title, render }] }`,
        );
      }
      return { file, module };
    })
    .sort((a, b) => a.module.order - b.module.order);

  modules.forEach(({ file, module }, i) => {
    const before = modules[i - 1];
    if (before !== undefined && before.module.order === module.order) {
      throw new Error(`${before.file} and ${file} have the same order ${module.order}`);
    }
  });

  const owners = new Map([[home.id, 'the shell']]);
  const list = [home];
  for (const { file, module } of modules) {
    for (const screen of module.screens) {
      const owner = owners.get(screen.id);
      if (owner !== undefined) throw new Error(`the screen id "${screen.id}" is taken twice: by ${owner} and by ${file}`);
      owners.set(screen.id, file);
      list.push(screen);
    }
  }
  return list;
}
