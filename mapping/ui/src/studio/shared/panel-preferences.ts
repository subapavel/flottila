import type { BrowserStateStore } from '../browser-state/browser-state';

/**
 * The Definition tab's viewer preferences (the prototype's panelPreferences.js) through `BrowserStateStore` (ADR-0028):
 * panel sizes and view modes, kept per user and so the same for every mapping.
 */
export const panelKey = (key: string) => `user:studio:${key}`;

/** The preference kept under `key` when `isValid` accepts it, else `fallback`. */
export function readPanelPreference<T>(browserState: BrowserStateStore, key: string, fallback: T, isValid: (value: unknown) => value is T): T {
  const value = browserState.read(panelKey(key));
  return isValid(value) ? value : fallback;
}

export function savePanelPreference(browserState: BrowserStateStore, key: string, value: unknown): void {
  browserState.write(panelKey(key), value);
}

/** A share of a split, from 0 to 1. */
export const isShare = (value: unknown): value is number => typeof value === 'number' && Number.isFinite(value) && value >= 0 && value <= 1;
