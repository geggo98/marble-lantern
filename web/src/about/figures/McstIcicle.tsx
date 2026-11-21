// The icicle of Fig. 3.3 (review M6): the tree of the search as it has grown. One bar for the root, a row of blocks below it for the root moves
// that have been tried and a block for all the others, then one row per level of replies. The width of a block is its share of the visits.
// The picture is one role="img"; the stepper and the table reach every state.
import { useId, useRef } from 'react';
import { useMeasuredWidth } from '../kit';
import { icicleLayout } from './mcts-icicle';
import type { BlockState } from './mcts-icicle';
import type { McstData, Substep } from './puct';

const ROOT_H = 24;
const MOVE_Y = 28;
const MOVE_H = 22;
const REPLY_Y = 54;
const REPLY_H = 14;
const ROW_GAP = 2;
/** Least width (units) of a block that carries a glyph. */
const GLYPH_MIN = 14;
const MIN_WIDTH = 240;
const DEFAULT_WIDTH = 640;
const COLUMNS_NARROW = 6;

const rowY = (depth: number): number => (depth === 1 ? MOVE_Y : REPLY_Y + (depth - 2) * (REPLY_H + ROW_GAP));
const rowH = (depth: number): number => (depth === 1 ? MOVE_H : REPLY_H);
const ORDER: Record<BlockState, number> = { todo: 0, visited: 1, path: 2, active: 3 };

export interface McstIcicleProps {
  data: McstData;
  k: number;
  substep: Substep;
  title: string;
  desc: string;
  /** Text in the root bar, for example `N = 50`. */
  rootText: string;
  /** `›` or `»` of a root move; `…` stands in the block of the other moves. */
  glyphOf: (edge: number) => string;
  /** Below the figure breakpoint only the six most visited root moves get their own block. */
  compact: boolean;
}

export function McstIcicle({ data, k, substep, title, desc, rootText, glyphOf, compact }: McstIcicleProps): React.JSX.Element {
  const holder = useRef<HTMLDivElement>(null);
  const measured = useMeasuredWidth(holder);
  const width = measured > 0 ? Math.max(MIN_WIDTH, Math.round(measured)) : DEFAULT_WIDTH;
  const layout = icicleLayout(data, k, substep, width, compact ? COLUMNS_NARROW : Number.POSITIVE_INFINITY);
  const height = rowY(layout.maxDepth) + rowH(layout.maxDepth) + 6;
  const id = useId();
  const drawn = [...layout.blocks].filter((b) => b.w >= 0.75).sort((a, b) => ORDER[a.state] - ORDER[b.state]);
  return (
    <div ref={holder} data-layout={compact ? 'compact' : 'wide'}>
      <svg className="ml-fig-ice ml-fig-mi" viewBox={`0 0 ${width} ${height}`} role="img" aria-labelledby={`${id}-t ${id}-d`}>
        <title id={`${id}-t`}>{title}</title>
        <desc id={`${id}-d`}>{desc}</desc>
        <rect className="ml-fig-ice__cell ml-fig-ice__cell--visited ml-fig-mi__block" data-root="" x={0} y={0} width={width} height={ROOT_H} />
        <text className="ml-fig-mi__label ml-fig-mi__label--root" x={8} y={ROOT_H / 2}>
          {rootText}
        </text>
        {drawn.map((b) => (
          <g key={b.key} data-depth={b.depth} data-state={b.state} {...(b.depth === 1 && b.edge >= 0 ? { 'data-column': b.edge } : {})} {...(b.key === 'rest' ? { 'data-rest': '' } : {})}>
            <rect className={`ml-fig-ice__cell ml-fig-ice__cell--${b.state} ml-fig-mi__block`} x={b.x} y={rowY(b.depth)} width={b.w} height={rowH(b.depth)} />
            {b.depth === 1 && b.w >= GLYPH_MIN && (
              <text className={`ml-fig-mi__label ml-fig-mi__label--${b.state}`} x={b.x + b.w / 2} y={rowY(1) + MOVE_H / 2}>
                {b.key === 'rest' ? '…' : glyphOf(b.edge)}
              </text>
            )}
          </g>
        ))}
      </svg>
    </div>
  );
}
