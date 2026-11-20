// The SVG board (design-notes.md, sections 6 to 8): drawn from the cell list, layers bottom to top, tap resolved to the
// nearest candidate, keyboard play with one tab stop, move animation along the jump path.
import { useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { CELLS, GOAL_CELLS } from '../board/cells';
import { jumpPath } from '../board/path';
import type { Side } from '../game/board-state';
import { makeMove } from '../engines/engine';
import {
  cellName, cellPoint, cycle, movableCells, navigate, reading, resolveTap, targetsOf, VIEW_BOX,
} from './board-model';
import type { ArrowKey, MoveDescription, Point, TapAction } from './board-model';
import { GuideGlyphPath, GuideRing, bandTextKey, pieceTextKey } from './GuideGlyph';
import type { Band } from '../guides/bands';
import { prefersReducedMotion } from './primitives';
import { haptic, HAPTIC_ILLEGAL, HAPTIC_MOVE, HAPTIC_SELECT } from './haptics';
import {
  clientToBoard, exceedsDragThreshold, hitRadiusUnits, markBoost, nearestCell, REACH_PX, snapTarget,
} from './touch-model';
import type { SideStyle } from './primitives';
import { useT } from './texts';

/** Move guides: the bands the board draws. Without them the board looks and works as before. */
export interface BoardGuides {
  /** Band of the best move of each movable piece, by cell. */
  pieces: ReadonlyMap<number, Band>;
  /** Band of each move, by move code (from | to << 8); read for the targets of the selected piece. */
  moves: ReadonlyMap<number, Band>;
}

export interface BoardProps {
  /** Optional move guides; shown only while `interactive`. */
  guides?: BoardGuides | null;
  /** Draw the legal targets of the selected piece (default true). False: no target marks; the selected piece keeps its highlight, and taps, keys and the cell labels work as before. */
  targetMarks?: boolean;
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
  sideLabel: (side: number) => string;
  /** The marble of a seat. Without it: `aSide` is amber (A), every other seat blue (B), as in the two-player game. */
  seatStyle?: (seat: number) => SideStyle;
  /** The goal corner of each seat, drawn on the sockets in the marble of that seat. Without it: the two-player goals. */
  goals?: readonly { cells: readonly number[]; style: SideStyle }[];
  onMove: (move: number) => void;
}

const ease = (t: number): number => t * t * (3 - 2 * t);

interface Moving {
  cell: number;
  at: Point;
}

/** A piece that the finger or the mouse carries: where it is on the board, and the target it would snap to. */
interface Drag {
  cell: number;
  at: Point;
}

/** A press on a movable piece that may become a drag. */
interface Press {
  id: number;
  from: number;
  x: number;
  y: number;
  dragging: boolean;
}

export function Board(props: BoardProps): React.JSX.Element {
  const { owner, legal, interactive, inert, aSide, rotated, plies, last, halo, onMove } = props;
  const styleOf = (seat: number): SideStyle => props.seatStyle?.(seat) ?? (seat === aSide ? 'a' : 'b');
  const { t } = useT();
  const svgRef = useRef<SVGSVGElement | null>(null);
  const [selected, setSelected] = useState<number | null>(null);
  const [focusCell, setFocusCell] = useState<number | null>(null);
  const [keyboard, setKeyboard] = useState(false);
  const [previewTarget, setPreviewTarget] = useState<number | null>(null);
  const [illegal, setIllegal] = useState<{ cell: number; key: number } | null>(null);
  const [moving, setMoving] = useState<Moving | null>(null);
  const [drag, setDrag] = useState<Drag | null>(null);
  const [pxPerUnit, setPxPerUnit] = useState(1);
  const press = useRef<Press | null>(null);
  const suppressClick = useRef(false);
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

  // The one tab stop belongs on a piece that can move: it stays on the cell the person used last only while that cell still can.
  // After a move (the next seat, or the same seat again) the stop returns to the first movable piece.
  useEffect(() => {
    const now = movableCells(legal);
    setFocusCell((cell) => (cell !== null && now.includes(cell) ? cell : null));
  }, [legal]);

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

  // The scale of the board in CSS px per board unit: the hit circles and the marks follow it.
  useLayoutEffect(() => {
    const el = svgRef.current;
    if (!el) return undefined;
    const measure = (): void => {
      const box = el.getBoundingClientRect();
      if (box.width > 0 && box.height > 0) setPxPerUnit(clientToBoard(box, { x: 0, y: 0 }).pxPerUnit);
    };
    measure();
    if (typeof ResizeObserver !== 'function') return undefined;
    const observer = new ResizeObserver(measure);
    observer.observe(el);
    return () => observer.disconnect();
  }, []);

  const defaultFocus = useMemo(() => {
    const first = reading(movable, rotated)[0];
    return first ?? CELLS.find((c) => c.q === 0 && c.r === 0)!.id;
  }, [movable, rotated]);
  const tabCell = focusCell ?? defaultFocus;

  // A person who plays with the keyboard keeps the focus inside the board when the turn comes back: it moves with the tab stop.
  useEffect(() => {
    if (!interactive) return;
    const active = document.activeElement;
    if (!(active instanceof Element) || active === svgRef.current || !svgRef.current?.contains(active)) return;
    const cell = Number(active.getAttribute('data-cell'));
    if (!movable.includes(cell)) {
      const element = svgRef.current.querySelector<SVGElement>(`[data-cell="${defaultFocus}"]`);
      element?.focus();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps -- only when the turn or the position changes
  }, [interactive, legal]);

  const act = (action: TapAction): void => {
    switch (action.type) {
      case 'select':
        setSelected(action.cell);
        setIllegal(null);
        haptic(HAPTIC_SELECT);
        break;
      case 'move':
        setSelected(null);
        setPreviewTarget(null);
        haptic(HAPTIC_MOVE);
        onMove(action.move);
        break;
      case 'clear':
        setSelected(null);
        setPreviewTarget(null);
        break;
      case 'illegal':
        setIllegal({ cell: action.cell, key: Date.now() });
        haptic(HAPTIC_ILLEGAL);
        break;
      case 'none':
        break;
    }
  };

  /** The pointer in board units, and the reach of a cell (44 CSS px wide) in board units. */
  const toBoard = (event: { clientX: number; clientY: number }): { point: Point; radius: number } | null => {
    const rect = svgRef.current?.getBoundingClientRect();
    if (!rect || rect.width <= 0 || rect.height <= 0) return null;
    const mapped = clientToBoard(rect, { x: event.clientX, y: event.clientY });
    return { point: mapped.point, radius: REACH_PX / mapped.pxPerUnit };
  };

  const onClick = (event: React.MouseEvent<SVGSVGElement>): void => {
    if (suppressClick.current) {
      suppressClick.current = false; // the click that ends a drag has been handled by the drop
      return;
    }
    if (!interactive) return;
    setKeyboard(false);
    const element = (event.target as Element).closest('[data-cell]');
    const hitCell = element ? Number((element as HTMLElement).dataset.cell ?? (element as SVGElement).getAttribute('data-cell')) : null;
    const where = toBoard(event);
    act(resolveTap({ selected, legal, rotated, hitCell, ...(where ?? {}) }));
    if (hitCell !== null) setFocusCell(hitCell);
  };

  // Drag and drop: a press on a movable piece, then a move of more than 6 px, carries the piece; a release near a legal
  // target plays the move, a release elsewhere cancels the drop. A press without that move stays a tap (see onClick).
  const onPointerDown = (event: React.PointerEvent<SVGSVGElement>): void => {
    setKeyboard(false);
    if (!interactive || !event.isPrimary || (event.pointerType === 'mouse' && event.button !== 0)) return;
    const where = toBoard(event);
    if (!where) return;
    const candidates = selected === null ? movable : [...new Set([...targetsOf(legal, selected), ...movable])];
    const from = nearestCell(where.point, candidates, rotated, where.radius);
    if (from === null || !movable.includes(from)) return; // a target is tapped, not dragged
    press.current = { id: event.pointerId, from, x: event.clientX, y: event.clientY, dragging: false };
  };

  const endDrag = (): void => {
    press.current = null;
    setDrag(null);
    setPreviewTarget(null);
  };

  const onPointerMove = (event: React.PointerEvent<SVGSVGElement>): void => {
    if (!interactive) return;
    const p = press.current;
    if (p && p.id === event.pointerId) {
      if (!p.dragging && exceedsDragThreshold(event.clientX - p.x, event.clientY - p.y)) {
        p.dragging = true;
        try {
          svgRef.current?.setPointerCapture?.(event.pointerId);
        } catch {
          // the pointer is gone already; pointercancel follows
        }
        if (selected !== p.from) act({ type: 'select', cell: p.from });
      }
      if (p.dragging) {
        const where = toBoard(event);
        if (!where) return;
        setDrag({ cell: p.from, at: where.point });
        setPreviewTarget(snapTarget(where.point, targetsOf(legal, p.from), rotated, where.radius));
        return;
      }
    }
    if (selected === null || event.pointerType !== 'mouse') return;
    const where = toBoard(event);
    if (!where) return;
    const action = resolveTap({ selected, legal, rotated, hitCell: null, point: where.point, radius: where.radius });
    setPreviewTarget(action.type === 'move' ? action.move >>> 8 : null);
  };

  const onPointerUp = (event: React.PointerEvent<SVGSVGElement>): void => {
    const p = press.current;
    if (!p || p.id !== event.pointerId) return;
    if (!p.dragging) {
      press.current = null;
      return;
    }
    suppressClick.current = true; // the click that follows the release is not a tap
    setTimeout(() => {
      suppressClick.current = false;
    }, 100);
    const where = toBoard(event);
    const drop = where ? snapTarget(where.point, targetsOf(legal, p.from), rotated, where.radius) : null;
    endDrag();
    if (drop !== null) act({ type: 'move', move: makeMove(p.from, drop) });
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
  const goalOf = new Map<number, SideStyle>();
  for (const g of props.goals ?? [{ cells: GOAL_CELLS[aSide], style: 'a' as const }, { cells: GOAL_CELLS[aSide === 0 ? 1 : 0], style: 'b' as const }]) {
    for (const c of g.cells) goalOf.set(c, g.style);
  }
  const points = (cells: readonly number[]): string =>
    cells.map((c) => `${pos(c).x.toFixed(1)},${pos(c).y.toFixed(1)}`).join(' ');

  const boost = markBoost(pxPerUnit);
  const hitRadius = hitRadiusUnits(pxPerUnit);
  const guides = interactive ? (props.guides ?? null) : null;
  const targetBand = (cell: number): Band | undefined =>
    guides && selected !== null ? guides.moves.get(makeMove(selected, cell)) : undefined;
  const pieceGuide = (cell: number): Band | undefined => (guides && movable.includes(cell) ? guides.pieces.get(cell) : undefined);

  const label = (cell: number): string => {
    const o = owner[cell] ?? -1;
    const parts = [cellName(cell), o < 0 ? t('cell_empty') : props.sideLabel(o)];
    if (cell === selected) parts.push(t('cell_selected'));
    else if (targets.includes(cell)) {
      parts.push(t('cell_target'));
      const band = targetBand(cell);
      if (band) parts.push(t(bandTextKey(band)));
    }
    const piece = pieceGuide(cell);
    if (piece) parts.push(t(pieceTextKey(piece)));
    return parts.join(', ');
  };

  // Pieces, the moving one last so that it is drawn on top.
  const pieceCells = CELLS.filter((c) => (owner[c.id] ?? -1) >= 0)
    .map((c) => c.id)
    .sort((a, b) => Number(moving?.cell === a || drag?.cell === a) - Number(moving?.cell === b || drag?.cell === b));

  const selectedPoint = selected === null ? null : pos(selected);
  const focusPoint = keyboard ? pos(tabCell) : null;
  const trailVisible = last !== null && selected === null;
  const lastStyle = last ? styleOf(last.side) : 'a';

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
        onPointerDown={onPointerDown}
        onPointerUp={onPointerUp}
        onPointerCancel={endDrag}
        // lostpointercapture bubbles: a touch is captured by the cell it started on, and handing the capture to the board
        // raises it on that cell. Only the board's own loss ends a drag.
        onLostPointerCapture={(e) => e.target === e.currentTarget && endDrag()}
        onContextMenu={(e) => e.preventDefault()}
        onPointerLeave={() => !press.current?.dragging && setPreviewTarget(null)}
        style={{ ['--ml-boost' as string]: boost }}
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
            const goal = goalOf.get(c.id);
            const cls = goal ? `ml-socket ml-socket--goal-${goal}` : 'ml-socket';
            return (
              <g key={c.id}>
                <circle className={cls} cx={p.x} cy={p.y} r={11.5} />
                {goal === 'b' && <circle className="ml-socket__mark-b" cx={p.x} cy={p.y} r={6.5} />}
                {goal === 'c' && <path className="ml-socket__mark-c" d={`M${p.x} ${p.y - 6.5}L${p.x + 6.5} ${p.y}L${p.x} ${p.y + 6.5}L${p.x - 6.5} ${p.y}Z`} />}
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
          {(props.targetMarks ?? true) && targets.map((c) => {
            const band = targetBand(c);
            return (
              <g key={c}>
                <circle className="ml-target__ring" cx={pos(c).x} cy={pos(c).y} r={11.5} />
                {band ? (
                  <g className={`ml-guide ml-guide--${band}`} data-guide="target" data-band={band} transform={`translate(${pos(c).x.toFixed(1)} ${pos(c).y.toFixed(1)})`}>
                    <GuideRing band={band} r={14.5} />
                    <GuideGlyphPath band={band} />
                  </g>
                ) : (
                  <circle className="ml-target__dot" cx={pos(c).x} cy={pos(c).y} r={4.5 * boost} />
                )}
              </g>
            );
          })}
        </g>
        {previewPath && <polyline className="ml-preview__line" points={points(previewPath)} />}
        {halo.map((c) => (
          <circle key={c} className="ml-halo" cx={pos(c).x} cy={pos(c).y} r={17} />
        ))}
        <g className="ml-pieces">
          {pieceCells.map((c) => {
            const o = owner[c] ?? 0;
            const dragged = drag?.cell === c;
            const p = dragged ? drag.at : moving?.cell === c ? moving.at : pos(c);
            const isSelected = c === selected;
            return (
              <use
                key={c}
                className={dragged ? 'is-selected is-dragging' : isSelected ? 'is-selected' : undefined}
                href={`#ml-piece-${styleOf(o)}`}
                transform={`translate(${p.x.toFixed(1)} ${p.y.toFixed(1)})${isSelected ? ' scale(1.12)' : ''}`}
              />
            );
          })}
        </g>
        {guides && (
          <g className="ml-guides">
            {movable.map((c) => {
              const band = guides.pieces.get(c);
              if (!band) return null;
              return (
                <g key={c} className={`ml-guide ml-guide--${band}`} data-guide="piece" data-band={band} data-cell-of={c} transform={`translate(${pos(c).x.toFixed(1)} ${pos(c).y.toFixed(1)})`}>
                  <GuideRing band={band} r={16.8} />
                  <g transform={`translate(12 -12) scale(${boost.toFixed(2)})`}>
                    <circle className="ml-guide__badge" r={8.2} />
                    <g transform="scale(0.95)">
                      <GuideGlyphPath band={band} />
                    </g>
                  </g>
                </g>
              );
            })}
          </g>
        )}
        {previewCell !== null && selected !== null && (
          <g className="ml-preview__ghost">
            <use
              href={`#ml-piece-${styleOf(owner[selected] ?? 0)}`}
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
                r={hitRadius}
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
