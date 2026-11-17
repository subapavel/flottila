/**
 * The open tabs of the shell: the ids of the screens open, in the order they were opened, and the id of the one
 * shown, or null when none is open. Each screen is open in at most one tab.
 */
export interface OpenTabs {
  open: readonly string[];
  active: string | null;
}

export const noTabs: OpenTabs = { open: [], active: null };

/** Shows the screen `id`: a new tab at the end when it is not open yet, else the tab it already has. */
export function openTab(tabs: OpenTabs, id: string): OpenTabs {
  return { open: tabs.open.includes(id) ? tabs.open : [...tabs.open, id], active: id };
}

/**
 * Closes the tab of `id`. When it was the one shown, the tab to its right is shown next, or the one to its left when
 * it was the last. Closing a screen that is not open changes nothing.
 */
export function closeTab(tabs: OpenTabs, id: string): OpenTabs {
  const index = tabs.open.indexOf(id);
  if (index < 0) return tabs;
  const open = tabs.open.filter((other) => other !== id);
  if (tabs.active !== id) return { open, active: tabs.active };
  return { open, active: open[Math.min(index, open.length - 1)] ?? null };
}
