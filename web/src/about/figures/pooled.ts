// V12: the geometry and the steps of the schematic of many games and one device thread. Pure. The picture is schematic; the numbers of
// drawn games are a design choice and the caption says so (pooled.json holds the measured throughputs).
import pooled from '../data/pooled.json';
import { parseDocNumber } from './numbers';

export type Cohort = 'A' | 'B';
export type EdgeId = 'aIn' | 'bIn' | 'aOut' | 'bOut' | 'backA' | 'backB';

export interface Rect {
  x: number;
  y: number;
  w: number;
  h: number;
}

export interface Layout {
  w: number;
  h: number;
  tokens: { x: number; y: number; cohort: Cohort }[];
  labelA: { x: number; y: number };
  labelB: { x: number; y: number };
  evaluator: Rect;
  trayA: Rect;
  trayB: Rect;
  device: Rect;
  edges: Record<EdgeId, string>;
}

export function layoutFor(compact: boolean): Layout {
  if (compact) {
    const tokens: Layout['tokens'] = [];
    for (let row = 0; row < 2; row++) {
      for (let col = 0; col < 3; col++) tokens.push({ x: 34 + col * 38, y: 46 + row * 46, cohort: 'A' });
      for (let col = 0; col < 3; col++) tokens.push({ x: 154 + col * 38, y: 46 + row * 46, cohort: 'B' });
    }
    return {
      w: 264,
      h: 420,
      tokens,
      labelA: { x: 14, y: 16 },
      labelB: { x: 140, y: 16 },
      evaluator: { x: 20, y: 150, w: 224, h: 130 },
      trayA: { x: 30, y: 196, w: 94, h: 56 },
      trayB: { x: 140, y: 196, w: 94, h: 56 },
      device: { x: 52, y: 340, w: 160, h: 64 },
      edges: {
        aIn: 'M72 120V196',
        bIn: 'M192 120V196',
        aOut: 'M77 252V300H110V340',
        bOut: 'M187 252V300H154V340',
        backA: 'M52 372H8V92H19',
        backB: 'M212 372H256V92H245',
      },
    };
  }
  const tokens: Layout['tokens'] = [];
  for (let row = 0; row < 4; row++) for (let col = 0; col < 6; col++) tokens.push({ x: 34 + col * 46, y: 52 + row * 46 + (row >= 2 ? 40 : 0), cohort: row < 2 ? 'A' : 'B' });
  return {
    w: 720,
    h: 320,
    tokens,
    labelA: { x: 12, y: 20 },
    labelB: { x: 12, y: 160 },
    evaluator: { x: 330, y: 12, w: 190, h: 296 },
    trayA: { x: 346, y: 70, w: 158, h: 70 },
    trayB: { x: 346, y: 190, w: 158, h: 70 },
    device: { x: 576, y: 124, w: 132, h: 72 },
    edges: {
      aIn: 'M286 88H346',
      bIn: 'M286 236H346',
      aOut: 'M504 105H540V150H576',
      bOut: 'M504 225H540V170H576',
      backA: 'M642 124V6H150V30',
      backB: 'M642 196V314H150V252',
    },
  };
}

export interface StepState {
  tokens: Cohort[];
  trays: Cohort[];
  edges: EdgeId[];
  device: boolean;
}

/** The six steps of one ping-pong cycle (the keys `fig.pooled.step.1` to `.6`). */
export const STEPS: readonly StepState[] = [
  { tokens: ['A'], trays: ['A'], edges: ['aIn'], device: false },
  { tokens: [], trays: ['A'], edges: ['aOut'], device: false },
  { tokens: ['B'], trays: ['A'], edges: ['bIn'], device: true },
  { tokens: ['A'], trays: [], edges: ['backA'], device: false },
  { tokens: ['A'], trays: ['B'], edges: ['bOut', 'aIn'], device: false },
  { tokens: ['A'], trays: ['B'], edges: ['aIn'], device: true },
];

/** The measured games per second of the SMALL network at 200 simulations (CPU with 8 threads, GPU with 2 cohorts as the mean of its runs). */
export function throughput(): { cpu: number; gpu: number } {
  const rows = pooled.selfPlay as { net: string; evaluator: string; gamesPerSecond: { value?: string; values?: string[] } }[];
  const cpu = rows.find((r) => r.net === 'SMALL' && r.evaluator === 'CPU, 8 threads');
  const gpu = rows.find((r) => r.net === 'SMALL' && r.evaluator === 'GPU, 2 cohorts');
  const runs = (gpu?.gamesPerSecond.values ?? []).map(parseDocNumber);
  return { cpu: parseDocNumber(cpu?.gamesPerSecond.value ?? 'NaN'), gpu: runs.reduce((a, b) => a + b, 0) / runs.length };
}
