// Pure geometry of the Plot primitive (design notes, section 6, "Plot"): the two view boxes, the choice between them, linear, log and
// category scales, and tick lists. No React in here, so that node tests cover it.

export interface PlotMargin {
  left: number;
  right: number;
  top: number;
  bottom: number;
}

export interface PlotBox {
  readonly name: 'wide' | 'compact';
  readonly width: number;
  readonly height: number;
  readonly margin: PlotMargin;
}

export const PLOT_WIDE: PlotBox = { name: 'wide', width: 640, height: 280, margin: { left: 52, right: 16, top: 28, bottom: 44 } };
export const PLOT_COMPACT: PlotBox = { name: 'compact', width: 260, height: 180, margin: { left: 40, right: 10, top: 28, bottom: 40 } };

/** Measured width in CSS px from which the wide view box applies (figure breakpoint 40rem = 640 px). */
export const PLOT_BREAKPOINT_PX = 640;

/** A measured width of 0 (not laid out yet, or no layout engine) gives the wide box, as a server render would. */
export function plotBoxFor(measuredWidth: number, height?: number): PlotBox {
  const base = measuredWidth > 0 && measuredWidth < PLOT_BREAKPOINT_PX ? PLOT_COMPACT : PLOT_WIDE;
  return height === undefined ? base : { ...base, height };
}

export type Scale = (value: number) => number;

/** Maps `domain` to `range`; a value outside the domain extrapolates. */
export function linearScale(domain: readonly [number, number], range: readonly [number, number]): Scale {
  const [d0, d1] = domain;
  const [r0, r1] = range;
  if (d0 === d1) throw new RangeError('empty domain');
  return (v) => r0 + ((v - d0) / (d1 - d0)) * (r1 - r0);
}

export function logScale(domain: readonly [number, number], range: readonly [number, number]): Scale {
  const [d0, d1] = domain;
  if (!(d0 > 0 && d1 > 0)) throw new RangeError('a log axis needs a positive domain');
  const inner = linearScale([Math.log10(d0), Math.log10(d1)], range);
  return (v) => {
    if (!(v > 0)) throw new RangeError(`not on a log axis: ${v}`);
    return inner(Math.log10(v));
  };
}

/** Centre of category `index` of `count` equal bands. */
export function categoryScale(count: number, range: readonly [number, number]): (index: number) => number {
  if (count < 1) throw new RangeError('no categories');
  const [r0, r1] = range;
  const band = (r1 - r0) / count;
  return (i) => r0 + band * (i + 0.5);
}

/** Round numbers inside [min, max] at a step of 1, 2 or 5 times a power of ten; about `target` of them. */
export function linearTicks(min: number, max: number, target = 5): number[] {
  if (!(max > min)) return [min];
  const raw = (max - min) / Math.max(1, target);
  const pow = 10 ** Math.floor(Math.log10(raw));
  const f = raw / pow;
  const step = (f <= 1 ? 1 : f <= 2 ? 2 : f <= 5 ? 5 : 10) * pow;
  const out: number[] = [];
  const first = Math.ceil(min / step - 1e-9);
  for (let i = first; i * step <= max + step * 1e-9; i++) out.push(Number((i * step).toPrecision(12)));
  return out;
}

export interface LogTicks {
  /** Ticks with a label: the given values, or every power of ten in the domain. */
  major: number[];
  /** 2, 3, 4 and 5 times each power of ten inside the domain, without the major values. */
  minor: number[];
}

export function logTicks(min: number, max: number, labelled?: readonly number[]): LogTicks {
  if (!(min > 0 && max > min)) throw new RangeError('a log axis needs 0 < min < max');
  const inDomain = (v: number): boolean => v >= min * (1 - 1e-9) && v <= max * (1 + 1e-9);
  const lo = Math.floor(Math.log10(min));
  const hi = Math.ceil(Math.log10(max));
  const major = labelled ? labelled.filter(inDomain) : [];
  if (!labelled) for (let p = lo; p <= hi; p++) if (inDomain(10 ** p)) major.push(10 ** p);
  const minor: number[] = [];
  for (let p = lo; p <= hi; p++) {
    for (const m of [2, 3, 4, 5]) {
      const v = m * 10 ** p;
      if (inDomain(v) && !major.includes(v)) minor.push(v);
    }
  }
  return { major, minor };
}

/** 1500 -> "1.5k", 20000 -> "20k", 1000000 -> "1M", 0.05 -> "0.05". Digits only; the unit belongs in the axis label. */
export function formatCompact(v: number): string {
  const a = Math.abs(v);
  const trim = (x: number): string => String(Number(x.toPrecision(3)));
  if (a >= 1e6) return `${trim(v / 1e6)}M`;
  if (a >= 1e3) return `${trim(v / 1e3)}k`;
  return trim(v);
}
