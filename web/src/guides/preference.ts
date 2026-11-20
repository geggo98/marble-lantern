// The level of the move guides, remembered in localStorage. Every access is guarded: storage may be blocked.
// Three levels (docs/web-ui.md, "Move guides"): `none` marks nothing, `targets` marks the legal targets of the selected piece (the
// behaviour of the old switch in its "off" position), `eval` adds the evaluation (the old "on" position).
export const GUIDES_KEY = 'ml.guides';

export type GuideLevel = 'none' | 'targets' | 'eval';
export const GUIDE_LEVELS: readonly GuideLevel[] = ['none', 'targets', 'eval'];
export const DEFAULT_GUIDE_LEVEL: GuideLevel = 'targets';

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

/**
 * The stored text as a level. The two values of the old on/off switch map to what the person saw: "on" to `eval`, "off" to
 * `targets` (the old "off" still showed the legal targets). Anything else, or nothing, gives the default.
 */
export function parseGuideLevel(stored: string | null): GuideLevel {
  if (stored === 'on') return 'eval';
  if (stored === 'off') return 'targets';
  return (GUIDE_LEVELS as readonly (string | null)[]).includes(stored) ? (stored as GuideLevel) : DEFAULT_GUIDE_LEVEL;
}

/** Reads the level and writes an old "on"/"off" value back in the new form, once. */
export function loadGuideLevel(store: KeyValueStore | null = defaultStore()): GuideLevel {
  try {
    const stored = store?.getItem(GUIDES_KEY) ?? null;
    const level = parseGuideLevel(stored);
    if (stored === 'on' || stored === 'off') saveGuideLevel(level, store);
    return level;
  } catch {
    return DEFAULT_GUIDE_LEVEL;
  }
}

export function saveGuideLevel(level: GuideLevel, store: KeyValueStore | null = defaultStore()): void {
  try {
    store?.setItem(GUIDES_KEY, level);
  } catch {
    // the choice then lasts for this page only
  }
}
