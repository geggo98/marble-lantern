// Figure J2 of topic 6 (java), section plain-java: the same code with objects and with primitives, in three views (a move, the undo data, the
// evaluation). Two lanes side by side (stacked on a phone); each lane counts allocations, bytes and cache lines, or shows the walk of seven states.
// Only two numbers are measured speeds (1.6 M nodes/s for the whole search, +30 % for the incremental evaluation); the tiles say so and never
// set them against each other. Design: design/j2-classical/spec.md. All text comes from the catalog (`fig.javaplain.*`); code lines are literals.
import { useEffect, useRef, useState } from 'react';
import type { KeyboardEvent } from 'react';
import { Figure, Legend, LegendItem, Manipulate, Segmented, Slider, Stepper, stepperLabels, useStepper } from '../kit';
import type { StatusLine } from '../kit/Figure';
import { Markup } from '../kit/markup';
import { FigFoot, figLabel, useFigText } from './common';
import type { Catalog, FigText } from './common';
import { Lane, MemoryMap, PlainReadout } from './java-plain-parts';
import {
  FRAME_INTS, LAST, ROOT_PLY, LONGEST_MOVE_NAME, N, SIZES, SLOTS, STEP_COUNT, VIEWS, bytesAllocated, fullEvalAt, hex4, moveAtPick, moveFrom, moveName, moveTo, narrationKey,
  narrationValues, objectCounts, objectsMap, piecesAt, primitiveCounts, primitivesMap, sumsAt, updateLineAt, walkAt,
} from './java-plain-model';
import type { CellState, Chip, PlainView } from './java-plain-model';
import './java-plain.css';

export interface JavaPlainFigureProps {
  catalog: Catalog;
  number: readonly [number, number];
  /** Initial state (a test or a link passes its own). */
  initialView?: PlainView;
  initialPick?: number;
}

const DASH = '—';

/** The words of a state of a cell or frame, for the accessible name (`fig.javaplain.cell.state.*`). */
const stateWord = (f: FigText, state: CellState): string => f.t(`fig.javaplain.cell.state.${state}`);

// ---------- legend ----------

type Swatch = 'hdr' | 'ref' | 'val' | 'pad' | 'free' | 'sel' | 'write' | 'read' | 'live' | 'stale';
const LEGEND: Record<PlainView, readonly [Swatch, string][]> = {
  move: [['hdr', 'legend.header'], ['ref', 'legend.ref'], ['val', 'legend.value'], ['pad', 'legend.pad'], ['free', 'legend.free'], ['sel', 'legend.selected']],
  undo: [['write', 'legend.write'], ['read', 'legend.read'], ['live', 'legend.live'], ['stale', 'legend.stale']],
  eval: [['write', 'legend.changed'], ['read', 'legend.pieceread'], ['live', 'legend.live'], ['stale', 'legend.stale']],
};

/** The marks are 12 units square, centred in the 32 x 16 box of the kit legend. Colour is never the only cue: fill, border style and shape differ. */
function SwatchMark({ kind }: { kind: Swatch }): React.JSX.Element {
  const a = 'var(--ml-fig-axis)';
  const d = 'var(--ml-fig-dim)';
  const k = 'var(--ml-fig-accent)';
  const body = ((): React.JSX.Element => {
    switch (kind) {
      case 'hdr':
        return <rect x="1" y="4" width="10" height="4" fill={d} />;
      case 'ref':
        return (
          <>
            <rect x="1.5" y="1.5" width="9" height="9" fill="none" stroke={a} />
            <circle cx="6" cy="6" r="2" fill={a} />
          </>
        );
      case 'val':
        return <rect x="1" y="1" width="10" height="10" fill={a} />;
      case 'pad':
        return <circle cx="6" cy="6" r="2" fill={d} />;
      case 'free':
        return <rect x="1.5" y="1.5" width="9" height="9" fill="none" stroke={d} strokeDasharray="2 2" />;
      case 'sel':
        return <rect x="1.5" y="1.5" width="9" height="9" fill={k} stroke="var(--ml-fig-mark)" strokeWidth="1.5" />;
      case 'write':
        return <rect x="1" y="1" width="10" height="10" fill="var(--ml-fig-accent-bg)" stroke={k} strokeWidth="2" />;
      case 'read':
        return <rect x="1" y="1" width="10" height="10" fill="none" stroke={k} strokeWidth="2" />;
      case 'live':
        return <rect x="1.5" y="1.5" width="9" height="9" fill="none" stroke={a} />;
      case 'stale':
        return (
          <>
            <rect x="1.5" y="1.5" width="9" height="9" fill="none" stroke={d} strokeDasharray="3 2" />
            <path d="M2 6H10" stroke={d} />
          </>
        );
    }
  })();
  return <g transform="translate(10 2)">{body}</g>;
}

