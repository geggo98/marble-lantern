// IcicleTree (design notes, section 6 "IcicleTree"): a search tree as stacked bars. The root is one bar across the top, below it one column
// per root move (a move bar with the glyph for a jump or a step) and in each column one small cell per reply. The cells are too small to be
// targets, so the tree is role="img"; a stepper and a select reach every state.
import { useId, useRef } from 'react';
import { useMeasuredWidth } from './Plot';
import { PLOT_BREAKPOINT_PX } from './plot-scale';

export type CellState = 'visited' | 'active' | 'path' | 'skipped' | 'todo';

export interface IcicleColumn {
  /** `»` for a jump, `›` for a step (`kit.legend.jump`, `kit.legend.step`); drawn in the move bar. */
  glyph?: string;
  /** State of the move bar: the active column of the stepper, or none. */
  state?: 'active';
  cells: readonly CellState[];
  /** The search stopped after this many cells: a line across the column below them (alpha-beta cut). */
  cutAfter?: number;
}

export interface IcicleGeometry {
  width: number;
  height: number;
  colW: number;
  gap: number;
  rootH: number;
  barH: number;
  cellH: number;
  cellGap: number;
  /** y of the move bars and of the first cell. */
  barY: number;
  cellY: number;
}

/** View boxes of the notes: 640 x 218 and 260 x 204 for 14 columns of 14 replies; other counts keep the unit sizes. */
export function icicleGeometry(columns: number, maxCells: number, compact: boolean): IcicleGeometry {
  const width = compact ? 260 : 640;
  const gap = compact ? 2 : 4;
  const cellH = compact ? 8 : 9;
  const cellGap = 2;
  const rootH = 24;
  const barH = 24;
  const colW = (width - gap * Math.max(0, columns - 1)) / Math.max(1, columns);
  const barY = rootH + 4;
  const cellY = barY + barH + 4;
  const height = cellY + Math.max(0, maxCells) * (cellH + cellGap) - (maxCells > 0 ? cellGap : 0) + 10;
  return { width, height, colW, gap, rootH, barH, cellH, cellGap, barY, cellY };
}

export interface IcicleTreeProps {
  title: string;
  desc: string;
  columns: readonly IcicleColumn[];
  /** Text in the root bar (for example the alpha and beta bounds); optional. */
  rootText?: string;
  rootState?: 'active';
  layout?: 'auto' | 'wide' | 'compact';
}

export function IcicleTree({ title, desc, columns, rootText, rootState, layout = 'auto' }: IcicleTreeProps): React.JSX.Element {
  const holder = useRef<HTMLDivElement>(null);
  const measured = useMeasuredWidth(holder);
  const compact = layout === 'compact' || (layout === 'auto' && measured > 0 && measured < PLOT_BREAKPOINT_PX);
  const maxCells = columns.reduce((m, c) => Math.max(m, c.cells.length), 0);
  const g = icicleGeometry(columns.length, maxCells, compact);
  const id = useId();
  return (
    <div ref={holder} data-layout={compact ? 'compact' : 'wide'}>
      <svg className="ml-fig-ice" viewBox={`0 0 ${g.width} ${g.height}`} role="img" aria-labelledby={`${id}-t ${id}-d`}>
        <title id={`${id}-t`}>{title}</title>
        <desc id={`${id}-d`}>{desc}</desc>
        <rect className="ml-fig-ice__root" data-state={rootState} x={0} y={0} width={g.width} height={g.rootH} />
        {rootText && (
          <text x={g.width / 2} y={g.rootH / 2}>
            {rootText}
          </text>
        )}
        {columns.map((col, i) => {
          const x = i * (g.colW + g.gap);
          return (
            <g key={i} data-column={i}>
              <rect className="ml-fig-ice__move" data-state={col.state} x={x} y={g.barY} width={g.colW} height={g.barH} />
              {col.glyph && (
                <text x={x + g.colW / 2} y={g.barY + g.barH / 2}>
                  {col.glyph}
                </text>
              )}
              {col.cells.map((state, k) => (
                <rect
                  key={k}
                  className={`ml-fig-ice__cell ml-fig-ice__cell--${state}`}
                  x={x}
                  y={g.cellY + k * (g.cellH + g.cellGap)}
                  width={g.colW}
                  height={g.cellH}
                />
              ))}
              {col.cutAfter !== undefined && (
                <line className="ml-fig-ice__cut" x1={x} x2={x + g.colW} y1={g.cellY + col.cutAfter * (g.cellH + g.cellGap) - g.cellGap / 2} y2={g.cellY + col.cutAfter * (g.cellH + g.cellGap) - g.cellGap / 2} />
              )}
            </g>
          );
        })}
      </svg>
    </div>
  );
}
