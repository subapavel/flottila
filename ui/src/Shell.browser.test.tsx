import '@patternfly/react-core/dist/styles/base.css';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, describe, expect, test } from 'vitest';
import { page, userEvent } from 'vitest/browser';
import type { Screen } from './screens';
import { screens } from './screens';
import { Shell } from './Shell';

const two: readonly Screen[] = [
  { id: 'first', title: 'First', render: () => <p>First screen</p> },
  { id: 'second', title: 'Second', render: () => <p>Second screen</p> },
];

let root: Root | undefined;
let host: HTMLElement | undefined;

function render(list: readonly Screen[], start?: string) {
  host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
  root.render(<Shell screens={list} start={start} />);
}

afterEach(async () => {
  root?.unmount();
  host?.remove();
  await page.viewport(1280, 800);
});

const nav = () => page.getByRole('navigation', { name: 'Screens' });
const tab = (name: string) => page.getByRole('tab', { name });
const tabs = () => page.getByRole('tab');

describe('Shell', () => {
  test('starts with the Home screen of the application open in a tab', async () => {
    render(screens, screens[0]?.id);
    await expect.element(page.getByRole('banner').getByText('Rail8')).toBeVisible();
    await expect.element(tab('Home')).toHaveAttribute('aria-selected', 'true');
    await expect.element(page.getByRole('heading', { level: 1, name: 'Rail8' })).toBeVisible();
  });

  test('opens a screen chosen in the navigation in a new tab, and shows the tab it already has', async () => {
    render(two, 'first');
    await nav().getByRole('button', { name: 'Second' }).click();
    await expect.element(tab('Second')).toHaveAttribute('aria-selected', 'true');
    await expect.element(page.getByText('Second screen')).toBeVisible();
    expect(tabs().elements()).toHaveLength(2);

    await nav().getByRole('button', { name: 'First' }).click();
    await expect.element(tab('First')).toHaveAttribute('aria-selected', 'true');
    await expect.element(page.getByText('First screen')).toBeVisible();
    expect(tabs().elements()).toHaveLength(2);
  });

  test('switches and closes tabs from the keyboard', async () => {
    render(two, 'first');
    await nav().getByRole('button', { name: 'Second' }).click();
    await tab('First').click();
    await expect.element(page.getByText('First screen')).toBeVisible();

    await userEvent.tab();
    await expect.element(page.getByRole('button', { name: 'Close First' })).toHaveFocus();
    await userEvent.tab();
    await expect.element(tab('Second')).toHaveFocus();
    await userEvent.keyboard('{Enter}');
    await expect.element(tab('Second')).toHaveAttribute('aria-selected', 'true');
    await expect.element(page.getByText('Second screen')).toBeVisible();

    await userEvent.tab();
    await userEvent.keyboard('{Enter}');
    await expect.element(tab('First')).toHaveAttribute('aria-selected', 'true');
    expect(tabs().elements()).toHaveLength(1);
  });

  test('closes a tab with its close button and shows the next one, then says when none is open', async () => {
    render(two, 'first');
    await nav().getByRole('button', { name: 'Second' }).click();
    await tab('First').click();

    await page.getByRole('button', { name: 'Close First' }).click();
    await expect.element(tab('Second')).toHaveAttribute('aria-selected', 'true');
    expect(tabs().elements()).toHaveLength(1);

    await page.getByRole('button', { name: 'Close Second' }).click();
    await expect.element(page.getByRole('heading', { name: 'No screen open' })).toBeVisible();
    expect(tabs().elements()).toHaveLength(0);

    await nav().getByRole('button', { name: 'First' }).click();
    await expect.element(tab('First')).toHaveAttribute('aria-selected', 'true');
  });

  test('marks the screen of the shown tab in the navigation', async () => {
    render(two, 'first');
    await expect.element(nav().getByRole('button', { name: 'First' })).toHaveAttribute('aria-current', 'page');
    await nav().getByRole('button', { name: 'Second' }).click();
    await expect.element(nav().getByRole('button', { name: 'Second' })).toHaveAttribute('aria-current', 'page');
    await expect.element(nav().getByRole('button', { name: 'First' })).not.toHaveAttribute('aria-current');
  });

  test('opens the tabs a screen asks for, one per id, and forgets one once it is closed', async () => {
    const opener: Screen = {
      id: 'opener',
      title: 'Opener',
      render: (open) => (
        <button type="button" onClick={() => open({ id: 'child', title: 'Child', render: () => <p>Child screen</p> })}>
          Open child
        </button>
      ),
    };
    render([opener], 'opener');
    await page.getByRole('button', { name: 'Open child' }).click();
    await expect.element(tab('Child')).toHaveAttribute('aria-selected', 'true');
    await expect.element(page.getByText('Child screen')).toBeVisible();
    expect(nav().getByRole('button', { name: 'Child' }).elements()).toHaveLength(0);

    await tab('Opener').click();
    await page.getByRole('button', { name: 'Open child' }).click();
    expect(tabs().elements()).toHaveLength(2);

    await page.getByRole('button', { name: 'Close Child' }).click();
    expect(tabs().elements()).toHaveLength(1);
    await page.getByRole('button', { name: 'Open child' }).click();
    await expect.element(page.getByText('Child screen')).toBeVisible();
  });

  test('asks a screen before its tab closes, and closes it only when the screen says so', async () => {
    let closeLater: (() => void) | undefined;
    const guarded: Screen = {
      id: 'guarded',
      title: 'Guarded',
      render: () => <p>Guarded screen</p>,
      beforeClose: (close) => (closeLater = close),
    };
    render([guarded, two[0]!], 'guarded');
    await nav().getByRole('button', { name: 'First' }).click();
    await tab('Guarded').click();

    await page.getByRole('button', { name: 'Close Guarded' }).click();
    await expect.element(page.getByText('Guarded screen')).toBeVisible();
    expect(tabs().elements()).toHaveLength(2);

    closeLater!();
    await expect.element(tab('First')).toHaveAttribute('aria-selected', 'true');
    expect(tabs().elements()).toHaveLength(1);
  });

  test('on a narrow window hides the navigation until the Navigation button shows it', async () => {
    await page.viewport(414, 896);
    render(two, 'first');
    await expect.element(page.getByRole('button', { name: 'Navigation' })).toHaveAttribute('aria-expanded', 'false');
    await page.getByRole('button', { name: 'Navigation' }).click();
    await nav().getByRole('button', { name: 'Second' }).click();
    await expect.element(tab('Second')).toHaveAttribute('aria-selected', 'true');
  });
});
