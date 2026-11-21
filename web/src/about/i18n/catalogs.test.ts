// Catalog checks for every topic pair (plan section 1.6): same keys in both languages, the same references per key,
// every {term:} id in the glossary, every {src:} path in the repository, every {sec:} target in the outline.
import { existsSync, readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { OUTLINE } from '../topics/outline';
import { GLOSSARY_IDS } from './glossary';
import { READING } from '../reading';
import { ABOUT_TOPICS } from '../routes';

const here = (file: string): string => fileURLToPath(new URL(file, import.meta.url));
const repoRoot = here('../../../../'); // web/src/about/i18n -> repository root
const LANGS = ['en', 'de'] as const;
const TOPICS = ABOUT_TOPICS.filter((t): t is Exclude<typeof t, 'glossary'> => t !== 'glossary');

type Catalog = Record<string, string>;
const load = (lang: string, name: string): Catalog => JSON.parse(readFileSync(here(`./${lang}/${name}.json`), 'utf8')) as Catalog;

/** The references of a string, as sorted "kind:target" entries ({n:} values included, they are data). */
function references(text: string): string[] {
  return [...text.matchAll(/\{(?<kind>term|src|sec|n):(?<body>[^{}|`]*)/g)].map((m) => `${m.groups!['kind']}:${m.groups!['body']}`).sort();
}

/** The {name} placeholders of a string (not the {term:} {src:} {sec:} {n:} markup, which has a colon), sorted. */
const placeholders = (text: string): string[] => [...text.matchAll(/\{(?<name>\w+)\}/g)].map((m) => m.groups!['name']!).sort();

const FIGURE_PREFIXES: Record<(typeof TOPICS)[number], readonly string[]> = {
  architecture: ['fig.offline', 'fig.online', 'fig.pipeline', 'fig.tree'],
  classical: ['fig.ab', 'fig.eval', 'fig.budget', 'fig.race', 'fig.roots'],
  inference: ['fig.net', 'fig.forward', 'fig.mcts'],
  training: ['fig.loop', 'fig.pooled', 'fig.lr'],
  tuning: ['fig.story'],
};

describe.each(TOPICS)('topic catalog %s', (topic) => {
  const en = load('en', topic);
  const de = load('de', topic);

  it('has the same keys in German and English', () => {
    expect(Object.keys(de).sort()).toEqual(Object.keys(en).sort());
  });

  it('has the same references in both languages, key by key', () => {
    const differing = Object.keys(en).filter((k) => JSON.stringify(references(en[k]!)) !== JSON.stringify(references(de[k] ?? '')));
    expect(differing).toEqual([]);
  });

  it('has the same {name} placeholders in both languages, key by key', () => {
    expect(Object.keys(en).filter((k) => JSON.stringify(placeholders(en[k]!)) !== JSON.stringify(placeholders(de[k] ?? '')))).toEqual([]);
  });

  it('has a reading note only for ids of the reading list, and each id of the page has a note', () => {
    const noted = Object.keys(en).filter((k) => /^read\.[^.]+\.note$/.test(k)).map((k) => k.split('.')[1]!);
    expect(noted.filter((id) => !(id in READING))).toEqual([]);
    expect(noted.length).toBeGreaterThan(0);
  });

  it('keeps the figure keys under the figure prefixes of this topic', () => {
    const allowed = FIGURE_PREFIXES[topic];
    const strays = Object.keys(en).filter((k) => k.startsWith('fig.')).filter((k) => !allowed.some((p) => k.startsWith(`${p}.`)));
    expect(strays).toEqual([]);
    expect(Object.keys(en).filter((k) => k.startsWith('fig.')).length).toBeGreaterThan(0);
  });

  it('has a title, a lead, and per section of the outline a toc entry, a heading and a first paragraph', () => {
    expect(en['title']).toBeTruthy();
    expect(en['lead']).toBeTruthy();
    for (const slug of OUTLINE[topic]) {
      for (const key of [`toc.${slug}`, `sec.${slug}.title`, `sec.${slug}.p1`]) expect(en[key], key).toBeTruthy();
    }
  });

  it('has no section text for a slug that is not in the outline', () => {
    const slugs = new Set(OUTLINE[topic]);
    const strays = Object.keys(en).filter((k) => /^(toc|sec)\./.test(k)).filter((k) => !slugs.has(k.split('.')[1]!));
    expect(strays).toEqual([]);
  });

  for (const lang of LANGS) {
    const catalog = lang === 'en' ? en : de;
    const refs = Object.entries(catalog).flatMap(([key, text]) => references(text).map((r) => ({ key, r })));

    it(`${lang}: every {term:} id exists in the glossary`, () => {
      const missing = refs.filter(({ r }) => r.startsWith('term:')).map(({ key, r }) => ({ key, id: r.slice(5).split('|')[0]! })).filter(({ id }) => !GLOSSARY_IDS.includes(id));
      expect(missing).toEqual([]);
    });

    it(`${lang}: every {src:} path exists in the repository`, () => {
      const missing = refs.filter(({ r }) => r.startsWith('src:')).map(({ key, r }) => ({ key, path: r.slice(4) })).filter(({ path }) => !existsSync(`${repoRoot}${path}`));
      expect(missing).toEqual([]);
    });

    it(`${lang}: every {sec:} target exists in the outline`, () => {
      const missing = refs
        .filter(({ r }) => r.startsWith('sec:'))
        .map(({ key, r }) => ({ key, target: r.slice(4) }))
        .filter(({ target }) => {
          const [t, s] = target.split('/');
          if (t === 'glossary') return !GLOSSARY_IDS.includes(s!); // {sec:glossary/<term id>} points at a glossary entry
          return !(TOPICS as readonly string[]).includes(t!) || !OUTLINE[t as (typeof TOPICS)[number]].includes(s!);
        });
      expect(missing).toEqual([]);
    });
  }
});

describe('glossary catalog', () => {
  const en = load('en', 'glossary');
  const de = load('de', 'glossary');
  const ids = (c: Catalog): string[] => Object.keys(c).filter((k) => k.endsWith('.name')).map((k) => k.split('.')[1]!).sort();

  it('has the same terms in both languages', () => {
    expect(ids(de)).toEqual(ids(en));
  });

  it('gives every term a name and a short definition in both languages', () => {
    for (const id of ids(en)) for (const c of [en, de]) for (const part of ['name', 'short']) expect(c[`term.${id}.${part}`], `${id}.${part}`).toBeTruthy();
  });

  it('has the English term of every entry in the German file only', () => {
    expect(Object.keys(en).filter((k) => k.endsWith('.en'))).toEqual([]);
    for (const id of ids(de)) expect(de[`term.${id}.en`], id).toBeTruthy();
  });

  it('lists a long definition in both languages or in neither', () => {
    const longs = (c: Catalog): string[] => Object.keys(c).filter((k) => k.endsWith('.long')).sort();
    expect(longs(de)).toEqual(longs(en));
  });

  it('uses slug-shaped ids that the route accepts', () => {
    expect(ids(en).filter((id) => !/^[a-z0-9][a-z0-9-]*$/.test(id))).toEqual([]);
  });
});

describe('kit catalog', () => {
  const en = load('en', 'kit');
  const de = load('de', 'kit');

  it('has the same keys and the same placeholders in both languages, all filled, all under kit.', () => {
    expect(Object.keys(de).sort()).toEqual(Object.keys(en).sort());
    expect(Object.keys(en).length).toBe(80); // 59 + the 21 keys of the live mode (kit.live.*, kit.chip.example, mine, exampleOnly)
    expect(Object.keys(en).filter((k) => !k.startsWith('kit.'))).toEqual([]);
    for (const k of Object.keys(en)) {
      expect(de[k], k).toBeTruthy();
      expect(placeholders(de[k]!), k).toEqual(placeholders(en[k]!));
    }
  });
});

describe('glossary page keys', () => {
  it('exist in both languages with the same placeholders', () => {
    const en = load('en', 'glossary');
    const de = load('de', 'glossary');
    const page = Object.keys(en).filter((k) => k.startsWith('glossary.'));
    expect(page.length).toBe(12);
    expect(Object.keys(de).filter((k) => k.startsWith('glossary.')).sort()).toEqual([...page].sort());
    for (const k of page) expect(placeholders(de[k]!), k).toEqual(placeholders(en[k]!));
  });
});

describe('the reading list', () => {
  it('has all 27 rows keyed by the id as a string', () => {
    expect(Object.keys(READING)).toHaveLength(27);
    expect(READING['19']?.url).toBe('https://en.wikipedia.org/wiki/Elo_rating_system');
    expect(READING['27']?.id).toBe('27');
  });
});

describe('chrome catalog', () => {
  it('has the same keys in both languages, all filled, and the keys the topic page asks for', () => {
    const en = load('en', 'chrome');
    const de = load('de', 'chrome');
    expect(Object.keys(de).sort()).toEqual(Object.keys(en).sort());
    expect(Object.keys(en).sort()).toEqual(['glossary_more', 'next', 'prev', 'reading', 'toc']);
    for (const c of [en, de]) for (const [k, v] of Object.entries(c)) expect(v, k).toBeTruthy();
  });
});

describe('the main catalogs hold a card for every topic', () => {
  for (const lang of LANGS) {
    const main = JSON.parse(readFileSync(here(`../../i18n/${lang}.json`), 'utf8')) as Catalog;
    it(`${lang}: kicker, title, teaser and time of every topic, and the not-found notice`, () => {
      const missing = ABOUT_TOPICS.flatMap((t) => ['kicker', 'title', 'teaser', 'time'].map((p) => `about_learn_${t}_${p}`)).filter((k) => !main[k]);
      expect(missing).toEqual([]);
      expect(main['about_notfound']).toBeTruthy();
      for (const k of ['about_learn_kicker', 'about_learn_minutes', 'about_learn_terms']) expect(main[k], k).toBeTruthy();
    });
  }
});

// One German term per concept. Origin: the fresh-eyes review of 2026-11-21 found "Schleifenlauf" next to "Loop-Lauf",
// "Figur" next to "Stein", "Genehmigung" next to "Permit" and more; a reader who looks a word up does not connect two names.
// Each entry is a rejected variant and the term the catalogs use instead. Limit: this catches only the variants listed here.
describe('German terms', () => {
  const VARIANTS: ReadonlyArray<readonly [RegExp, string]> = [
    [/Schleifenlauf/, 'Loop-Lauf'],
    [/Wettlauf|Renn(en|stellung|modul)/, 'Endspiel, Endspielstellung, Endspieltabelle'],
    [/Endspieldatenbank/, 'Tablebase'],
    [/\bSteine?n?\b/, 'Figur'],
    [/Personen|pro Person\b|(nächsten|dritten) Person\b/, 'Spieler or Seite'],
    [/Partieserie|\bSerie\b|Quote\b/, 'Match and Score'],
    [/thread-?safe|thread-sicher/, 'threadsicher'],
    [/Zielspitze|Startspitze|oberen Spitze/, 'Ecke'],
    [/aufgestiegen|steigt nur auf|aufsteigt/, 'befördert'],
    [/Stoppregel/, 'Abbruchkriterium'],
    [/Teacher-Temperatur/, 'Lehrer-Temperatur'],
    [/Geräte-Thread/, 'Device-Thread'],
    [/Kastendiagramm/, 'Blockdiagramm'],
    [/Engine-Sitzung/, 'Engine Session'],
    [/\bein \{term:softmax|\bein Softmax/, 'eine Softmax'],
  ];
  const main = JSON.parse(readFileSync(here('../../i18n/de.json'), 'utf8')) as Catalog;
  const catalogs: Record<string, Catalog> = {
    hub: Object.fromEntries(Object.entries(main).filter(([k]) => k.startsWith('about_learn_'))),
    ...Object.fromEntries([...TOPICS, 'glossary', 'kit'].map((name) => [name, load('de', name)])),
  };
  const entries = Object.entries(catalogs).flatMap(([name, c]) => Object.entries(c).filter(([k]) => !k.endsWith('.en')).map(([k, v]) => ({ where: `${name}:${k}`, v })));

  it('reads every German catalog of the Info part', () => {
    expect(entries.length).toBeGreaterThan(1000);
  });

  it.each(VARIANTS.map(([re, use]) => [String(re), re, use] as const))('has no %s', (_, re, use) => {
    expect(entries.filter(({ v }) => re.test(v)).map(({ where }) => `${where}: use ${use}`)).toEqual([]);
  });

  it('says "Permit" and glosses it as "Genehmigung" once, where the semaphore is introduced', () => {
    expect(entries.filter(({ v }) => /Genehmigung/.test(v)).map(({ where }) => where)).toEqual(['architecture:sec.online.p3']);
  });
});
