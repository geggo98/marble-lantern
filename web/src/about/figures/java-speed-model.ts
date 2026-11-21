// Model of figure J1 (the speed chart of topic 6, "java"): the rows of data/java-speed.json, the filter of the switch "Java only / Java and C++",
// the numbers as the page shows them (two significant digits) and the geometry of the groups. No React in here.
import raw from '../data/java-speed.json';

export type SpeedGroup = 'mac-run' | 'other';
export type SpeedKind = 'java' | 'cpp';
export type SpeedFilter = 'java' | 'all';

export interface SpeedRow {
  id: string;
  group: SpeedGroup;
  kind: SpeedKind;
  value: number;
  lo?: number;
  hi?: number;
  /** The text that the cited line of `source.doc` must contain. */
  needle: string;
  source: { doc: string; line: number };
}

export const SPEED_ROWS: readonly SpeedRow[] = raw.rows as SpeedRow[];

/** The y axis: log, four decades. */
export const SPEED_DOMAIN: readonly [number, number] = [100, 200000];
export const SPEED_TICKS: readonly number[] = [100, 1000, 10000, 100000];

/** The rows that the switch shows, in the order of the data (the Mac run first). */
export const visibleRows = (filter: SpeedFilter, rows: readonly SpeedRow[] = SPEED_ROWS): SpeedRow[] =>
  rows.filter((r) => filter === 'all' || r.kind === 'java');

/** A value as the page prints it: two significant digits (the data file keeps the exact source value). 111409 -> 110000, 201 -> 200, 85582.5 -> 86000, 3100 -> 3100. */
export function roundedValue(value: number): number {
  if (value < 1000) return value >= 100 ? Math.round(value / 10) * 10 : Math.round(value);
  const magnitude = 10 ** (Math.floor(Math.log10(value)) - 1);
  return Math.round(value / magnitude) * magnitude;
}

/** Position of the dividing line between the Mac run and the other rows on the category axis, or undefined when one group is missing. */
export function groupDivider(rows: readonly SpeedRow[]): number | undefined {
  const mac = rows.filter((r) => r.group === 'mac-run').length;
  return mac > 0 && mac < rows.length ? mac - 0.5 : undefined;
}

/** Text of a row's value: a range as "126–163", else the rounded value through `format`. */
export const valueText = (row: SpeedRow, format: (v: number) => string): string =>
  row.lo !== undefined && row.hi !== undefined ? `${format(row.lo)}–${format(row.hi)}` : format(roundedValue(row.value));
