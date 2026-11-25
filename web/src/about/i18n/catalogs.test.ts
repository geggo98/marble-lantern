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
const PAGE_TOPICS = ABOUT_TOPICS.filter((t) => t !== 'glossary') as string[];
const TOPICS: readonly string[] = PAGE_TOPICS;
const OUTLINES = OUTLINE as Record<string, readonly string[]>;
const CARD_TOPICS: readonly string[] = ABOUT_TOPICS;

type Catalog = Record<string, string>;
const load = (lang: string, name: string): Catalog => JSON.parse(readFileSync(here(`./${lang}/${name}.json`), 'utf8')) as Catalog;

/** The references of a string, as sorted "kind:target" entries ({n:} values included, they are data). */
function references(text: string): string[] {
  return [...text.matchAll(/\{(?<kind>term|src|sec|n|sci):(?<body>[^{}|`]*)/g)].map((m) => `${m.groups!['kind']}:${m.groups!['body']}`).sort();
}

/** The {name} placeholders of a string (not the {term:} {src:} {sec:} {n:} markup, which has a colon), sorted. */
const placeholders = (text: string): string[] => [...text.matchAll(/\{(?<name>\w+)\}/g)].map((m) => m.groups!['name']!).sort();

const FIGURE_PREFIXES: Record<string, readonly string[]> = {
  architecture: ['fig.offline', 'fig.online', 'fig.pipeline', 'fig.tree'],
  classical: ['fig.ab', 'fig.eval', 'fig.budget', 'fig.race', 'fig.roots'],
  inference: ['fig.net', 'fig.forward', 'fig.mcts'],
  training: ['fig.loop', 'fig.pooled', 'fig.lr', 'fig.energy'],
  tuning: ['fig.story'],
  java: ['fig.javaspeed', 'fig.javaplain', 'fig.simd', 'fig.gpu', 'fig.j5'],
  'java-web': ['fig.hilla', 'fig.boundary'],
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
    const allowed = FIGURE_PREFIXES[topic] ?? [];
    const strays = Object.keys(en).filter((k) => k.startsWith('fig.')).filter((k) => !allowed.some((p) => k.startsWith(`${p}.`)));
    expect(strays).toEqual([]);
    expect(Object.keys(en).filter((k) => k.startsWith('fig.')).length).toBeGreaterThan(0);
  });

  it('has a title, a lead, and per section of the outline a toc entry, a heading and a first paragraph', () => {
    expect(en['title']).toBeTruthy();
    expect(en['lead']).toBeTruthy();
    for (const slug of OUTLINES[topic]!) {
      for (const key of [`toc.${slug}`, `sec.${slug}.title`, `sec.${slug}.p1`]) expect(en[key], key).toBeTruthy();
    }
  });

  it('has no section text for a slug that is not in the outline', () => {
    const slugs = new Set(OUTLINES[topic]);
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
          return !TOPICS.includes(t!) || !OUTLINES[t!]!.includes(s!);
        });
      expect(missing).toEqual([]);
    });
  }
});

// Length budgets of the texts (plan section 4; owner request of the text pass): a lead up to 60 words, a paragraph up to 90
// (110 in the data-science topics inference and training), a reading note up to 20, German up to 25 % more; at most three
// paragraphs per section. Words are counted as the reader sees them: a term or link counts its visible text, a source chip one word.
const BUDGET = { lead: 60, p: 90, pDs: 110, read: 20 } as const;
const DS_TOPICS = ['inference', 'training'];
// The two Java topics of round 3 (2026-11-22) came with texts that exceed the budgets above: lead up to 70 words, paragraphs up to 170
// (the longest is about 169, sec.tornadovm.p4 after the round-3 text review of 2026-11-22), reading notes up to 23, up to six paragraphs per section. Origin: the text writer delivered them with the figures in mind; the
// owner has not decided to shorten them. Limit: only these two topics; any other topic keeps the budgets above. Expiry: once the texts are shortened.
const JAVA_TOPICS: Record<string, { lead: number; p: number; read: number }> = { java: { lead: 70, p: 170, read: 23 }, 'java-web': { lead: 70, p: 170, read: 23 } };
// One section may have a fourth paragraph: topic training, section energy (Fig. 4.4). Origin: the text writer of 2026-11-25 needed four paragraphs (the method,
// the two groups of rows, the findings, the limits of the estimates), and the owner allowed it. Limit: only sec.energy.p4 of the topic training; every other
// section keeps three. Expiry: once the section is shortened to three paragraphs.
const FOURTH_PARAGRAPH = new Set(['training:sec.energy.p4']);
const visibleWords = (text: string): number =>
  text
    .replace(/\{(?<kind>[a-z]+):(?<target>[^{}|]*)(?:\|(?<label>[^{}]*))?\}/g, (_m, kind: string, target: string, label?: string) => label ?? (kind === 'src' ? 'x' : target))
    .split(/\s+/)
    .filter(Boolean).length;

describe.each(TOPICS)('text budgets of topic %s', (topic) => {
  for (const lang of LANGS) {
    it(`${lang}: lead, paragraphs and reading notes stay within their word budgets`, () => {
      const catalog = load(lang, topic);
      const factor = lang === 'de' ? 1.25 : 1;
      const limit = (key: string): number | undefined => {
        if (key === 'lead') return JAVA_TOPICS[topic]?.lead ?? BUDGET.lead;
        if (/^sec\.[^.]+\.p\d+$/.test(key)) return JAVA_TOPICS[topic]?.p ?? (DS_TOPICS.includes(topic) ? BUDGET.pDs : BUDGET.p);
        if (/^read\.[^.]+\.note$/.test(key)) return JAVA_TOPICS[topic]?.read ?? BUDGET.read;
        return undefined;
      };
      const over = Object.entries(catalog)
        .map(([key, text]) => ({ key, words: visibleWords(text), max: limit(key) }))
        .filter((r): r is { key: string; words: number; max: number } => r.max !== undefined && r.words > Math.floor(r.max * factor))
        .map(({ key, words, max }) => `${key}: ${words} words, budget ${Math.floor(max * factor)}`);
      expect(over).toEqual([]);
      const paragraphs = Object.keys(catalog).filter((k) => /^sec\.[^.]+\.p\d+$/.test(k));
      expect(paragraphs.length, 'paragraphs seen').toBeGreaterThan(0);
      expect(paragraphs.filter((k) => Number(k.split('.p')[1]) > (topic in JAVA_TOPICS ? 6 : 3)).filter((k) => !FOURTH_PARAGRAPH.has(`${topic}:${k}`))).toEqual([]);
    });
  }
});

// The key contract between the page of the topic java and its texts: the page looks these keys up by name, so a missing or renamed key
// would show the key itself on the page. The keys of the figures J2 to J7 are checked by the tests of those figures.
describe('the key contract of the topic java', () => {
  const en = load('en', 'java');
  const SNIPPETS = ['a1', 'a2', 'a3', 'b1', 'b2', 'd1', 'd2', 'e1', 'e3', 'illus'];
  const ROWS = ['java-scalar-b1', 'java-vec-1t', 'tornado-metal-b256', 'java-vec-avx512', 'tornado-cuda-t4', 'cpp-cuda-t4', 'teavm-scalar-b1', 'cpp-wasm-simd'];
  const FIG = ['title', 'desc', 'caption', 'axis', 'switch.label', 'switch.java', 'switch.all', 'group.mac', 'group.other', 'value', ...ROWS.map((r) => `row.${r}`)];

  it('has a caption for each snippet and no other snippet key', () => {
    expect(Object.keys(en).filter((k) => k.startsWith('snip.')).sort()).toEqual(SNIPPETS.map((s) => `snip.${s}.caption`).sort());
  });

  it('has every key of figure J1', () => {
    expect(Object.keys(en).filter((k) => k.startsWith('fig.javaspeed.')).sort()).toEqual(FIG.map((f) => `fig.javaspeed.${f}`).sort());
    expect(placeholders(en['fig.javaspeed.value']!)).toEqual(['value']);
  });

  it('has a reading note for each of the links 32 to 41 and 45 to 52', () => {
    const notes = Object.keys(en).filter((k) => k.startsWith('read.')).map((k) => Number(k.split('.')[1])).sort((a, b) => a - b);
    expect(notes).toEqual([32, 33, 34, 35, 36, 37, 38, 39, 40, 41, 45, 46, 47, 48, 49, 50, 51, 52]);
  });
});

describe('the key contract of the topic java-web', () => {
  const en = load('en', 'java-web');

  it('has a caption for each of its snippets and no other snippet key', () => {
    expect(Object.keys(en).filter((k) => k.startsWith('snip.')).sort()).toEqual(['e2', 'h1', 'h3', 'hgen'].map((s) => `snip.${s}.caption`).sort());
  });

  it('has a reading note for each of its 14 links', () => {
    const notes = Object.keys(en).filter((k) => k.startsWith('read.')).map((k) => Number(k.split('.')[1])).sort((a, b) => a - b);
    expect(notes).toEqual([25, 26, 42, 43, 44, 60, 61, 62, 63, 64, 65, 66, 67, 68]);
  });
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

  // Since the owner feedback of 2026-11-21 the German file names the English term only where it differs from the
  // German name (glossary-names.test.ts checks that rule), so `.en` is optional but never empty.
  it('has English terms in the German file only, none of them empty', () => {
    expect(Object.keys(en).filter((k) => k.endsWith('.en'))).toEqual([]);
    for (const id of ids(de)) if (`term.${id}.en` in de) expect(de[`term.${id}.en`], id).toBeTruthy();
    expect(ids(de).filter((id) => `term.${id}.en` in de).length).toBeGreaterThan(0);
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
    expect(Object.keys(en).length).toBe(85); // 59 + the 22 keys of the live mode (kit.live.*, kit.chip.example, mine, exampleOnly) + the 4 keys of the code snippet (kit.code.*)
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
    expect(page.length).toBe(15);
    expect(Object.keys(de).filter((k) => k.startsWith('glossary.')).sort()).toEqual([...page].sort());
    for (const k of page) expect(placeholders(de[k]!), k).toEqual(placeholders(en[k]!));
  });
});

describe('the reading list', () => {
  it('has all 61 rows keyed by the id as a string', () => {
    expect(Object.keys(READING)).toHaveLength(61);
    expect(READING['19']?.url).toBe('https://en.wikipedia.org/wiki/Elo_rating_system');
    expect(READING['27']?.id).toBe('27');
    expect(READING['31']?.id).toBe('31');
    expect(READING['44']?.url).toBe('https://teavm.org/docs/intro/overview.html');
  });

  it('lists the links of the topics java and java-web (ids 32 to 52 and 60 to 68), each verified on 2026-11-21 or 2026-11-22', () => {
    const java = Object.values(READING).filter((e) => Number(e.id) >= 32);
    expect(java.map((e) => e.id)).toEqual([...Array.from({ length: 21 }, (_, i) => String(32 + i)), ...Array.from({ length: 9 }, (_, i) => String(60 + i))]);
    for (const e of java) expect(e.verifiedOn, e.id).toBe(Number(e.id) <= 44 ? '2026-11-21' : '2026-11-22');
  });

  it('lists the four chapters of Russell and Norvig (Global Edition numbering) as paywalled book entries', () => {
    const aima = Object.values(READING).filter((e) => e.url === 'https://aima.cs.berkeley.edu/');
    expect(aima.map((e) => e.id)).toEqual(['28', '29', '30', '31']);
    expect(aima.map((e) => /Chapter (\d+):/.exec(e.title)?.[1])).toEqual(['6', '19', '22', '23']);
    for (const e of aima) expect(e.paywalled, e.id).toBe(true);
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
      const missing = CARD_TOPICS.flatMap((t) => ['kicker', 'title', 'teaser', 'time'].map((p) => `about_learn_${t}_${p}`)).filter((k) => !main[k]);
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
