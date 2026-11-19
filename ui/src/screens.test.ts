import { describe, expect, test } from 'vitest';
import { screenList, type Screen } from './screens';

const screen = (id: string): Screen => ({ id, title: id, render: () => null });
const home = screen('home');
const uiModule = (order: number, ...ids: string[]) => ({ default: { order, screens: ids.map(screen) } });
const ids = (list: readonly Screen[]) => list.map((s) => s.id);

describe('screenList', () => {
  test('gives Home first, then the screens of each module by its order, each module in its own order', () => {
    const list = screenList(home, {
      './zeta/module.tsx': uiModule(5, 'first', 'second'),
      './alpha/module.tsx': uiModule(30, 'last'),
      './beta/module.tsx': uiModule(10, 'third'),
    });
    expect(ids(list)).toEqual(['home', 'first', 'second', 'third', 'last']);
  });

  test('gives Home alone without modules', () => {
    expect(screenList(home, {})).toEqual([home]);
  });

  test('keeps the screens the modules made', () => {
    const made = screen('made');
    expect(screenList(home, { './a/module.tsx': { default: { order: 1, screens: [made] } } })[1]).toBe(made);
  });

  const shape = (file: string) =>
    `${file} does not default-export a UiModule: { order: a finite number, screens: [{ id: a plain name, title, render }] }`;

  test.each([
    ['no default export', {}],
    ['a default export that is no object', { default: 'messages' }],
    ['no order', { default: { screens: [] } }],
    ['an order that is not finite', { default: { order: Number.NaN, screens: [] } }],
    ['screens that are no array', { default: { order: 1, screens: screen('x') } }],
    ['a screen without render', { default: { order: 1, screens: [{ id: 'x', title: 'X' }] } }],
    ['a screen without a title', { default: { order: 1, screens: [{ id: 'x', render: () => null }] } }],
    ['a screen whose id is no plain name', { default: { order: 1, screens: [screen('message:1')] } }],
    ['a screen whose id starts with a digit', { default: { order: 1, screens: [screen('1st')] } }],
  ])('refuses a module.tsx with %s', (_what, exports) => {
    expect(() => screenList(home, { './a/module.tsx': uiModule(1, 'fine'), './x/module.tsx': exports })).toThrow(shape('./x/module.tsx'));
  });

  test('refuses two modules with the same order, naming both files', () => {
    expect(() => screenList(home, { './b/module.tsx': uiModule(20, 'two'), './a/module.tsx': uiModule(20, 'one') })).toThrow(
      './a/module.tsx and ./b/module.tsx have the same order 20',
    );
  });

  test('refuses a screen id two modules use, naming both files', () => {
    expect(() =>
      screenList(home, { './a/module.tsx': uiModule(1, 'flows'), './b/module.tsx': uiModule(2, 'other', 'flows') }),
    ).toThrow('the screen id "flows" is taken twice: by ./a/module.tsx and by ./b/module.tsx');
  });

  test('refuses a module that uses the id of Home, naming the shell', () => {
    expect(() => screenList(home, { './a/module.tsx': uiModule(1, 'home') })).toThrow(
      'the screen id "home" is taken twice: by the shell and by ./a/module.tsx',
    );
  });

  test('refuses a module that uses one id twice', () => {
    expect(() => screenList(home, { './a/module.tsx': uiModule(1, 'flows', 'flows') })).toThrow(
      'the screen id "flows" is taken twice: by ./a/module.tsx and by ./a/module.tsx',
    );
  });
});
