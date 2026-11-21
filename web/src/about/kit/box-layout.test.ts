// The layout of a box diagram from the real data files (design notes, section 6 "BoxDiagram"): at 320 px the compact layout keeps the 44 px
// targets, boxes stay inside their regions and never overlap, and no edge runs through a box that is not one of its ends.
import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { headPath, layoutBoxes, midOfLongest, pathOf, simplify, wrapLabel } from './box-layout';
import type { ArchData, BoxLayout, Pt, Rect } from './box-layout';

const load = (name: string): ArchData => JSON.parse(readFileSync(new URL(`../data/${name}`, import.meta.url), 'utf8')) as ArchData;
const FILES = ['arch-offline.json', 'arch-online.json', 'arch-training.json'];

/** Unit of the compact layout at a viewport of 320 px: the figure has a 1 px frame and 12 px padding, and the diagram a 264 unit view box. */
const UNIT_AT_320 = (320 - 2 - 24) / 264;

const inside = (a: Rect, b: Rect): boolean => a.x >= b.x && a.y >= b.y && a.x + a.w <= b.x + b.w && a.y + a.h <= b.y + b.h;
const overlap = (a: Rect, b: Rect): boolean => a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h;

/** True when the axis-aligned segment p-q enters the open interior of the rectangle. */
function cuts(p: Pt, q: Pt, r: Rect): boolean {
  const x0 = Math.min(p.x, q.x);
  const x1 = Math.max(p.x, q.x);
  const y0 = Math.min(p.y, q.y);
  const y1 = Math.max(p.y, q.y);
  return x1 > r.x && x0 < r.x + r.w && y1 > r.y && y0 < r.y + r.h;
}

const onBoundary = (p: Pt, r: Rect): boolean => {
  const eps = 1e-6;
  const withinX = p.x >= r.x - eps && p.x <= r.x + r.w + eps;
  const withinY = p.y >= r.y - eps && p.y <= r.y + r.h + eps;
  return withinX && withinY && (Math.abs(p.x - r.x) < eps || Math.abs(p.x - r.x - r.w) < eps || Math.abs(p.y - r.y) < eps || Math.abs(p.y - r.y - r.h) < eps);
};

