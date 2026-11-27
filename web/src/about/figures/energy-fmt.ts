// Fig. 4.4: how the figure writes numbers and row names. Digits come from energy-model.ts (formatHours, formatKwh); the words around them come from the
// catalog (`fig.energy.*`); the locale decides the separators. No number is typed here: everything derives from the rows of data/energy.json.
import type { FigText } from './shared';
import { daysOf, formatHours, formatKwh, powerW, rowCode } from './energy-model';
import type { EnergyData, EnergyRow, NetName } from './energy-model';

export interface EnergyFmt {
  code: (row: EnergyRow) => string;
  /** "SMALL generation 9", "LARGE training steps, CPU, 14 threads". */
  name: (row: EnergyRow) => string;
  trainer: (row: EnergyRow) => string;
  hours: (row: EnergyRow) => string;
  /** "7.3 days" from 24 h on, else an empty string. */
  days: (row: EnergyRow) => string;
  kwh: (row: EnergyRow) => string;
  /** "(0.9–1.5)" for an estimate with a range, else an empty string. */
  range: (row: EnergyRow) => string;
  aboveIdle: (row: EnergyRow) => string;
  power: (row: EnergyRow) => string;
  /** "+131 ± 24 Elo" (a head-to-head match). */
  elo: (elo: number, ci: number) => string;
  /** "1,840 ± 130" (a rating on the scale, no sign; the display values of the rating file). */
  rating: (elo: number, hw: number) => string;
  size: (net: NetName, data: EnergyData) => string;
  /** A tick label: at most two decimals, locale separators. */
  tick: (v: number) => string;
  /** The word "estimate" for a screen reader, or an empty string for a measured row. */
  estimateSr: (row: EnergyRow) => string;
}

/** The decimals a transcribed value was written with (0.119 has three, 0.86 two). */
const decimalsOf = (v: number): number => (String(v).split('.')[1] ?? '').length;

export function makeEnergyFmt(fig: FigText): EnergyFmt {
  const { t, fmt, language } = fig;
  const approx = (row: EnergyRow, text: string): string => (row.status === 'estimate' ? t('fig.energy.approx', { v: text }) : text);
  const tickFormat = new Intl.NumberFormat(language, { maximumFractionDigits: 2 });
  const kwhDigits = (row: EnergyRow): number => formatKwh(row);
  const trainer = (row: EnergyRow): string => t(`fig.energy.trainer.${row.trainer}`);
  return {
    code: rowCode,
    name: (row) => {
      const net = t(`fig.energy.net.${row.net}`);
      const part = row.group === 'steps' ? t('fig.energy.row.steps', { trainer: trainer(row) }) : row.generation ? t('fig.energy.row.gen', { g: row.generation }) : t('fig.energy.row.gen0');
      return `${net} ${part}`;
    },
    trainer,
    hours: (row) => approx(row, t('fig.energy.unit.h', { v: fmt.fixed(row.hours, formatHours(row.hours)) })),
    days: (row) => {
      const days = daysOf(row.hours);
      return days === undefined ? '' : t('fig.energy.unit.days', { v: fmt.fixed(days, 1) });
    },
    kwh: (row) => approx(row, t('fig.energy.unit.kwh', { v: fmt.fixed(row.kwh, kwhDigits(row)) })),
    range: (row) => (row.kwhRange ? t('fig.energy.range', { lo: fmt.fixed(row.kwhRange[0], kwhDigits(row)), hi: fmt.fixed(row.kwhRange[1], kwhDigits(row)) }) : ''),
    aboveIdle: (row) => (row.aboveIdleKwh === undefined ? '' : t('fig.energy.unit.kwh', { v: fmt.fixed(row.aboveIdleKwh, decimalsOf(row.aboveIdleKwh)) })),
    power: (row) => t('fig.energy.unit.w', { v: fmt.fixed(powerW(row), 0) }),
    elo: (elo, ci) => t('fig.energy.unit.elo', { v: fmt.signed(elo), ci: fmt.fixed(ci, 0) }),
    rating: (elo, hw) => t('fig.energy.unit.rating', { v: fmt.fixed(elo, 0), ci: fmt.fixed(hw, 0) }),
    size: (net, data) => {
      const n = data.nets[net];
      return t('fig.energy.size', { weights: fmt.fixed(Math.round(n.weights / 1000) * 1000, 0), kb: fmt.fixed(Math.round(n.packedBytes / 1000), 0) });
    },
    tick: (v) => tickFormat.format(v),
    estimateSr: (row) => (row.status === 'estimate' ? t('fig.energy.estimate.sr') : ''),
  };
}
