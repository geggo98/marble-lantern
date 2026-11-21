// Plot primitives (design notes, section 6, "Plot"): an inline SVG with linear, log or category axes, ticks, and marks: line, bars,
// dot and whisker (with leaders on a log axis), reference line, band, cursor. Two view boxes; the choice follows the measured width of the
// plot (threshold 640 px). Labels come in as props; nothing in here is user text.
import { createContext, useContext, useId, useLayoutEffect, useRef, useState } from 'react';
import type { ReactNode, RefObject } from 'react';
import { PLOT_BREAKPOINT_PX, PLOT_COMPACT, PLOT_WIDE, categoryScale, formatCompact, linearScale, linearTicks, logScale, logTicks, plotBoxFor } from './plot-scale';
import type { PlotBox, PlotMargin, Scale } from './plot-scale';

export interface NumericAxis {
  scale: 'linear' | 'log';
  domain: readonly [number, number];
  /** Values that get a tick and a label. Default: rounded values on a linear axis, powers of ten on a log axis. */
  ticks?: readonly number[];
  format?: (value: number) => string;
  /** Written horizontally; on the y axis above the top left corner of the frame (design notes, section 2). */
  label?: string;
  /** Linear: none. Log: minor ticks at 2 to 5 times each power of ten (default true). */
  minor?: boolean;
}

export interface CategoryAxis {
  scale: 'category';
  categories: readonly string[];
  label?: string;
}

export type XAxis = NumericAxis | CategoryAxis;

export interface PlotContextValue {
  box: PlotBox;
  /** Inner area of the frame in view box units. */
  area: { x0: number; x1: number; y0: number; y1: number };
  /** x position of a value, or for a category axis of the category index. */
  sx: (value: number) => number;
  sy: Scale;
  xLog: boolean;
  yLog: boolean;
}

const PlotContext = createContext<PlotContextValue | null>(null);

export function usePlot(): PlotContextValue {
  const ctx = useContext(PlotContext);
  if (!ctx) throw new Error('a plot mark must be a child of <Plot>');
  return ctx;
}

/** Width of an element (or, with `closest`, of its nearest ancestor that matches the selector) in CSS px, kept up to date. 0 until it has a layout. */
export function useMeasuredWidth(ref: RefObject<HTMLElement | null>, closest?: string): number {
  const [width, setWidth] = useState(0);
  useLayoutEffect(() => {
    const el = closest ? (ref.current?.closest<HTMLElement>(closest) ?? ref.current) : ref.current;
    if (!el) return undefined;
    const read = (): void => setWidth(el.getBoundingClientRect().width);
    read();
    if (typeof ResizeObserver !== 'function') return undefined;
    const observer = new ResizeObserver(read);
    observer.observe(el);
    return () => observer.disconnect();
  }, [ref, closest]);
  return width;
}

/** Narrowest view box of a fitted plot (units): below it the margins would leave no frame. */
const MIN_FIT_WIDTH = 200;
/** Width of one character of a tick label (12 px monospace) and the least space between two labels, in units. */
const TICK_CHAR_WIDTH = 7.2;
const TICK_LABEL_GAP = 6;

export interface PlotProps {
  /** Accessible name and description: role="img" with <title> and <desc>. */
  title: string;
  desc: string;
  x: XAxis;
  y: NumericAxis;
  /**
   * `auto` (default) measures the width of the plot. `fit` is for a plot in a slot narrower than the figure (one of two columns): the
   * compact margins and type, with a view box as wide as the slot, so that one unit stays about one CSS pixel (review M4). `compact` and
   * `wide` are the fixed boxes of the notes (260 and 640 units).
   */
  layout?: 'auto' | 'wide' | 'compact' | 'fit';
  /** Margins that differ from the box (units), for example more room above the frame for a value label. */
  margin?: Partial<PlotMargin>;
  /** A figure brief may set another height than 280 (wide) or 180 (compact). */
  height?: number;
  children?: ReactNode;
}

function numericTicks(axis: NumericAxis): { major: number[]; minor: number[] } {
  const [min, max] = axis.domain;
  if (axis.scale === 'log') {
    const t = logTicks(min, max, axis.ticks);
    return { major: t.major, minor: axis.minor === false ? [] : t.minor };
  }
  return { major: axis.ticks ? [...axis.ticks] : linearTicks(min, max), minor: [] };
}