describe.each(FILES)('layout of %s', (file) => {
  const data = load(file);

  describe.each(['wide', 'compact'] as const)('%s', (mode) => {
    const laid: BoxLayout = layoutBoxes(data, mode);
    const node = new Map(laid.nodes.map((n) => [n.id, n]));

    it('places every box once, inside its region, without overlap', () => {
      expect(laid.nodes.map((n) => n.id).sort()).toEqual(data.boxes.map((b) => b.id).sort());
      for (const n of laid.nodes) {
        const g = laid.groups.find((x) => x.id === n.group);
        expect(g && inside(n, g), `${n.id} inside ${n.group}`).toBe(true);
        expect(n.x).toBeGreaterThanOrEqual(0);
        expect(n.x + n.w).toBeLessThanOrEqual(laid.width);
      }
      for (let i = 0; i < laid.nodes.length; i++) {
        for (let k = i + 1; k < laid.nodes.length; k++) {
          const a = laid.nodes[i] as Rect & { id: string };
          const b = laid.nodes[k] as Rect & { id: string };
          expect(overlap(a, b), `${a.id} and ${b.id}`).toBe(false);
        }
      }
      for (let i = 0; i < laid.groups.length; i++) {
        for (let k = i + 1; k < laid.groups.length; k++) expect(overlap(laid.groups[i] as Rect, laid.groups[k] as Rect)).toBe(false);
      }
    });

    it('routes every edge at right angles from the edge of its source to the edge of its target, through no other box', () => {
      expect(laid.edges).toHaveLength(data.edges.length);
      for (const e of laid.edges) {
        const from = node.get(e.from) as Rect;
        const to = node.get(e.to) as Rect;
        expect(onBoundary(e.points[0] as Pt, from), `edge ${e.index} starts on ${e.from}`).toBe(true);
        expect(onBoundary(e.points[e.points.length - 1] as Pt, to), `edge ${e.index} ends on ${e.to}`).toBe(true);
        for (let i = 1; i < e.points.length; i++) {
          const p = e.points[i - 1] as Pt;
          const q = e.points[i] as Pt;
          expect(p.x === q.x || p.y === q.y, `edge ${e.index} segment ${i} is axis-aligned`).toBe(true);
          expect(p.x >= 0 && p.x <= laid.width && p.y >= 0 && p.y <= laid.height, `edge ${e.index} stays in the view box`).toBe(true);
          for (const n of laid.nodes) {
            if (n.id === e.from || n.id === e.to) continue;
            expect(cuts(p, q, n), `edge ${e.index} (${e.from} to ${e.to}) crosses ${n.id}`).toBe(false);
          }
        }
        // the segment at an end must not run inside its own box either (it leaves or enters at the boundary)
        const first = [e.points[0] as Pt, e.points[1] as Pt] as const;
        expect(cuts(first[0], first[1], from), `edge ${e.index} leaves ${e.from}`).toBe(false);
      }
    });
  });

  it('compact: 264 units wide, and at 320 px every box is at least 44 px in both directions', () => {
    const laid = layoutBoxes(data, 'compact');
    expect(laid.width).toBe(264);
    for (const n of laid.nodes) {
      expect(n.w * UNIT_AT_320, `${n.id} width`).toBeGreaterThanOrEqual(44);
      expect(n.h * UNIT_AT_320, `${n.id} height`).toBeGreaterThanOrEqual(44);
    }
  });

  it('wide: 720 units wide, boxes of 144 x 60, at most four in a row', () => {
    const laid = layoutBoxes(data, 'wide');
    expect(laid.width).toBe(720);
    expect(new Set(laid.nodes.map((n) => `${n.w}x${n.h}`))).toEqual(new Set(['144x60']));
    const perRow = new Map<number, number>();
    for (const n of laid.nodes) perRow.set(n.row, (perRow.get(n.row) ?? 0) + 1);
    expect(Math.max(...perRow.values())).toBeLessThanOrEqual(4);
  });
});

describe('compact rules of the notes', () => {
  const laid = layoutBoxes(load('arch-offline.json'), 'compact');
  it('a region of three or more boxes uses rows of three (70 x 50), a smaller region full-width boxes (224 x 46)', () => {
    const size = (id: string): string => {
      const n = laid.nodes.find((x) => x.id === id) as Rect;
      return `${n.w}x${n.h}`;
    };
    expect(size('page')).toBe('70x50'); // "main thread" has eight boxes
    expect(size('serviceWorker')).toBe('224x46'); // a region of one box
    expect(size('raceTable')).toBe('224x46');
  });

  it('keeps the first row of a three-box region left to right in file order', () => {
    const [a, b, c] = ['page', 'board', 'gameUrl'].map((id) => laid.nodes.find((n) => n.id === id) as Rect & { row: number }) as [Rect & { row: number }, Rect & { row: number }, Rect & { row: number }];
    expect(a.row === b.row && b.row === c.row).toBe(true);
    expect(a.x < b.x && b.x < c.x).toBe(true);
  });
});

describe('region size decides the compact box size', () => {
  const box = (id: string, runsIn: string): { id: string; label: string; runsIn: string } => ({ id, label: id, runsIn });
  const sizes = (count: number): Set<string> => {
    const boxes = Array.from({ length: count }, (_, i) => box(`b${i}`, 'r'));
    return new Set(layoutBoxes({ boxes, edges: [] }, 'compact').nodes.map((n) => `${n.w}x${n.h}`));
  };
  it('one and two boxes are full width; three and more are three to a row (the boundary is three)', () => {
    expect(sizes(1)).toEqual(new Set(['224x46']));
    expect(sizes(2)).toEqual(new Set(['224x46']));
    expect(sizes(3)).toEqual(new Set(['70x50']));
    expect(sizes(4)).toEqual(new Set(['70x50']));
  });
  it('four boxes make a second row of one', () => {
    const rows = layoutBoxes({ boxes: Array.from({ length: 4 }, (_, i) => box(`b${i}`, 'r')), edges: [] }, 'compact').nodes.map((n) => n.row);
    expect(rows).toEqual([0, 0, 0, 1]);
  });
});

