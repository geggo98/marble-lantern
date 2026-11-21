// MiniBoard and HeatBoard (design notes, section 6): the 121-cell star in the geometry of the game board (ui/board-model.ts), flat plate,
// no text on the board. A static board is role="img" with <title> and <desc>; a board with a cell picker is role="group".
import { useId, useRef } from 'react';
import type { KeyboardEvent, ReactNode } from 'react';
import { CELLS } from '../../board/cells';
import { VIEW_BOX, cellPoint, navigate } from '../../ui/board-model';
import type { ArrowKey } from '../../ui/board-model';
import { HEAT_RADIUS, arrowGeometry } from './board-heat';
import type { Arrow, HeatLevel, SignedLevel } from './board-heat';

export interface CellPicker {
  /** The selected (and only tabbable) cell. */
  value: number;
  onChange: (cell: number) => void;
  /** Accessible name of the group. */
  label: string;
  /** Accessible name of one cell (`kit.board.cell`: cell name and content). */
  cellLabel: (cell: number) => string;
}

export interface BoardCommon {
  title: string;
  desc: string;
  /** Cells with the neighbour ring (accent, r 13, 4 units). */
  rings?: readonly number[];
  /** Cells with the dashed origin ring of a move. */
  fromRings?: readonly number[];
  arrows?: readonly Arrow[];
  /** A board with a cell picker is a group with one tab stop (roving tabindex). */
  picker?: CellPicker;
  /** Extra SVG layers above the marbles (pair lines, number marks). */
  children?: ReactNode;
}

const VIEW = `${VIEW_BOX.x} ${VIEW_BOX.y} ${VIEW_BOX.width} ${VIEW_BOX.height}`;

const ARROW_KEYS = new Set<string>(['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown', 'Home', 'End']);

function Plate(): React.JSX.Element {
  return (
    <g>
      <path className="ml-fig-board__plate-edge" d="M0 -240L207.8 120L-207.8 120Z" />
      <path className="ml-fig-board__plate-edge" d="M0 240L-207.8 -120L207.8 -120Z" />
      <path className="ml-fig-board__plate" d="M0 -240L207.8 120L-207.8 120Z" />
      <path className="ml-fig-board__plate" d="M0 240L-207.8 -120L207.8 -120Z" />
    </g>
  );
}

function Arrows({ arrows }: { arrows: readonly Arrow[] }): React.JSX.Element {
  return (
    <g>
      {arrows.map((a, i) => {
        const g = arrowGeometry(a);
        return (
          <g key={i}>
            <path
              className="ml-fig-board__arrow"
              d={`M${g.from.x.toFixed(2)} ${g.from.y.toFixed(2)}L${g.end.x.toFixed(2)} ${g.end.y.toFixed(2)}`}
              strokeWidth={a.width ?? 5}
              strokeDasharray={a.dashed ? '7 6' : undefined}
            />
            <path className="ml-fig-board__arrowhead" d={g.head} />
          </g>
        );
      })}
    </g>
  );
}

function Picker({ picker }: { picker: CellPicker }): React.JSX.Element {
  const group = useRef<SVGGElement>(null);
  const memory = useRef<number | null>(null);
  const focusCell = (cell: number): void => {
    group.current?.querySelector<SVGElement>(`[data-cell="${cell}"]`)?.focus();
  };
  const onKeyDown = (event: KeyboardEvent<SVGElement>, cell: number): void => {
    if (!ARROW_KEYS.has(event.key)) return;
    event.preventDefault();
    const moved = navigate(cell, event.key as ArrowKey, false, memory.current);
    memory.current = event.key === 'ArrowUp' || event.key === 'ArrowDown' ? moved.x : null;
    if (moved.cell !== cell) {
      picker.onChange(moved.cell);
      focusCell(moved.cell);
    }
  };
  const p = cellPoint(picker.value, false);
  return (
    <g ref={group} role="group" aria-label={picker.label}>
      <g aria-hidden="true">
        <circle className="ml-cellfocus__gap" cx={p.x} cy={p.y} r={17.5} />
        <circle className="ml-cellfocus" cx={p.x} cy={p.y} r={20} />
      </g>
      {CELLS.map((c) => {
        const q = cellPoint(c.id, false);
        return (
          <circle
            key={c.id}
            className="ml-fig-board__cell ml-fig-board__cell--picker"
            data-cell={c.id}
            cx={q.x}
            cy={q.y}
            r={15}
            role="button"
            aria-pressed={c.id === picker.value}
            aria-label={picker.cellLabel(c.id)}
            tabIndex={c.id === picker.value ? 0 : -1}
            onClick={() => picker.onChange(c.id)}
            onKeyDown={(e) => onKeyDown(e, c.id)}
            onFocus={() => {
              if (c.id !== picker.value) picker.onChange(c.id);
            }}
          />
        );
      })}
    </g>
  );
}

