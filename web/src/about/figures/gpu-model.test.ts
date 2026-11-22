// Model and data of figure J4 (GPU anatomy): the derived numbers of the kernel grid follow from the repository's constants, the active parts of
// each step match the spec (section 6), and no unknown slot ever carries a number. The cited lines of the repository are read and checked.
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import {
  AKA,
  CMP_ROWS,
  GRID,
  GROUP_THREADS,
  GROUP_WARPS,
  HW_IDS,
  MACHINES,
  PART_OF,
  STEPS,
  STEP_COUNT,
  WARP_SIZE,
  activeParts,
  akaText,
  cmpCell,
  gridCounts,
  ladderRows,
  narrationKey,
  narrationValues,
  partFacts,
  partState,
  partsOf,
  quantityText,
  roundedCount,
  variantFor,
} from './gpu-model';
import type { HwId } from './gpu-model';
import { javaCatalog } from './java-catalog';
import raw from '../data/gpu-anatomy.json';

const REPO = fileURLToPath(new URL('../../../../', import.meta.url));
const lineOf = (file: string, line: number): string => readFileSync(`${REPO}${file}`, 'utf8').split('\n')[line - 1] ?? '';
const fmt = (v: number): string => new Intl.NumberFormat('en').format(v);
const atMost = (v: string): string => `at most ${v}`;

describe('gpu-anatomy.json', () => {
  it('cites lines of the repository that contain the text the figure relies on', () => {
    for (const [name, s] of Object.entries(GRID.sources)) {
      expect(lineOf(s.file, s.line), `${name}: ${s.file}:${s.line}`).toContain(s.needle);
    }
  });

  it('takes the grid constants from KernelsHexConv.java and HexGrid.java', () => {
    const kernels = readFileSync(`${REPO}nn-tornado/src/main/java/sternhalma/tornado/KernelsHexConv.java`, 'utf8');
    const num = (name: string): number => Number(new RegExp(`${name}\\s*=\\s*(\\d+);`).exec(kernels)?.[1]);
    expect(GRID.simdgroup.tile).toBe(num('SG_TILE'));
    expect(GRID.simdgroup.groupSize).toBe(num('SG_GROUP_SIZE'));
    expect(GRID.tiled.tileN).toBe(num('TILE_N'));
    expect(GRID.tiled.tileM).toBe(num('TILE_M'));
    expect(GRID.tiled.groupX).toBe(GRID.tiled.tileN / num('MICRO_N'));
    expect(GRID.tiled.groupY).toBe(GRID.tiled.tileM / num('MICRO_M'));
    expect(GRID.tiled.groupX * GRID.tiled.groupY).toBe(GRID.simdgroup.groupSize);
    // 122 rows: the 121 cells plus the padding row (HexGrid.ROWS = CELLS + 1)
    expect(GRID.rows).toBe(122);
  });

  it('has the work-group limits of docs/tornado.md', () => {
    const doc = readFileSync(`${REPO}docs/tornado.md`, 'utf8');
    expect(doc).toContain('largest work group of any kernel is 256 items');
    expect(doc).toContain('Local memory per work group is at most 12.5 KiB');
    expect(raw.workGroup).toEqual({ localMemKiB: 12.5, maxItems: 256 });
  });

  it('keeps a number only where the maker publishes one: null slots stay null', () => {
    expect(MACHINES.m3).toMatchObject({ l1: null, l2: null, regs: null, maxWarps: null, link: null, latency: null });
    expect(MACHINES.rtx).toMatchObject({ regs: null, maxWarps: null, latency: null, backend: null });
    expect(MACHINES.t4.latency).toEqual({ l1: 30, l2: 190, memLo: 300, memHi: 600 });
    expect(MACHINES.rtx.link?.rate.atMost).toBe(true);
    expect(MACHINES.rtx.link?.name).toBe('PCIe Gen5'); // the lane width is NOT VERIFIED: no "x16"
  });

  it('derives the CUDA cores from the units and the cores per unit', () => {
    expect(MACHINES.t4.units * (MACHINES.t4.cudaPerUnit ?? 0)).toBe(MACHINES.t4.cudaCores);
    expect(MACHINES.rtx.units * (MACHINES.rtx.cudaPerUnit ?? 0)).toBe(MACHINES.rtx.cudaCores);
  });
});