describe('errors in the data', () => {
  it('names the edge when a box does not exist, and refuses a self loop', () => {
    const boxes = [{ id: 'a', label: 'A' }, { id: 'b', label: 'B' }];
    expect(() => layoutBoxes({ boxes, edges: [{ from: 'a', to: 'zzz', kind: 'data' }] }, 'wide')).toThrow(/edge 0.*zzz/);
    expect(() => layoutBoxes({ boxes, edges: [{ from: 'a', to: 'a', kind: 'data' }] }, 'wide')).toThrow(/itself/);
  });
});

describe('path helpers', () => {
  it('simplify drops repeated points and points inside a straight run', () => {
    expect(simplify([{ x: 0, y: 0 }, { x: 0, y: 0 }, { x: 0, y: 5 }, { x: 0, y: 9 }, { x: 4, y: 9 }])).toEqual([{ x: 0, y: 0 }, { x: 0, y: 9 }, { x: 4, y: 9 }]);
  });

  it('the label sits in the middle of the longest segment', () => {
    expect(midOfLongest([{ x: 0, y: 0 }, { x: 0, y: 10 }, { x: 40, y: 10 }])).toEqual({ x: 20, y: 10 });
  });

  it('a data head is a closed triangle, a control head an open angle; both end in the tip', () => {
    const pts = [{ x: 0, y: 0 }, { x: 20, y: 0 }];
    expect(pathOf(pts)).toBe('M0 0L20 0');
    expect(headPath(pts, 'data', 10)).toBe('M20 0L10 5L10 -5Z');
    expect(headPath(pts, 'control', 10)).toBe('M10 5L20 0L10 -5');
  });
});

// ---- the wide layout of the design review (B2) and the compact fixes (M1) ----------------------------------------------------------

import { WIDE_BANDS, arrange } from '../figures/arch-model';
import type { ArchKind, RawArch } from '../figures/arch-model';

const KINDS: readonly (readonly [ArchKind, string])[] = [['offline', 'arch-offline.json'], ['online', 'arch-online.json'], ['pipeline', 'arch-training.json']];
const arranged = (kind: ArchKind, file: string): ArchData => arrange(kind, JSON.parse(readFileSync(new URL(`../data/${file}`, import.meta.url), 'utf8')) as RawArch).data;
const gapBetween = (a: Rect, b: Rect): number => Math.max(b.x - (a.x + a.w), a.x - (b.x + b.w), b.y - (a.y + a.h), a.y - (b.y + b.h));

