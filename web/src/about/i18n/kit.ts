// The shared labels of the figure kit (kit.* keys): legends, stepper words, tags, node kinds. One catalog for all topics, so that Vite
// puts it in the chunk that the topic pages share. The kit components take their labels as props; a topic page looks them up here.
import en from './en/kit.json';
import de from './de/kit.json';
import { makeAboutCatalog } from './catalog';
import type { SameKeys } from './catalog';

export const kitCatalog = makeAboutCatalog({ en, de });
export const kitMatch: SameKeys<typeof en, typeof de> = true;
export type KitKey = keyof typeof en;