export function Plot({ title, desc, x, y, layout = 'auto', height, margin: marginOverride, children }: PlotProps): React.JSX.Element {
  const holder = useRef<HTMLDivElement>(null);
  const measured = useMeasuredWidth(holder);
  const sized = (base: PlotBox): PlotBox => ({
    ...base,
    ...(height ? { height } : {}),
    ...(marginOverride ? { margin: { ...base.margin, ...marginOverride } } : {}),
  });
  const base: PlotBox = layout === 'wide' ? PLOT_WIDE : layout === 'compact' ? PLOT_COMPACT : plotBoxFor(measured);
  // a slot that is narrower than the wide box but wider than the compact one: draw in the width of the slot
  const fitted: PlotBox = (layout === 'fit' || layout === 'auto') && measured > 0 && measured < PLOT_BREAKPOINT_PX ? { ...PLOT_COMPACT, width: Math.max(MIN_FIT_WIDTH, Math.round(measured)) } : base;
  const box = sized(layout === 'fit' && !(measured > 0) ? PLOT_COMPACT : fitted);
  const id = useId();
  const { margin } = box;
  const area = { x0: margin.left, x1: box.width - margin.right, y0: margin.top, y1: box.height - margin.bottom };

  const sy = y.scale === 'log' ? logScale(y.domain, [area.y1, area.y0]) : linearScale(y.domain, [area.y1, area.y0]);
  const sx: (v: number) => number =
    x.scale === 'category'
      ? categoryScale(x.categories.length, [area.x0, area.x1])
      : x.scale === 'log'
        ? logScale(x.domain, [area.x0, area.x1])
        : linearScale(x.domain, [area.x0, area.x1]);

  const yTicks = numericTicks(y);
  const yFormat = y.format ?? formatCompact;
  const xTicks = x.scale === 'category' ? null : numericTicks(x);
  const xFormat = x.scale === 'category' ? null : (x.format ?? formatCompact);

  // an x tick keeps its mark but loses its label when the label would touch the one before it (review m14)
  const labelled = new Set<number>();
  if (xTicks && xFormat) {
    let lastPx = -Infinity;
    let lastLen = 0;
    for (const v of [...xTicks.major].sort((a, b) => sx(a) - sx(b))) {
      const len = xFormat(v).length;
      if (sx(v) - lastPx >= ((lastLen + len) / 2) * TICK_CHAR_WIDTH + TICK_LABEL_GAP) {
        labelled.add(v);
        lastPx = sx(v);
        lastLen = len;
      }
    }
  }

  const value: PlotContextValue = { box, area, sx, sy, xLog: x.scale === 'log', yLog: y.scale === 'log' };
  return (
    <div ref={holder} className="ml-fig-plot-holder" data-layout={box.name}>
      <svg
        className="ml-fig-plot"
        viewBox={`0 0 ${box.width} ${box.height}`}
        role="img"
        aria-labelledby={`${id}-t ${id}-d`}
        data-layout={box.name}
      >
        <title id={`${id}-t`}>{title}</title>
        <desc id={`${id}-d`}>{desc}</desc>
        <PlotContext.Provider value={value}>
          {/* back to front: band and reference lines (children below), frame, ticks, labels, marks */}
          <rect className="ml-fig-plot__frame" x={area.x0} y={area.y0} width={area.x1 - area.x0} height={area.y1 - area.y0} />
          {yTicks.minor.map((v) => (
            <line key={`ym${v}`} className="ml-fig-plot__tick ml-fig-plot__tick--minor" x1={area.x0} x2={area.x0 - 3} y1={sy(v)} y2={sy(v)} />
          ))}
          {yTicks.major.map((v) => (
            <g key={`y${v}`}>
              <line className="ml-fig-plot__tick" x1={area.x0} x2={area.x0 - 6} y1={sy(v)} y2={sy(v)} />
              <text x={area.x0 - 9} y={sy(v)} textAnchor="end" dominantBaseline="central">
                {yFormat(v)}
              </text>
            </g>
          ))}
          {xTicks &&
            xFormat &&
            xTicks.minor.map((v) => (
              <line key={`xm${v}`} className="ml-fig-plot__tick ml-fig-plot__tick--minor" x1={sx(v)} x2={sx(v)} y1={area.y1} y2={area.y1 + 3} />
            ))}
          {xTicks &&
            xFormat &&
            xTicks.major.map((v) => (
              <g key={`x${v}`}>
                <line className="ml-fig-plot__tick" x1={sx(v)} x2={sx(v)} y1={area.y1} y2={area.y1 + 6} />
                {labelled.has(v) && (
                  <text x={sx(v)} y={area.y1 + 19} textAnchor="middle">
                    {xFormat(v)}
                  </text>
                )}
              </g>
            ))}
          {x.scale === 'category' &&
            x.categories.map((name, i) => (
              <g key={`c${i}`}>
                <line className="ml-fig-plot__tick" x1={sx(i)} x2={sx(i)} y1={area.y1} y2={area.y1 + 6} />
                <text className="ml-fig-plot__catlabel" x={sx(i)} y={area.y1 + 19} textAnchor="middle">
                  {name}
                </text>
              </g>
            ))}
          {x.label && (
            <text className="ml-fig-plot__axislabel" x={(area.x0 + area.x1) / 2} y={box.height - 6} textAnchor="middle">
              {x.label}
            </text>
          )}
          {y.label && (
            <text className="ml-fig-plot__axislabel" x={area.x0} y={area.y0 - 10} textAnchor="start">
              {y.label}
            </text>
          )}
          {children}
        </PlotContext.Provider>
      </svg>
    </div>
  );
}