describe('kernel grid of one convolution of LARGE (64 channels)', () => {
  it('derives the counts of the spec table', () => {
    expect(gridCounts('t4', 1)).toMatchObject({ variant: 'NAIVE', rows: 122, threads: 7808, groups: null, warps: 244 });
    expect(gridCounts('m3', 256)).toMatchObject({ variant: 'SIMDGROUP', rows: 31232, threads: 249856, groups: 1952, warps: 7808 });
    expect(gridCounts('t4', 256)).toMatchObject({ variant: 'TILED', rows: 31232, threads: 124928, groups: 976, warps: 3904 });
    expect(gridCounts('rtx', 256)).toMatchObject({ variant: 'TILED', threads: 124928, groups: 976, warps: 3904 });
  });

  it('divides the warps by the units', () => {
    expect(gridCounts('m3', 1).perUnit).toBeCloseTo(6.1, 1);
    expect(gridCounts('t4', 1).perUnit).toBeCloseTo(6.1, 1);
    expect(gridCounts('rtx', 1).perUnit).toBeCloseTo(1.4, 1);
    expect(gridCounts('m3', 256).perUnit).toBeCloseTo(195.2, 1);
    expect(gridCounts('t4', 256).perUnit).toBeCloseTo(97.6, 1);
    expect(gridCounts('rtx', 256).perUnit).toBeCloseTo(23, 0);
  });

  it('keeps NAIVE while batch * channels <= 128, else SIMDGROUP on Metal and TILED elsewhere', () => {
    expect(variantFor('m3', 2)).toBe('NAIVE'); // docs/tornado.md: LARGE up to batch 2
    expect(variantFor('m3', 3)).toBe('SIMDGROUP');
    expect(variantFor('t4', 3)).toBe('TILED');
  });

  it('one work group is 128 threads: 4 warps of 32', () => {
    expect(GROUP_THREADS).toBe(128);
    expect(WARP_SIZE).toBe(32);
    expect(GROUP_WARPS).toBe(4);
    expect(gridCounts('m3', 256).threads).toBe((gridCounts('m3', 256).groups ?? 0) * GROUP_THREADS);
    expect(gridCounts('t4', 256).threads).toBe((gridCounts('t4', 256).groups ?? 0) * GROUP_THREADS);
  });

  it('rounds the page values as the spec lists them (about 7,800; 250,000; 125,000; 2,000; 1,000; 240; 3,900; 6; 1; 200; 100; 20)', () => {
    const shown = (v: number): number => roundedCount(v);
    expect([7808, 249856, 124928, 1952, 976, 244, 7808, 3904].map(shown)).toEqual([7800, 250000, 125000, 2000, 1000, 240, 7800, 3900]);
    expect([6.1, 1.43, 195.2, 97.6, 22.96].map(shown)).toEqual([6, 1, 200, 100, 20]);
  });
});