describe.each(KINDS)('figure %s in the layout of the figure', (kind, file) => {
  const data = arranged(kind, file);

  it('wide: the regions of a band stand side by side at one height, the bands one below the other', () => {
    const laid = layoutBoxes(data, 'wide', { bands: WIDE_BANDS[kind] as string[][] });
    const rect = new Map(laid.groups.map((g) => [g.id, g]));
    for (const band of WIDE_BANDS[kind]) {
      const regions = band.map((id) => rect.get(id) as Rect);
      for (const r of regions) expect(r.y).toBe((regions[0] as Rect).y);
      for (let i = 1; i < regions.length; i++) expect((regions[i] as Rect).x).toBeGreaterThan(((regions[i - 1] as Rect).x + (regions[i - 1] as Rect).w));
    }
    expect(laid.width).toBe(720);
    // far shorter than the stacked layout of the first version (V1 was 2,000 to 2,400 px tall at 820 px)
    expect(laid.height).toBeLessThan(800);
  });

  it('wide: every label sits clear of all boxes, of the region labels and of the other labels', () => {
    const laid = layoutBoxes(data, 'wide', { bands: WIDE_BANDS[kind] as string[][] });
    const labels = laid.edges.flatMap((e) => (e.label ? [{ index: e.index, r: { x: e.label.x - e.label.w / 2, y: e.label.y - e.label.h / 2, w: e.label.w, h: e.label.h } }] : []));
    expect(labels.length).toBeGreaterThan(data.edges.length / 2);
    for (const { index, r } of labels) {
      for (const n of laid.nodes) expect(overlap(r, n), `label of edge ${index} over ${n.id}`).toBe(false);
      for (const [g, rect] of Object.entries(laid.groupLabels)) expect(overlap(r, rect), `label of edge ${index} over the label of ${g}`).toBe(false);
      expect(r.x >= 0 && r.y >= 0 && r.x + r.w <= laid.width && r.y + r.h <= laid.height, `label of edge ${index} in the view box`).toBe(true);
      for (const other of labels) if (other.index < index) expect(overlap(r, other.r), `labels of edges ${other.index} and ${index}`).toBe(false);
    }
    // an edge without a label has a marker, and the markers keep apart
    const numbered = laid.edges.filter((e) => e.numbered);
    for (const e of laid.edges) expect(Boolean(e.label) !== e.numbered, `edge ${e.index} has a label or a marker, not both`).toBe(true);
    for (let i = 0; i < numbered.length; i++) for (let k = i + 1; k < numbered.length; k++) expect(Math.hypot((numbered[i] as { mid: Pt }).mid.x - (numbered[k] as { mid: Pt }).mid.x, (numbered[i] as { mid: Pt }).mid.y - (numbered[k] as { mid: Pt }).mid.y)).toBeGreaterThanOrEqual(20);
  });

  it('wide: no edge runs sideways through the narrow strip between two neighbours in a row, and none crosses a region label', () => {
    const laid = layoutBoxes(data, 'wide', { bands: WIDE_BANDS[kind] as string[][] });
    for (const e of laid.edges) {
      for (const [p, q] of e.points.slice(1).map((pt, i) => [e.points[i] as Pt, pt] as const)) {
        for (const [g, rect] of Object.entries(laid.groupLabels)) expect(cuts(p, q, rect), `edge ${e.index} crosses the label of ${g}`).toBe(false);
        if (p.y !== q.y) continue; // sideways
        for (const a of laid.nodes) {
          for (const b of laid.nodes) {
            if (a.row !== b.row || b.x < a.x + a.w || gapBetween(a, b) >= 56) continue;
            const strip = { x: a.x + a.w, y: a.y + 1, w: b.x - (a.x + a.w), h: a.h - 2 };
            expect(cuts(p, q, strip), `edge ${e.index} runs sideways between ${a.id} and ${b.id}`).toBe(false);
          }
        }
      }
    }
  });

  it('compact: no edge crosses a region label, and the numbered markers are at least 20 units apart, clear of the boxes', () => {
    const laid = layoutBoxes(data, 'compact');
    for (const e of laid.edges) {
      for (const [p, q] of e.points.slice(1).map((pt, i) => [e.points[i] as Pt, pt] as const)) {
        for (const [g, rect] of Object.entries(laid.groupLabels)) expect(cuts(p, q, rect), `edge ${e.index} crosses the label of ${g}`).toBe(false);
      }
    }
    for (let i = 0; i < laid.edges.length; i++) {
      const a = (laid.edges[i] as { mid: Pt }).mid;
      expect(laid.nodes.every((n) => !(a.x > n.x && a.x < n.x + n.w && a.y > n.y && a.y < n.y + n.h)), `marker ${i + 1} is over a box`).toBe(true);
      for (let k = i + 1; k < laid.edges.length; k++) {
        const b = (laid.edges[k] as { mid: Pt }).mid;
        expect(Math.hypot(a.x - b.x, a.y - b.y), `markers ${i + 1} and ${k + 1}`).toBeGreaterThanOrEqual(20);
      }
    }
  });
});

describe('wrapLabel', () => {
  it('breaks at spaces into at most the asked number of lines and keeps every word', () => {
    expect(wrapLabel('gzip, length and SHA-256 checked', 2)).toEqual(['gzip, length and', 'SHA-256 checked']);
    expect(wrapLabel('postMessage', 3)).toEqual(['postMessage']);
    for (const n of [1, 2, 3]) expect(wrapLabel('table bytes in init', n).join(' ')).toBe('table bytes in init');
  });
});