// ---- marks ----

export type Series = 1 | 2 | 3;

export interface Point {
  x: number;
  y: number;
}

/** A polyline through the points, in the style of series 1 (solid), 2 (dash 7 4) or 3 (dash 2 4). */
export function PlotLine({ points, series = 1 }: { points: readonly Point[]; series?: Series }): React.JSX.Element {
  const { sx, sy } = usePlot();
  const d = points.map((p, i) => `${i === 0 ? 'M' : 'L'}${sx(p.x).toFixed(2)} ${sy(p.y).toFixed(2)}`).join('');
  return <path className={`ml-fig-plot__line ml-fig-plot__line--${series}`} d={d} />;
}

export interface Bar {
  x: number;
  y: number;
}

/** Bars from `baseline` (default the lower end of a linear y axis). Refused on a log axis: a log axis has no zero (design notes, section 6). */
export function PlotBars({ bars, baseline, width = 14 }: { bars: readonly Bar[]; baseline?: number; width?: number }): React.JSX.Element {
  const { sx, sy, yLog, area } = usePlot();
  if (yLog) throw new Error('bars need a linear axis that starts at zero; use <PlotDots leaders> on a log axis');
  const y0 = baseline === undefined ? area.y1 : sy(baseline);
  return (
    <g>
      {bars.map((b, i) => {
        const y = sy(b.y);
        return <rect key={i} className="ml-fig-plot__bar" x={sx(b.x) - width / 2} y={Math.min(y, y0)} width={width} height={Math.abs(y0 - y)} />;
      })}
    </g>
  );
}

export interface Dot {
  x: number;
  y: number;
  /** Interval: whisker from `lo` to `hi` with caps of 10 units. */
  lo?: number;
  hi?: number;
  /** Text next to the dot (ink, weight 600; 700 when `best`). */
  valueLabel?: string;
  best?: boolean;
  /**
   * Where the label stands: `right` of the dot (default), `left` of it, or `above` the top of its whisker (or above the dot), centred.
   * The last two are for a dot whose right neighbour is too close (review m13).
   */
  labelSide?: 'left' | 'right' | 'above';
}

/**
 * Dots, optionally with whiskers. Series 1 is a filled circle, 2 a hollow square, 3 a filled diamond. `leaders` draws a thin line from the
 * lower edge of the frame to each dot (for values on a log axis, where a bar would lie about its length).
 */
