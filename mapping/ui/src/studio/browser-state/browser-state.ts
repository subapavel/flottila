/** The port for the viewer's own layout and view state, kept in the browser. */
export interface BrowserStateStore {
  /** The value written under `key`, or `undefined` when there is none or it cannot be read. */
  read(key: string): unknown;
  /** Keeps `value` under `key`; a failure is ignored, and the next read falls back to the defaults. */
  write(key: string, value: unknown): void;
}

const PREFIX = 'rail8-mapping:v1:';

/** The key of a target field mapping's canvas, its box positions and zoom, by its authoring id. */
export function canvasKey(mappingId: string, targetFieldMappingId: string): string {
  return `mapping:${mappingId}:canvas:${targetFieldMappingId}`;
}

/** The key of a mapping's selected target field, selected again on the next open. */
export function targetFieldKey(mappingId: string): string {
  return `mapping:${mappingId}:target-field`;
}

/**
 * The key of a mapping's test source document, kept per mapping: the Test tab's session
 * writes it, and the Definition tab's queue test input reads it.
 */
export const testSourceKey = (mappingId: string) => `mapping:${mappingId}:test-source`;

/** The default `BrowserStateStore`, over `localStorage` or another `Storage`, with every call wrapped. */
export function storageBrowserState(storage: Pick<Storage, 'getItem' | 'setItem'>): BrowserStateStore {
  return {
    read(key) {
      try {
        const text = storage.getItem(PREFIX + key);
        return text === null ? undefined : (JSON.parse(text) as unknown);
      } catch {
        return undefined;
      }
    },
    write(key, value) {
      try {
        storage.setItem(PREFIX + key, JSON.stringify(value));
      } catch {
        // full or blocked storage: the state is lost, the editor still works
      }
    },
  };
}