describe('steps', () => {
  // spec section 6: the active slots of each step; the parts of a machine follow from the slot table
  const expected: Record<HwId, string[][]> = {
    t4: [[], ['cpu', 'hostmem'], ['hostmem', 'link', 'devmem'], ['devmem', 'l2', 'sm'], ['l1', 'reg', 'warp'], ['simt'], ['thread'], ['group'], ['devmem', 'link', 'hostmem', 'cpu']],
    rtx: [[], ['cpu', 'hostmem'], ['hostmem', 'link', 'devmem'], ['devmem', 'l2', 'sm'], ['l1', 'reg', 'warp'], ['simt'], ['thread'], ['group'], ['devmem', 'link', 'hostmem', 'cpu']],
    m3: [[], ['cpu', 'umem'], ['umem', 'copy'], ['umem', 'cache', 'core'], ['tgmem', 'reg', 'simd'], ['simt'], ['thread'], ['group'], ['umem', 'copy', 'cpu']],
  };
  for (const hw of HW_IDS) {
    it(`${hw}: the active parts of every step equal the spec`, () => {
      for (let k = 0; k <= STEP_COUNT; k++) expect([...activeParts(hw, k)].sort(), `step ${k}`).toEqual([...(expected[hw][k] as string[])].sort());
    });
  }

  it('has eight steps after the idle state and levels 1,1,1,2,3,4,4,5,1', () => {
    expect(STEP_COUNT).toBe(8);
    expect(STEPS.map((s) => s.id)).toEqual(['idle', 'host', 'path', 'gpu', 'unit', 'warp', 'thread', 'grid', 'back']);
    expect(STEPS.map((s) => s.level)).toEqual([1, 1, 1, 2, 3, 4, 4, 5, 1]);
    expect(STEPS.find((s) => s.id === 'path')?.dir).toBe('in');
    expect(STEPS.find((s) => s.id === 'back')?.dir).toBe('out');
  });

  it('draws everything in ink at step 0 and marks active or dim parts afterwards', () => {
    expect(partState('t4', 0, 'cpu')).toBeUndefined();
    expect(partState('t4', 1, 'cpu')).toBe('active');
    expect(partState('t4', 1, 'gpu')).toBe('dim');
  });

  it('names one narration per step and a second one by kind for the steps that differ', () => {
    expect(narrationKey('t4', 1)).toBe('fig.gpu.narr.host.discrete');
    expect(narrationKey('rtx', 1)).toBe('fig.gpu.narr.host.discrete');
    expect(narrationKey('m3', 1)).toBe('fig.gpu.narr.host.unified');
    expect(narrationKey('m3', 5)).toBe('fig.gpu.narr.warp');
    expect(narrationKey('t4', 7)).toBe('fig.gpu.narr.grid');
    expect(narrationKey('m3', 0)).toBe('fig.gpu.narr.idle');
  });

  it('every narration key exists in the catalog, in both languages', () => {
    for (const hw of HW_IDS) {
      for (let k = 0; k <= STEP_COUNT; k++) {
        for (const lang of ['en', 'de'] as const) {
          const key = narrationKey(hw, k);
          expect(key in javaCatalog.catalogs[lang], `${lang} ${key}`).toBe(true);
        }
      }
    }
  });

  it('fills every placeholder of every narration', () => {
    for (const hw of HW_IDS) {
      const values = narrationValues(hw, fmt, atMost);
      for (let k = 0; k <= STEP_COUNT; k++) {
        const text = javaCatalog.lookup('en', narrationKey(hw, k) as never, values);
        expect(text, `${hw} step ${k}`).not.toMatch(/\{\w+\}/);
      }
    }
  });
});

describe('machines and parts', () => {
  it('each machine has the parts of its kind; the M3 Max has no link and no L2 box', () => {
    expect([...partsOf('t4')].sort()).toEqual(Object.values(PART_OF.discrete).sort().filter((x, i, a) => a.indexOf(x) === i));
    expect(partsOf('m3').has('link')).toBe(false);
    expect(partsOf('m3').has('copy')).toBe(true);
    expect(partsOf('m3').has('l2')).toBe(false);
    expect(partsOf('m3').has('cache')).toBe(true);
  });

  it('has a label, a what and a java text for every part in both languages', () => {
    for (const lang of ['en', 'de'] as const) {
      for (const hw of HW_IDS) {
        for (const id of partsOf(hw)) {
          for (const suffix of ['', '.what', '.java']) expect(`fig.gpu.part.${id}${suffix}` in javaCatalog.catalogs[lang], `${lang} ${id}${suffix}`).toBe(true);
        }
      }
    }
  });

  it('gives no fact for a slot the maker leaves open (the panel then says so)', () => {
    expect(partFacts('m3', 'cache', fmt, atMost)).toEqual([]);
    expect(partFacts('m3', 'reg', fmt, atMost)).toEqual([]);
    expect(partFacts('rtx', 'reg', fmt, atMost)).toEqual([]);
    expect(partFacts('rtx', 'l1', fmt, atMost).map((f) => f.key)).toEqual(['size']);
    expect(partFacts('t4', 'l1', fmt, atMost).map((f) => f.key)).toEqual(['size', 'latency', 'split']);
    expect(partFacts('t4', 'sm', fmt, atMost).map((f) => f.key)).toEqual(['count', 'percores', 'sched', 'maxwarps']);
  });

  it('every fact key exists in the catalog', () => {
    for (const hw of HW_IDS) {
      for (const id of partsOf(hw)) {
        for (const f of partFacts(hw, id, fmt, atMost)) expect(`fig.gpu.fact.${f.key}` in javaCatalog.catalogs.en, f.key).toBe(true);
      }
    }
  });

  it('writes a split as "64 + 32 KB / 32 + 64 KB" (shared memory + L1)', () => {
    expect(partFacts('t4', 'l1', fmt, atMost).find((f) => f.key === 'split')?.value).toBe('64 + 32 KB / 32 + 64 KB');
  });

  it('names the same thing at NVIDIA, Apple and TornadoVM', () => {
    expect(akaText('group')).toBe('thread block · threadgroup · work group');
    expect(akaText('cpu')).toBeNull();
    expect(Object.keys(AKA).sort()).toEqual(['core', 'devmem', 'group', 'l1', 'simd', 'sm', 'tgmem', 'thread', 'warp']);
  });
});

