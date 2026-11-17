import { describe, expect, it } from 'vitest';
import { closeTab, noTabs, openTab } from './tabs';

describe('openTab', () => {
  it('adds a tab at the end and shows it', () => {
    const tabs = openTab(openTab(noTabs, 'home'), 'search');
    expect(tabs).toEqual({ open: ['home', 'search'], active: 'search' });
  });

  it('shows the tab a screen already has instead of opening a second one', () => {
    const tabs = openTab(openTab(openTab(noTabs, 'home'), 'search'), 'home');
    expect(tabs).toEqual({ open: ['home', 'search'], active: 'home' });
  });
});

describe('closeTab', () => {
  const three = { open: ['a', 'b', 'c'], active: 'b' };

  it('shows the tab to the right of the closed one', () => {
    expect(closeTab(three, 'b')).toEqual({ open: ['a', 'c'], active: 'c' });
  });

  it('shows the tab to the left when the last one is closed', () => {
    expect(closeTab({ open: ['a', 'b', 'c'], active: 'c' }, 'c')).toEqual({ open: ['a', 'b'], active: 'b' });
  });

  it('keeps the shown tab when another one is closed', () => {
    expect(closeTab(three, 'a')).toEqual({ open: ['b', 'c'], active: 'b' });
  });

  it('shows nothing once the only tab is closed', () => {
    expect(closeTab(openTab(noTabs, 'a'), 'a')).toEqual(noTabs);
  });

  it('changes nothing for a screen that is not open', () => {
    expect(closeTab(three, 'x')).toBe(three);
  });
});
