// The SVG board (design-notes.md, sections 6 to 8): drawn from the cell list, layers bottom to top, tap resolved to the
// nearest candidate, keyboard play with one tab stop, move animation along the jump path.
import { useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { CELLS, GOAL_CELLS } from '../board/cells';
import { jumpPath } from '../board/path';
import type { Side } from '../game/board-state';
import {
  cellName, cellPoint, cycle, movableCells, navigate, reading, resolveTap, targetsOf, VIEW_BOX,
} from './board-model';
import type { ArrowKey, MoveDescription, Point, TapAction } from './board-model';
import { prefersReducedMotion } from './primitives';
import type { SideStyle } from './primitives';
import { useT } from './texts';

export interface BoardProps {
  owner: Int8Array;
  legal: readonly number[];
  /** True while the human may move. Otherwise the board is inert. */
  interactive: boolean;
  /** Draw it inert and with the progress cursor (loading, thinking, game over). */
  inert: boolean;
  /** The side drawn as amber ("A"); the other side is blue with a ring ("B"). */
  aSide: Side;
  /** The human plays the top start corner: turn the board by 180 degrees. */
  rotated: boolean;
  /** Number of plies played; a change by exactly one animates the last move. */
  plies: number;
  last: MoveDescription | null;
  /** Cells to ring after a win. */
  halo: readonly number[];
  sideLabel: (side: Side) => string;
  onMove: (move: number) => void;
}

const styleOf = (side: number, aSide: Side): SideStyle => (side === aSide ? 'a' : 'b');
const ease = (t: number): number => t * t * (3 - 2 * t);

interface Moving {
  cell: number;
  at: Point;
}

export function Board(props: BoardProps): React.JSX.Element {
  const { owner, legal, interactive, inert, aSide, rotated, plies, last, halo, onMove } = props;
  const { t } = useT();
  const svgRef = useRef<SVGSVGElement | null>(null);
  const [selected, setSelected] = useState<number | null>(null);
  const [focusCell, setFocusCell] = useState<number | null>(null);
  const [keyboard, setKeyboard] = useState(false);
  const [previewTarget, setPreviewTarget] = useState<number | null>(null);
  const [illegal, setIllegal] = useState<{ cell: number; key: number } | null>(null);
  const [moving, setMoving] = useState<Moving | null>(null);
  const columnMemory = useRef<number | null>(null);
  const previousPlies = useRef(plies);

  const movable = useMemo(() => (interactive ? movableCells(legal) : []), [interactive, legal]);
  const targets = useMemo(
    () => (interactive && selected !== null ? targetsOf(legal, selected) : []),
    [interactive, legal, selected],
  );

  // A new position clears everything that belonged to the old one.
  useEffect(() => {
    setSelected(null);
    setPreviewTarget(null);
    setIllegal(null);
  }, [plies, legal]);

  useEffect(() => {
    if (!interactive) {
      setSelected(null);
      setPreviewTarget(null);
    }
  }, [interactive]);

  // The illegal-click mark stays for 1.2 s, then fades.
  useEffect(() => {
    if (!illegal) return undefined;
    const timer = setTimeout(() => setIllegal(null), 1500);
    return () => clearTimeout(timer);
  }, [illegal]);

  // Move animation: one hop after the other along the jump path; at most 1.1 s for a long chain.
  useLayoutEffect(() => {
    const grew = plies === previousPlies.current + 1;
    previousPlies.current = plies;
    if (!grew || !last || last.path.length < 2) return undefined;
    if (prefersReducedMotion() || typeof requestAnimationFrame !== 'function') return undefined;
    const path = last.path.map((c) => cellPoint(c, rotated));
    const hops = path.length - 1;
    const hopMs = Math.min(180, 1100 / hops);
    const started = performance.now();
    let frame = 0;
    const step = (now: number): void => {
      const elapsed = Math.max(0, now - started); // a frame timestamp can precede performance.now()
      if (elapsed >= hopMs * hops) {
        setMoving(null);
        return;
      }
      const i = Math.floor(elapsed / hopMs);
      const k = ease((elapsed - i * hopMs) / hopMs);
      const a = path[i]!;
      const b = path[i + 1]!;
      setMoving({ cell: last.to, at: { x: a.x + (b.x - a.x) * k, y: a.y + (b.y - a.y) * k } });
      frame = requestAnimationFrame(step);
    };
    setMoving({ cell: last.to, at: path[0]! });
    frame = requestAnimationFrame(step);
    return () => {
      cancelAnimationFrame(frame);
      setMoving(null);
    };
  }, [plies, last, rotated]);

  const defaultFocus = useMemo(() => {
    const first = reading(movable, rotated)[0];
    return first ?? CELLS.find((c) => c.q === 0 && c.r === 0)!.id;
  }, [movable, rotated]);
  const tabCell = focusCell ?? defaultFocus;

  const act = (action: TapAction): void => {
    switch (action.type) {
      case 'select':
        setSelected(action.cell);
        setIllegal(null);
        break;
      case 'move':
        setSelected(null);
        setPreviewTarget(null);
        onMove(action.move);
        break;
      case 'clear':
        setSelected(null);
        setPreviewTarget(null);
        break;
      case 'illegal':
        setIllegal({ cell: action.cell, key: Date.now() });
        break;
      case 'none':
        break;
    }
  };

  const toBoard = (event: { clientX: number; clientY: number }): { point: Point; radius: number } | null => {
    const rect = svgRef.current?.getBoundingClientRect();
    if (!rect || rect.width <= 0) return null;
    const scale = VIEW_BOX.width / rect.width;
    return {
      point: { x: VIEW_BOX.x + (event.clientX - rect.left) * scale, y: VIEW_BOX.y + (event.clientY - rect.top) * scale },
      radius: 16 * scale,
    };
  };

  const onClick = (event: React.MouseEvent<SVGSVGElement>): void => {
    if (!interactive) return;
    setKeyboard(false);
    const element = (event.target as Element).closest('[data-cell]');
    const hitCell = element ? Number((element as HTMLElement).dataset.cell ?? (element as SVGElement).getAttribute('data-cell')) : null;
    const where = toBoard(event);
    act(resolveTap({ selected, legal, rotated, hitCell, ...(where ?? {}) }));
    if (hitCell !== null) setFocusCell(hitCell);
  };

  const onPointerMove = (event: React.PointerEvent<SVGSVGElement>): void => {
    if (!interactive || selected === null || event.pointerType !== 'mouse') return;
    const where = toBoard(event);
    if (!where) return;
    const action = resolveTap({ selected, legal, rotated, hitCell: null, point: where.point, radius: where.radius });
    setPreviewTarget(action.type === 'move' ? action.move >>> 8 : null);
  };

  const focusOn = (cell: number): void => {
    setFocusCell(cell);
    const element = svgRef.current?.querySelector<SVGElement>(`[data-cell="${cell}"]`);
    element?.focus();
  };

  const onKeyDown = (event: React.KeyboardEvent<SVGElement>, cell: number): void => {
    setKeyboard(true);
    switch (event.key) {
      case 'ArrowLeft':
      case 'ArrowRight':
      case 'ArrowUp':
      case 'ArrowDown':
      case 'Home':
      case 'End': {
        event.preventDefault();
        const vertical = event.key === 'ArrowUp' || event.key === 'ArrowDown';
        const next = navigate(cell, event.key as ArrowKey, rotated, vertical ? columnMemory.current : null);
        columnMemory.current = vertical ? next.x : null;
        focusOn(next.cell);
        break;
      }
      case 'PageDown':
      case 'PageUp': {
        event.preventDefault();
        const list = reading(selected === null ? movable : targets, rotated);
        const next = cycle(list, list.includes(cell) ? cell : null, event.key === 'PageDown' ? 1 : -1);
        if (next !== null) focusOn(next);
        break;
      }
      case 'Enter':
      case ' ':
        event.preventDefault();
        if (interactive) act(resolveTap({ selected, legal, rotated, hitCell: cell }));
        break;
      case 'Escape':
        setSelected(null);
        setPreviewTarget(null);
        break;
      default:
        break;
    }
  };

  const previewCell = previewTarget ?? (keyboard && focusCell !== null && targets.includes(focusCell) ? focusCell : null);
  const occupied = useMemo(() => Array.from(owner, (o) => o >= 0), [owner]);
  const previewPath = useMemo(() => {
    if (selected === null || previewCell === null) return null;
    return jumpPath(selected, previewCell, occupied) ?? [selected, previewCell];
  }, [selected, previewCell, occupied]);

  const pos = (c: number): Point => cellPoint(c, rotated);
  const bSide: Side = aSide === 0 ? 1 : 0;
  const goalA = new Set(GOAL_CELLS[aSide]);
  const goalB = new Set(GOAL_CELLS[bSide]);
  const points = (cells: readonly number[]): string =>
    cells.map((c) => `${pos(c).x.toFixed(1)},${pos(c).y.toFixed(1)}`).join(' ');

  const label = (cell: number): string => {
    const o = owner[cell] ?? -1;
    const parts = [cellName(cell), o < 0 ? t('cell_empty') : props.sideLabel(o as Side)];
    if (cell === selected) parts.push(t('cell_selected'));
    else if (targets.includes(cell)) parts.push(t('cell_target'));
    return parts.join(', ');
  };

  // Pieces, the moving one last so that it is drawn on top.
  const pieceCells = CELLS.filter((c) => (owner[c.id] ?? -1) >= 0)
    .map((c) => c.id)
    .sort((a, b) => Number(moving?.cell === a) - Number(moving?.cell === b));

  const selectedPoint = selected === null ? null : pos(selected);
  const focusPoint = keyboard ? pos(tabCell) : null;
  const trailVisible = last !== null && selected === null;
  const lastStyle = last ? styleOf(last.side, aSide) : 'a';

  return (
    <div className="ml-board">
      <svg
        ref={svgRef}
        className={inert ? 'ml-board__svg is-inert' : 'ml-board__svg'}
        viewBox={`${VIEW_BOX.x} ${VIEW_BOX.y} ${VIEW_BOX.width} ${VIEW_BOX.height}`}
        role="group"
        aria-label={t('board_label')}
        xmlns="http://www.w3.org/2000/svg"
        onClick={onClick}
        onPointerMove={onPointerMove}
        onPointerDown={() => setKeyboard(false)}
        onPointerLeave={() => setPreviewTarget(null)}
      >
        <g className="ml-board__plate">
          <polygon className="ml-board__plate-edge" points="0,-240 207.8,120 -207.8,120" />
          <polygon className="ml-board__plate-edge" points="0,240 -207.8,-120 207.8,-120" />
          <polygon className="ml-board__plate-fill" points="0,-240 207.8,120 -207.8,120" />
          <polygon className="ml-board__plate-fill" points="0,240 -207.8,-120 207.8,-120" />
        </g>
        <g className="ml-board__sockets">
          {CELLS.map((c) => {
            const p = pos(c.id);
            const cls = goalA.has(c.id) ? 'ml-socket ml-socket--goal-a' : goalB.has(c.id) ? 'ml-socket ml-socket--goal-b' : 'ml-socket';
            return (
              <g key={c.id}>
                <circle className={cls} cx={p.x} cy={p.y} r={11.5} />
                {goalB.has(c.id) && <circle className="ml-socket__mark-b" cx={p.x} cy={p.y} r={6.5} />}
              </g>
            );
          })}
        </g>
        {trailVisible && last && (
          <g className={`ml-trail ml-trail--${lastStyle}`}>
            <polyline className="ml-trail__line" points={points(last.path)} />
            <circle className="ml-trail__origin" cx={pos(last.from).x} cy={pos(last.from).y} r={11.5} />
            {last.path.slice(1, -1).map((c, i) => (
              <circle key={`${c}-${i}`} className="ml-trail__hop" cx={pos(c).x} cy={pos(c).y} r={3.2} />
            ))}
            <circle className="ml-trail__dest" cx={pos(last.to).x} cy={pos(last.to).y} r={17.5} />
          </g>
        )}
        <g className="ml-targets">
          {targets.map((c) => (
            <g key={c}>
              <circle className="ml-target__ring" cx={pos(c).x} cy={pos(c).y} r={11.5} />
              <circle className="ml-target__dot" cx={pos(c).x} cy={pos(c).y} r={4.5} />
            </g>
          ))}
        </g>
        {previewPath && <polyline className="ml-preview__line" points={points(previewPath)} />}
        {halo.map((c) => (
          <circle key={c} className="ml-halo" cx={pos(c).x} cy={pos(c).y} r={17} />
        ))}
        <g className="ml-pieces">
          {pieceCells.map((c) => {
            const o = owner[c] ?? 0;
            const p = moving?.cell === c ? moving.at : pos(c);
            const isSelected = c === selected;
            return (
              <use
                key={c}
                className={isSelected ? 'is-selected' : undefined}
                href={`#ml-piece-${styleOf(o, aSide)}`}
                transform={`translate(${p.x.toFixed(1)} ${p.y.toFixed(1)})${isSelected ? ' scale(1.12)' : ''}`}
              />
            );
          })}
        </g>
        {previewCell !== null && selected !== null && (
          <g className="ml-preview__ghost">
            <use
              href={`#ml-piece-${styleOf(owner[selected] ?? 0, aSide)}`}
              transform={`translate(${pos(previewCell).x.toFixed(1)} ${pos(previewCell).y.toFixed(1)})`}
            />
          </g>
        )}
        {selectedPoint && <circle className="ml-select" cx={selectedPoint.x} cy={selectedPoint.y} r={19} />}
        {illegal && (
          <g className="ml-illegal" key={illegal.key}>
            <circle cx={pos(illegal.cell).x} cy={pos(illegal.cell).y} r={14.5} />
            <path
              d={`M${pos(illegal.cell).x - 4} ${pos(illegal.cell).y - 4}l8 8M${pos(illegal.cell).x + 4} ${pos(illegal.cell).y - 4}l-8 8`}
            />
          </g>
        )}
        {focusPoint && (
          <g>
            <circle className="ml-cellfocus__gap" cx={focusPoint.x} cy={focusPoint.y} r={17.5} />
            <circle className="ml-cellfocus" cx={focusPoint.x} cy={focusPoint.y} r={20} />
          </g>
        )}
        <g className="ml-hit">
          {CELLS.map((c) => {
            const p = pos(c.id);
            return (
              <circle
                key={c.id}
                data-cell={c.id}
                cx={p.x}
                cy={p.y}
                r={15}
                fill="transparent"
                role="button"
                tabIndex={c.id === tabCell ? 0 : -1}
                aria-label={label(c.id)}
                aria-disabled={!interactive}
                onFocus={() => setFocusCell(c.id)}
                onKeyDown={(e) => onKeyDown(e, c.id)}
                style={{ outline: 'none' }}
              />
            );
          })}
        </g>
      </svg>
      {illegal && (
        <p className="ml-hint" role="status">
          <svg className="ml-icon" viewBox="0 0 24 24" aria-hidden="true">
            <use href="#i-alert" />
          </svg>
          <span>{t('illegal_target')}</span>
        </p>
      )}
    </div>
  );
}