export function PlotDots({ dots, series = 1, leaders = false }: { dots: readonly Dot[]; series?: Series; leaders?: boolean }): React.JSX.Element {
  const { sx, sy, area } = usePlot();
  return (
    <g>
      {dots.map((d, i) => {
        const cx = sx(d.x);
        const cy = sy(d.y);
        return (
          <g key={i}>
            {leaders && <line className="ml-fig-plot__leader" x1={cx} x2={cx} y1={area.y1} y2={cy} />}
            {d.lo !== undefined && d.hi !== undefined && (
              <path className="ml-fig-plot__whisker" d={`M${cx} ${sy(d.lo)}V${sy(d.hi)}M${cx - 5} ${sy(d.lo)}H${cx + 5}M${cx - 5} ${sy(d.hi)}H${cx + 5}`} />
            )}
            {series === 1 && <circle className="ml-fig-plot__pt--1" cx={cx} cy={cy} r={4.5} />}
            {series === 2 && <rect className="ml-fig-plot__pt--2" x={cx - 4} y={cy - 4} width={8} height={8} />}
            {series === 3 && <path className="ml-fig-plot__pt--3" d={`M${cx} ${cy - 6}L${cx + 6} ${cy}L${cx} ${cy + 6}L${cx - 6} ${cy}Z`} />}
            {d.valueLabel !== undefined && (
              <text className={d.best ? 'ml-fig-plot__best' : 'ml-fig-plot__value'} x={d.labelSide === 'left' ? cx - 9 : d.labelSide === 'above' ? cx : cx + 9}
                y={d.labelSide === 'above' ? Math.min(cy, d.hi !== undefined ? sy(d.hi) : cy) - 9 : cy - 8}
                textAnchor={d.labelSide === 'left' ? 'end' : d.labelSide === 'above' ? 'middle' : 'start'}
              >
                {d.valueLabel}
              </text>
            )}
          </g>
        );
      })}
    </g>
  );
}

/** A reference line (zero, a threshold such as 0.5): dashed 8 3 2 3, with an optional label at its end. */
export function PlotRef({ y, x, label }: { y?: number; x?: number; label?: string }): React.JSX.Element {
  const { sx, sy, area } = usePlot();
  if ((y === undefined) === (x === undefined)) throw new Error('<PlotRef> takes y or x');
  if (y !== undefined) {
    const py = sy(y);
    return (
      <g>
        <line className="ml-fig-plot__ref" x1={area.x0} x2={area.x1} y1={py} y2={py} />
        {label && (
          <text x={area.x1 - 4} y={py - 5} textAnchor="end">
            {label}
          </text>
        )}
      </g>
    );
  }
  const px = sx(x as number);
  return (
    <g>
      <line className="ml-fig-plot__ref" x1={px} x2={px} y1={area.y0} y2={area.y1} />
      {label && (
        <text x={px + 4} y={area.y0 + 12}>
          {label}
        </text>
      )}
    </g>
  );
}

/** A band between two curves, edges in the accent. `lo` and `hi` have one y per x. */
export function PlotBand({ xs, lo, hi }: { xs: readonly number[]; lo: readonly number[]; hi: readonly number[] }): React.JSX.Element {
  const { sx, sy } = usePlot();
  const top = xs.map((x, i) => `${sx(x).toFixed(2)} ${sy(hi[i] ?? 0).toFixed(2)}`);
  const bottom = xs.map((x, i) => `${sx(x).toFixed(2)} ${sy(lo[i] ?? 0).toFixed(2)}`);
  return (
    <g>
      <path className="ml-fig-plot__band" d={`M${top.join('L')}L${[...bottom].reverse().join('L')}Z`} />
      <path className="ml-fig-plot__bandedge" d={`M${top.join('L')}`} />
      <path className="ml-fig-plot__bandedge" d={`M${bottom.join('L')}`} />
    </g>
  );
}

/** The value chosen by a slider: a dashed vertical line and, when `y` is given, a ring of radius 8 around the point. */
export function PlotCursor({ x, y }: { x: number; y?: number }): React.JSX.Element {
  const { sx, sy, area } = usePlot();
  const px = sx(x);
  return (
    <g>
      <line className="ml-fig-plot__cursor" x1={px} x2={px} y1={area.y0} y2={area.y1} />
      {y !== undefined && <circle className="ml-fig-plot__now" cx={px} cy={sy(y)} r={8} />}
    </g>
  );
}