function BoardSvg({ title, desc, rings, fromRings, arrows, picker, children, base, layer }: BoardCommon & { base: ReactNode; layer?: ReactNode }): React.JSX.Element {
  const id = useId();
  const labelled = { 'aria-labelledby': `${id}-t ${id}-d` };
  return (
    <svg
      className="ml-fig-board"
      viewBox={VIEW}
      role={picker ? 'group' : 'img'}
      {...labelled}
    >
      <title id={`${id}-t`}>{title}</title>
      <desc id={`${id}-d`}>{desc}</desc>
      <Plate />
      {base}
      {rings?.map((cell) => {
        const p = cellPoint(cell, false);
        return <circle key={`nb${cell}`} className="ml-fig-board__nb" cx={p.x} cy={p.y} r={13} />;
      })}
      {layer}
      {fromRings?.map((cell) => {
        const p = cellPoint(cell, false);
        return <circle key={`from${cell}`} className="ml-fig-board__from" cx={p.x} cy={p.y} r={16} />;
      })}
      {arrows && <Arrows arrows={arrows} />}
      {children}
      {picker && <Picker picker={picker} />}
    </svg>
  );
}

const Sockets = ({ className }: { className: string }): React.JSX.Element => (
  <g>
    {CELLS.map((c) => {
      const p = cellPoint(c.id, false);
      return <circle key={c.id} className={className} cx={p.x} cy={p.y} r={11.5} />;
    })}
  </g>
);

export interface MiniBoardProps extends BoardCommon {
  /** Cell ids per seat; seat `a` starts in the top corner (caption: `kit.board.orientation`). */
  pieces: { a?: readonly number[]; b?: readonly number[]; c?: readonly number[] };
}

/** Empty sockets and the marbles of the board design (`#ml-piece-a|b|c` of the sprite). */
export function MiniBoard({ pieces, ...common }: MiniBoardProps): React.JSX.Element {
  const marbles = (seat: 'a' | 'b' | 'c'): ReactNode =>
    (pieces[seat] ?? []).map((cell) => {
      const p = cellPoint(cell, false);
      return <use key={`${seat}${cell}`} href={`#ml-piece-${seat}`} x={p.x} y={p.y} data-seat={seat} data-cell={cell} />;
    });
  return (
    <BoardSvg
      {...common}
      base={<Sockets className="ml-socket" />}
      layer={
        <g>
          {marbles('a')}
          {marbles('b')}
          {marbles('c')}
        </g>
      }
    />
  );
}

export type HeatCell = { level: HeatLevel } | { signed: SignedLevel };

export interface HeatBoardProps extends BoardCommon {
  /** One entry per cell id 0 to 120; `null` or a missing entry draws only the ring. A sequential level draws a disc, a negative signed level a diamond. */
  cells: readonly (HeatCell | null | undefined)[];
}

/** Rings on every cell and one disc (sequential, rising size) or disc or diamond (signed) per valued cell. */
export function HeatBoard({ cells, ...common }: HeatBoardProps): React.JSX.Element {
  return (
    <BoardSvg
      {...common}
      base={<Sockets className="ml-fig-heat__ring" />}
      layer={
        <g>
          {cells.map((cell, id) => {
            if (!cell) return null;
            const p = cellPoint(id, false);
            if ('level' in cell) {
              return <circle key={id} className={`ml-fig-heat__v--${cell.level}`} data-level={cell.level} cx={p.x} cy={p.y} r={HEAT_RADIUS[cell.level]} />;
            }
            const r = cell.signed === 'zero' ? 5 : cell.signed.endsWith('2') ? 12.5 : 8.5;
            return cell.signed.startsWith('neg') ? (
              <path key={id} className={`ml-fig-heat__s--${cell.signed}`} data-signed={cell.signed} d={`M${p.x} ${p.y - r}L${p.x + r} ${p.y}L${p.x} ${p.y + r}L${p.x - r} ${p.y}Z`} />
            ) : (
              <circle key={id} className={`ml-fig-heat__s--${cell.signed}`} data-signed={cell.signed} cx={p.x} cy={p.y} r={r} />
            );
          })}
        </g>
      }
    />
  );
}