// ---------- the views: two lane bodies each ----------

interface Bodies {
  objects: React.JSX.Element;
  primitives: React.JSX.Element;
}

function moveBodies({ f, pick }: { f: FigText; pick: number }): Bodies {
  const { t, n } = f;
  const m = moveAtPick(pick);
  const from = moveFrom(m);
  const to = moveTo(m);
  const oc = objectCounts();
  const pc = primitiveCounts();
  const objMap = objectsMap(pick);
  const primMap = primitivesMap(pick);
  const strip = (cls: string, flex: number, body: React.ReactNode): React.JSX.Element => (
    <span className={`ml-fig-jp__f ${cls}`} style={{ flex }}>
      {body}
    </span>
  );
  const pad = SIZES.moveObject - SIZES.objectHeader - 8;
  return {
    objects: (
      <>
        <p className="ml-fig-cmp__code">record Move(int from, int to) · Move[]</p>
        <div className="ml-fig-jp__strip" data-part="one-move">
          {strip('ml-fig-jp__f--val ml-fig-jp__f--ref', SIZES.reference, <span>ref<small>{SIZES.reference} B</small></span>)}
          <span className="ml-fig-jp__arrow" aria-hidden="true">→</span>
          {strip('ml-fig-jp__f--hdr', SIZES.objectHeader, <span>{t('fig.javaplain.part.header')}<small>{SIZES.objectHeader} B</small></span>)}
          {strip('ml-fig-jp__f--val', 4, <span>{from}<small>4 B</small></span>)}
          {strip('ml-fig-jp__f--val', 4, <span>{to}<small>4 B</small></span>)}
          {strip('ml-fig-jp__f--pad', pad, <span>·<small>{pad} B</small></span>)}
        </div>
        <p className="ml-fig-jp__eq">
          {t('fig.javaplain.move.objects.sum')} {SIZES.reference} + {SIZES.moveObject} = <b>{SIZES.reference + SIZES.moveObject} B</b>
        </p>
        <MemoryMap data={objMap} label={f.plain('fig.javaplain.mem.objects.desc', { n: N, bytes: n(oc.bytes), lines: oc.lines })} />
        <PlainReadout
          cells={[
            { key: 'fig.javaplain.read.alloc', label: t('fig.javaplain.read.alloc'), value: n(oc.allocations) },
            { key: 'fig.javaplain.read.bytes', label: t('fig.javaplain.read.bytes'), value: n(oc.bytes) },
            { key: 'fig.javaplain.read.lines', label: t('fig.javaplain.read.lines'), value: `≥ ${n(oc.lines)}` },
          ]}
        />
      </>
    ),
    primitives: (
      <>
        <p className="ml-fig-cmp__code">int move = from | (to &lt;&lt; 8) · int[]</p>
        <div className="ml-fig-jp__strip" data-part="one-move">
          {strip('ml-fig-jp__f--pad', 16, <span>0<small>16 bit</small></span>)}
          {strip('ml-fig-jp__f--val', 8, <span>{to}<small>8 bit</small></span>)}
          {strip('ml-fig-jp__f--val', 8, <span>{from}<small>8 bit</small></span>)}
        </div>
        <p className="ml-fig-jp__eq ml-fig-jp__eq--code">
          {from} | ({to} &lt;&lt; 8) = {m} = 0x{hex4(m)}
        </p>
        <p className="ml-fig-jp__eq">
          {t('fig.javaplain.move.primitives.sum')} <b>4 B</b>
        </p>
        <MemoryMap data={primMap} label={f.plain('fig.javaplain.mem.primitives.desc', { n: N, bytes: n(pc.bytes), lines: pc.lines })} />
        <PlainReadout
          cells={[
            { key: 'fig.javaplain.read.alloc', label: t('fig.javaplain.read.alloc'), value: n(pc.allocations) },
            { key: 'fig.javaplain.read.bytes', label: t('fig.javaplain.read.bytes'), value: n(pc.bytes) },
            { key: 'fig.javaplain.read.lines', label: t('fig.javaplain.read.lines'), value: `≥ ${n(pc.lines)}` },
          ]}
        />
      </>
    ),
  };
}

