// The on/off choice of the guides, remembered in localStorage. Every access is guarded: storage may be blocked.
export const GUIDES_KEY = 'ml.guides';

export interface KeyValueStore {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
}

function defaultStore(): KeyValueStore | null {
  try {
    return globalThis.localStorage ?? null;
  } catch {
    return null;
  }
}

/** Off unless the stored value is exactly "on". */
export function loadGuidesPreference(store: KeyValueStore | null = defaultStore()): boolean {
  try {
    return store?.getItem(GUIDES_KEY) === 'on';
  } catch {
    return false;
  }
}

export function saveGuidesPreference(on: boolean, store: KeyValueStore | null = defaultStore()): void {
  try {
    store?.setItem(GUIDES_KEY, on ? 'on' : 'off');
  } catch {
    // the choice then lasts for this page only
  }
}