describe('distance ladder', () => {
  it('has four rows on the NVIDIA cards, with latencies only for the T4', () => {
    const t4 = ladderRows('t4');
    expect(t4.rows.map((r) => r.bar)).toEqual([22, 44, 70, 100]);
    expect(t4.rows.map((r) => r.cycles)).toEqual([null, { lo: 30, hi: null }, { lo: 190, hi: null }, { lo: 300, hi: 600 }]);
    expect(ladderRows('rtx').rows.every((r) => r.cycles === null)).toBe(true);
  });

  it('has two known rungs on the M3 Max and two hidden reserve rows, without numbers', () => {
    const m3 = ladderRows('m3');
    expect(m3.rows.map((r) => r.part)).toEqual(['cache', 'umem']);
    expect(m3.reserved).toBe(2);
    expect(m3.rows.every((r) => r.cycles === null)).toBe(true);
    expect(m3.rows.length + m3.reserved).toBe(ladderRows('t4').rows.length);
  });
});

describe('comparison table', () => {
  it('has the values of the spec: units, caches, memory, path, backend', () => {
    const text = (row: (typeof CMP_ROWS)[number], hw: HwId): string => {
      const c = cmpCell(row, hw, fmt, atMost);
      return c.kind === 'text' ? c.text : c.kind;
    };
    expect(HW_IDS.map((h) => text('l1', h))).toEqual(['dash', '96 KB', '128 KB']);
    expect(HW_IDS.map((h) => text('l2', h))).toEqual(['dash', '4 MB', '96 MB']);
    expect(HW_IDS.map((h) => text('mem', h))).toEqual(['64 GB', '16 GB', '32 GB']);
    expect(HW_IDS.map((h) => text('membw', h))).toEqual(['400 GB/s', '≈300 GB/s', '≈1,800 GB/s']);
    expect(HW_IDS.map((h) => text('path', h))).toEqual(['none', '16 GB/s', 'at most 64 GB/s']);
    expect(HW_IDS.map((h) => text('backend', h))).toEqual(['Metal', 'CUDA', 'dash']);
    expect(HW_IDS.map((h) => text('warp', h))).toEqual(['32', '32', '32']);
    expect(HW_IDS.map((h) => cmpCell('units', h, fmt, atMost).kind)).toEqual(['units', 'units', 'units']);
  });

  it('never shows a number for a slot whose value is null', () => {
    for (const row of CMP_ROWS) {
      for (const hw of HW_IDS) {
        const m = MACHINES[hw];
        const empty = (row === 'l1' && m.l1 === null) || (row === 'l2' && m.l2 === null) || (row === 'backend' && m.backend === null);
        if (empty) expect(cmpCell(row, hw, fmt, atMost).kind, `${row} ${hw}`).toBe('dash');
      }
    }
  });

  it('formats a quantity with the rounding mark and the bound', () => {
    expect(quantityText({ v: 1800, u: 'GB/s', approx: true }, fmt, atMost)).toBe('≈1,800 GB/s');
    expect(quantityText({ v: 64, u: 'GB/s', atMost: true }, fmt, atMost)).toBe('at most 64 GB/s');
  });
});