const UNDO_ROWS: readonly { name: string; type: string; field: 'hash' | 'move' | 'mover' | 'mask' }[] = [
  { name: 'hashes', type: 'long[]', field: 'hash' },
  { name: 'moves', type: 'int[]', field: 'move' },
  { name: 'movers', type: 'int[]', field: 'mover' },
  { name: 'masksBefore', type: 'int[]', field: 'mask' },
];

function undoBodies({ f, step }: { f: FigText; step: number }): Bodies {
  const { t, n } = f;
  const w = walkAt(step);
  const cols = Array.from({ length: SLOTS }, (_, i) => i);
  const live = w.objects.filter((o) => o.live);
  const dead = w.objects.filter((o) => !o.live);
  const pile = (list: typeof live, kind: 'live' | 'dead'): React.JSX.Element[] =>
    cols.map((i) => {
      const o = list[i];
      if (!o) {
        return (
          <li key={i} className="ml-fig-jp__obj" data-state="empty" aria-hidden="true">
            ·
          </li>
        );
      }
      return (
        <li key={i} className={`ml-fig-jp__obj is-${kind}${o.fresh ? ' is-new' : ''}`} data-obj={o.id} data-state={kind}>
          Undo #{o.id} · {SIZES.undoObject} B
        </li>
      );
    });
  const cell = (slot: number, field: (typeof UNDO_ROWS)[number]['field']): React.JSX.Element => {
    const s = w.slots[slot];
    const state: CellState = s ? s.state : 'empty';
    return (
      <td key={slot} className={s ? `is-${s.state}` : undefined} data-slot={slot} data-state={state}>
        {s ? String(s[field]) : '·'}
        <span className="ml-visually-hidden">, {stateWord(f, state)}</span>
      </td>
    );
  };
  return {
    objects: (
      <>
        <p className="ml-fig-cmp__code">record Undo(long hash, int move, int mover, int maskBefore) · Deque&lt;Undo&gt;</p>
        <div className="ml-fig-jp__heap">
          <div>
            <div className="ml-fig-jp__pilehead">{t('fig.javaplain.undo.stack')}</div>
            <ul className="ml-fig-jp__pile" data-pile="live">
              {pile(live, 'live')}
            </ul>
          </div>
          <div>
            <div className="ml-fig-jp__pilehead">{t('fig.javaplain.undo.garbage')}</div>
            <ul className="ml-fig-jp__pile" data-pile="dead">
              {pile(dead, 'dead')}
            </ul>
          </div>
        </div>
        <PlainReadout
          cells={[
            { key: 'fig.javaplain.read.alloc', label: t('fig.javaplain.read.alloc'), value: n(w.allocations) },
            { key: 'fig.javaplain.read.bytes.alloc', label: t('fig.javaplain.read.bytes.alloc'), value: n(bytesAllocated(w)) },
            { key: 'fig.javaplain.read.garbage', label: t('fig.javaplain.read.garbage'), value: n(dead.length) },
          ]}
        />
      </>
    ),
    primitives: (
      <>
        <table className="ml-fig-jp__arr">
          <caption className="ml-visually-hidden">{t('fig.javaplain.undo.table.caption')}</caption>
          <thead>
            <tr>
              <th scope="col">{t('fig.javaplain.undo.index')}</th>
              {cols.map((c) => (
                <th key={c} scope="col" className={c === w.depth ? 'is-ply' : undefined} {...(c === w.depth ? { 'data-ply': 'now' } : {})}>
                  {ROOT_PLY + c}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {UNDO_ROWS.map((r) => (
              <tr key={r.name}>
                <th scope="row">
                  {r.name} <span className="ml-fig-jp__arrtype">{r.type}</span>
                </th>
                {cols.map((c) => cell(c, r.field))}
              </tr>
            ))}
          </tbody>
        </table>
        <PlainReadout
          cells={[
            { key: 'fig.javaplain.read.alloc', label: t('fig.javaplain.read.alloc'), value: n(0) },
            { key: 'fig.javaplain.read.bytes.alloc', label: t('fig.javaplain.read.bytes.alloc'), value: n(0) },
            { key: 'fig.javaplain.read.garbage', label: t('fig.javaplain.read.garbage'), value: n(0) },
          ]}
        />
      </>
    ),
  };
}

function PieceRows({ f, chips, mode, mover }: { f: FigText; chips: [Chip[], Chip[]]; mode: 'read' | 'changed' | 'none'; mover: number }): React.JSX.Element {
  return (
    <>
      {chips.map((side, p) => (
        <div key={p} className="ml-fig-jp__side" data-side={p}>
          <span>{f.t(p === 0 ? 'fig.javaplain.eval.side.a' : 'fig.javaplain.eval.side.b')}</span>
          {side.map((c, i) => {
            const cls = mode === 'read' ? ' is-read' : mode === 'changed' && c.changed && p === mover ? ' is-changed' : '';
            return (
              <span key={i} className={`ml-fig-jp__pc${cls}`} data-cell={c.cell} {...(cls ? { 'data-state': cls.trim().slice(3) } : {})}>
                {c.dist}
              </span>
            );
          })}
        </div>
      ))}
    </>
  );
}

function evalBodies({ f, step }: { f: FigText; step: number }): Bodies {
  const { t, n } = f;
  const w = walkAt(step);
  const { sides, mover } = piecesAt(step);
  const sums = sumsAt(step);
  const full = fullEvalAt(step);
  const line = updateLineAt(step);
  const sideName = (p: number): string => t(p === 0 ? 'fig.javaplain.eval.side.a' : 'fig.javaplain.eval.side.b');
  const frames = Array.from({ length: SLOTS }, (_, i) => {
    const s = w.slots[i];
    const state: CellState = s ? s.state : 'empty';
    return (
      <span key={i} className={`ml-fig-jp__frame${s ? ` is-${s.state}` : ''}`} data-frame={i} data-state={state}>
        {s ? `${s.sums[0]} · ${s.sums[1]}` : '·'}
        <span className="ml-visually-hidden">, {stateWord(f, state)}</span>
      </span>
    );
  });
  const update =
    line.kind === 'idle' ? (
      t('fig.javaplain.eval.incr.idle')
    ) : line.kind === 'update' ? (
      <>
        sum[{sideName(line.side)}] += {line.to} − {line.from} → <b>{line.sum}</b>
      </>
    ) : (
      <>
        {t('fig.javaplain.eval.incr.restore')} <b>{line.sums[0]}</b> · <b>{line.sums[1]}</b>
      </>
    );
  return {
    objects: (
      <>
        <p className="ml-fig-cmp__code">dsum = Σ distance(piece)</p>
        <PieceRows f={f} chips={sides} mode={full.computed ? 'read' : 'none'} mover={mover} />
        <p className="ml-fig-jp__eq">
          {full.computed ? (
            <>
              {t('fig.javaplain.eval.full.now')} <b>{sums[0]}</b> · <b>{sums[1]}</b>
            </>
          ) : (
            t('fig.javaplain.eval.full.idle')
          )}
        </p>
        <PlainReadout
          cells={[
            { key: 'fig.javaplain.read.dsum.a', label: t('fig.javaplain.read.dsum.a'), value: full.computed ? n(sums[0]) : DASH },
            { key: 'fig.javaplain.read.dsum.b', label: t('fig.javaplain.read.dsum.b'), value: full.computed ? n(sums[1]) : DASH },
            { key: 'fig.javaplain.read.pieces', label: t('fig.javaplain.read.pieces'), value: n(full.piecesRead) },
          ]}
        />
      </>
    ),
    primitives: (
      <>
        <p className="ml-fig-cmp__code">sum[p] += dist[to] − dist[from]</p>
        <PieceRows f={f} chips={sides} mode="changed" mover={mover} />
        <p className="ml-fig-jp__eq">{update}</p>
        <div className="ml-fig-jp__frames" title={`${FRAME_INTS} int`}>
          <span>{t('fig.javaplain.eval.frames')}</span>
          {frames}
        </div>
        <PlainReadout
          cells={[
            { key: 'fig.javaplain.read.dsum.a', label: t('fig.javaplain.read.dsum.a'), value: n(sums[0]) },
            { key: 'fig.javaplain.read.dsum.b', label: t('fig.javaplain.read.dsum.b'), value: n(sums[1]) },
            { key: 'fig.javaplain.read.pieces', label: t('fig.javaplain.read.pieces'), value: n(w.updates) },
          ]}
        />
      </>
    ),
  };
}

// ---------- the figure ----------

export function JavaPlainFigure({ catalog, number, initialView = 'move', initialPick = 1 }: JavaPlainFigureProps): React.JSX.Element {
  const f = useFigText(catalog);
  const { t, kit, n } = f;
  const [view, setView] = useState<PlainView>(initialView);
  const [pick, setPick] = useState(Math.min(N, Math.max(1, initialPick)));
  const { state, dispatch } = useStepper(LAST);
  const step = state.step;
  const holder = useRef<HTMLDivElement>(null);

  // Test hooks on the figure element (the kit Figure has no attribute slot): `#fig-javaplain[data-view][data-step][data-pick]`.
  useEffect(() => {
    const el = holder.current?.closest('figure');
    if (!el) return;
    el.dataset.view = view;
    el.dataset.step = String(step);
    el.dataset.pick = String(pick);
    // `[data-narr="<key>"]` marks the visible narration (the live line, not its ghosts).
    const line = el.querySelector<HTMLElement>('.ml-fig__status-line:not(.ml-fig__status-line--ghost)');
    if (line) line.dataset.narr = narrationKey(view, step);
  }, [view, step, pick]);

  const stepping = view !== 'move';
  const bodies = view === 'move' ? moveBodies({ f, pick }) : view === 'undo' ? undoBodies({ f, step }) : evalBodies({ f, step });
  const moveText = moveName(moveAtPick(pick));

  // The narration: one sentence per state; every sentence of the catalog (both views, every state, the longest move name) stands as a hidden ghost
  // in the same grid cell, so that the figure does not jump when the view or the step changes.
  const stepLabel = (k: number): string => kit('kit.stepper.step', { k: k + 1, n: STEP_COUNT });
  const sentence = (v: PlainView, s: number, p: number, name?: string): string => {
    const values = narrationValues(v, s, p);
    return f.plain(narrationKey(v, s), name === undefined ? values : { ...values, move: name });
  };
  const all: StatusLine[] = [{ text: sentence('move', 0, N, LONGEST_MOVE_NAME) }];
  const seen = new Set<string>();
  for (const v of ['undo', 'eval'] as const) {
    for (let s = 0; s <= LAST; s++) {
      for (const name of [undefined, LONGEST_MOVE_NAME]) {
        const line = { step: stepLabel(s), text: sentence(v, s, pick, name) };
        const key = `${line.step}|${line.text}`;
        if (!seen.has(key)) {
          seen.add(key);
          all.push(line);
        }
      }
    }
  }
  const status = {
    ...(stepping ? { step: stepLabel(step) } : {}),
    text: sentence(view, step, pick),
    all,
    // The slider changes the move at every key press; it does not write into the live region (its value text names the move).
    live: stepping && !state.playing,
  };

  const onHome = (e: KeyboardEvent<HTMLDivElement>): void => {
    // Step 0 (the root) is a state of this walk: Home goes there, not to step 1 as in the kit.
    if (e.key !== 'Home' || e.altKey || e.ctrlKey || e.metaKey) return;
    e.preventDefault();
    e.stopPropagation();
    dispatch({ type: 'set', step: 0 });
  };

  const eval_ = view === 'eval';
  const measured = (
    <dl className="ml-fig-readout ml-fig-jp__measured">
      <div className="ml-fig-readout__cell" data-kind="measured">
        <dt>{t('fig.javaplain.measured.engine.label')}</dt>
        <dd>
          <span className="ml-fig-readout__value">{n(1.6)} M</span>
          <small>{t('fig.javaplain.measured.engine.note')}</small>
        </dd>
      </div>
      {eval_ ? (
        <div className="ml-fig-readout__cell" data-kind="measured">
          <dt>{t('fig.javaplain.measured.eval.label')}</dt>
          <dd>
            <span className="ml-fig-readout__value">+{n(30)} %</span>
            <small>{t('fig.javaplain.measured.eval.note')}</small>
          </dd>
        </div>
      ) : (
        <div className="ml-fig-readout__cell" data-kind="none">
          <dt>{t('fig.javaplain.measured.none.label')}</dt>
          <dd>
            <span className="ml-fig-readout__value">{t('fig.javaplain.measured.none.value')}</span>
            <small>{t('fig.javaplain.measured.none.note')}</small>
          </dd>
        </div>
      )}
    </dl>
  );

  return (
    <Figure
      id="fig-javaplain"
      labelWord={figLabel(kit, number[0], number[1])}
      title={t('fig.javaplain.title')}
      status={status}
      panel={
        <Manipulate>
          <Segmented<PlainView>
            label={t('fig.javaplain.view.label')}
            options={VIEWS.map((v) => ({ value: v, label: t(`fig.javaplain.view.${v}`) }))}
            value={view}
            onChange={(v) => {
              dispatch({ type: 'pause' });
              setView(v);
            }}
          />
          {/* The slider and the stepper share one grid cell; the one that is not in use is inert and hidden, so the stage starts at the same height in every view. */}
          <div className="ml-fig-jp__ctls">
            <div className="ml-fig-jp__ctl" data-ctl="pick" inert={stepping}>
              <Slider
                label={t('fig.javaplain.pick.label')}
                min={1}
                max={N}
                value={pick}
                onChange={setPick}
                valueText={t('fig.javaplain.pick.value', { k: pick, n: N, move: moveText })}
                out={`${pick} / ${N} · ${moveText}`}
              />
            </div>
            <div className="ml-fig-jp__ctl" data-ctl="step" inert={!stepping} onKeyDownCapture={onHome}>
              <Stepper state={state} dispatch={dispatch} labels={{ ...stepperLabels(f.kitT, kit('kit.stepper.position')), positionText: `${step + 1} / ${STEP_COUNT}` }} />
            </div>
          </div>
        </Manipulate>
      }
      caption={<Markup text={t('fig.javaplain.caption')} />}
      foot={
        <FigFoot
          kit={kit}
          paths={[
            'engine/src/main/java/sternhalma/engine/Move.java',
            'engine/src/main/java/sternhalma/engine/Position.java',
            'search/src/main/java/sternhalma/search/IncrementalLinear.java',
            'docs/cpp-comparison.md',
            'docs/search-and-evaluation.md',
          ]}
          data="java-plain.json"
        />
      }
    >
      <div ref={holder} className="ml-fig-jp" data-view={view}>
        <div className="ml-fig-cmp">
          <Lane lane="objects" title={t(eval_ ? 'fig.javaplain.lane.full' : 'fig.javaplain.lane.objects')} tag={t(eval_ ? 'fig.javaplain.lane.full.tag' : 'fig.javaplain.lane.objects.tag')}>
            {bodies.objects}
          </Lane>
          <Lane lane="primitives" ours title={t(eval_ ? 'fig.javaplain.lane.incremental' : 'fig.javaplain.lane.primitives')} tag={t(eval_ ? 'fig.javaplain.lane.incremental.tag' : 'fig.javaplain.lane.primitives.tag')}>
            {bodies.primitives}
          </Lane>
        </div>
        <Legend>
          {LEGEND[view].map(([sw, k]) => (
            <LegendItem key={k} mark={<SwatchMark kind={sw} />}>
              {t(`fig.javaplain.${k}`)}
            </LegendItem>
          ))}
        </Legend>
        {measured}
      </div>
    </Figure>
  );
}
