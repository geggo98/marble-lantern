// V5: the alpha-beta search at depth 2, one step per root move. Two examples (a mid-game position with 67 root moves and the start position
// with 14), an overview of all root moves with the bound alpha as a line, a close view of the current root move with a gauge, a reply strip
// and a board, and a counter that compares depth 2 with depth 2. Design: v5-mid-spec.md; model: ab-model.ts.
import { useEffect, useId, useReducer, useRef, useState } from 'react';
import {
  Figure,
  Legend,
  LegendItem,
  Manipulate,
  MiniBoard,
  PLOT_BREAKPOINT_PX,
  Segmented,
  Stepper,
  arrowGeometry,
  initialStepper,
  stepperLabels,
  stepperReducer,
  useMeasuredWidth,
} from '../kit';
import type { StatusLine } from '../kit/Figure';
import { Markup } from '../kit/markup';
import { cellPoint } from '../../ui/board-model';
import { useMediaQuery } from '../../ui/useMedia';
import midRaw from '../data/ab-tree-mid.json';
import startRaw from '../data/ab-tree-start.json';
import { FigFoot, figLabel, useFigText } from './common';
import type { Catalog, Lookup } from './common';
import {
  NEG_INF,
  alphaAt,
  alphaPaths,
  bestRoot,
  decidingReply,
  gaugeAlpha,
  gaugeLayout,
  gaugeTicks,
  moveAt,
  narration,
  neighbourNotable,
  overviewLayout,
  pluralForm,
  replyChain,
  replyStrip,
  rootName,
  scoreText,
  stepCount,
  stepDelayMs,
  valueCell,
  verdictAt,
  visitedAt,
} from './ab-model';
import type { AbData, GaugeLayout, OverviewLayout, Verdict } from './ab-model';

export const AB_MID = midRaw as unknown as AbData;
export const AB_START = startRaw as unknown as AbData;

export type AbExample = 'mid' | 'start';
const EXAMPLES: Record<AbExample, { data: AbData; file: string }> = {
  mid: { data: AB_MID, file: 'ab-tree-mid.json' },
  start: { data: AB_START, file: 'ab-tree-start.json' },
};

/** The event that the links in the text sections send: the figure selects the example and goes to step 0. */
export const AB_EXAMPLE_EVENT = 'ml-ab-example';

export interface AlphaBetaFigureProps {
  catalog: Catalog;
  number: readonly [number, number];
}

const r2 = (v: number): number => Math.round(v * 100) / 100;
const cellsOf = (data: AbData, owner: number): number[] => data.cells.flatMap((c, i) => (c === owner ? [i] : []));

/** The state of the stepper plus the clock of the playback: 1.2 s on a notable step and 0.25 s on any other one (spec, section 6). */
function useAbStepper(data: AbData) {
  const reduced = useMediaQuery('(prefers-reduced-motion: reduce)', false);
  const [state, dispatch] = useReducer(stepperReducer, undefined, () => initialStepper(stepCount(data), !reduced));
  useEffect(() => dispatch({ type: 'canPlay', value: !reduced }), [reduced]);
  const { playing, step } = state;
  /** True when the last step came from the clock of the playback and not from a hand (the announcement tells the two apart). */
  const ticked = useRef(false);
  useEffect(() => {
    if (!playing) return undefined;
    const id = setTimeout(() => {
      ticked.current = true;
      dispatch({ type: 'tick' });
    }, stepDelayMs(data, step));
    return () => clearTimeout(id);
  }, [playing, step, data]);
  return { state, dispatch, ticked };
}

export function AlphaBetaFigure({ catalog, number }: AlphaBetaFigureProps): React.JSX.Element {
  const f = useFigText(catalog);
  const { t, kit, n, language } = f;
  const [example, setExample] = useState<AbExample>('mid');
  const data = EXAMPLES[example].data;
  const { state, dispatch, ticked } = useAbStepper(data);
  const step = state.step;
  const holder = useRef<HTMLDivElement>(null);
  const width = useMeasuredWidth(holder, '.ml-fig');
  const compact = width > 0 && width < PLOT_BREAKPOINT_PX;
  const hatch = `ml-ab2-hatch-${useId()}`;
  const total = stepCount(data);
  const moves = data.rootMoves.length;

  /** Switching the example stops the playback and goes to step 0 (spec, section 7). */
  const select = (next: AbExample): void => {
    dispatch({ type: 'reset' });
    dispatch({ type: 'count', value: stepCount(EXAMPLES[next].data) });
    setExample(next);
  };
  const selectRef = useRef(select);
  selectRef.current = select;
  useEffect(() => {
    const onExample = (event: Event): void => {
      const value = (event as CustomEvent<AbExample>).detail;
      if (value === 'mid' || value === 'start') selectRef.current(value);
    };
    window.addEventListener(AB_EXAMPLE_EVENT, onExample);
    return () => window.removeEventListener(AB_EXAMPLE_EVENT, onExample);
  }, []);

  // Page Up and Page Down on the stepper: the previous and the next notable step. A listener on the figure keeps the kit's stepper as it is.
  const dataRef = useRef(data);
  dataRef.current = data;
  const stepRef = useRef(step);
  stepRef.current = step;
  useEffect(() => {
    const figure = holder.current?.closest('figure');
    if (!figure) return undefined;
    const onKey = (event: KeyboardEvent): void => {
      if ((event.key !== 'PageUp' && event.key !== 'PageDown') || event.altKey || event.ctrlKey || event.metaKey) return;
      if (!(event.target instanceof Element) || !event.target.closest('.ml-fig-stepper')) return;
      event.preventDefault();
      dispatch({ type: 'set', step: neighbourNotable(dataRef.current, stepRef.current, event.key === 'PageUp' ? -1 : 1) });
    };
    figure.addEventListener('keydown', onKey);
    return () => figure.removeEventListener('keydown', onKey);
  }, [dispatch]);

  // While the playback runs the narration is not announced (the live prop of the status is false); when it stops, the current step is announced
  // once (spec, section 6): a pause by the pause button, or the end that the clock of the playback reached. A jump by hand during the playback
  // (End, the scrubber) is announced by the narration itself, so the announcement below stays silent then, else it would be read twice.
  const [announce, setAnnounce] = useState('');
  const previous = useRef({ playing: false, step });
  const nar = narration(data, step, n);
  const narText = f.plain(`fig.ab.narr.${nar.key}`, nar.values);
  useEffect(() => {
    const was = previous.current;
    const byClock = ticked.current;
    ticked.current = false;
    if (was.playing && !state.playing && (step === was.step || (step === total && byClock))) setAnnounce(narText);
    else if (state.playing || was.step !== step) setAnnounce('');
    previous.current = { playing: state.playing, step };
  }, [state.playing, step, total, narText, ticked]);

  const move = moveAt(data, step);
  const verdict = verdictAt(data, step);
  const alpha = alphaAt(data, step);
  const visited = visitedAt(data, step);
  const minimax = data.meta.minimaxNodes;
  const fullCount = data.rootMoves.filter((m) => !m.cutoff).length;
  const verdictText = (v: Verdict): string => t(`fig.ab.verdict.${v}`);
  const best = bestRoot(data);
  const shown = move ?? (step > moves ? best : null);
  const O = overviewLayout(data, compact);
  const G = gaugeLayout(data, compact);

  // Every narration of the example in this language (every step), so that the status area keeps the height of the longest one (kit: status.all).
  const stepLabel = (k: number): string => f.kit('kit.stepper.step', { k, n: total });
  const allNarrations: StatusLine[] = [];
  const seenText = new Set<string>();
  for (let k = 0; k <= total; k++) {
    const nk = narration(data, k, n);
    const line = { step: stepLabel(k), text: f.plain(`fig.ab.narr.${nk.key}`, nk.values) };
    const key = `${line.step.length}|${line.text}`;
    if (!seenText.has(key)) {
      seenText.add(key);
      allNarrations.push(line);
    }
  }
  const status = { step: stepLabel(step), text: narText, all: allNarrations, live: !state.playing };
  const sideA = cellsOf(data, 0);
  const sideB = cellsOf(data, 1);

  return (
    <Figure
      id="fig-ab"
      labelWord={figLabel(kit, number[0], number[1])}
      title={t('fig.ab.title')}
      status={status}
      panel={
        <Manipulate>
          <Segmented
            label={t('fig.ab.example.label')}
            value={example}
            onChange={select}
            options={[
              { value: 'mid', label: t('fig.ab.example.mid', { ply: AB_MID.meta.ply }) },
              { value: 'start', label: t('fig.ab.example.start') },
            ]}
          />
          <Stepper state={state} dispatch={dispatch} labels={stepperLabels(f.kitT, t('fig.ab.stepper'))} />
        </Manipulate>
      }
      caption={<Markup text={t(`fig.ab.caption.${example}`)} />}
      foot={
        <>
          <FigFoot kit={kit} paths={['search/src/main/java/sternhalma/search/AlphaBeta.java', 'search/src/main/java/sternhalma/search/Evaluators.java']} data={EXAMPLES[example].file} />
          <span className="ml-visually-hidden" role="status" aria-live="polite" data-ab-announce="">
            {announce}
          </span>
        </>
      }
    >
      <div ref={holder} className="ml-fig-ab2" data-example={example}>
        <svg className="ml-fig-ab2__defs" width="0" height="0" aria-hidden="true" focusable="false">
          <defs>
            <pattern id={hatch} width="4" height="4" patternUnits="userSpaceOnUse" patternTransform="rotate(45)">
              <line className="ml-ab2-hatchline" x1="0" y1="0" x2="0" y2="4" />
            </pattern>
          </defs>
        </svg>

        <dl className="ml-fig-ab2__read">
          <div title={t('fig.ab.readout.alpha')}>
            <dt>
              <span className="ml-fig-ab2__sym">α</span>
              {t('fig.ab.readout.alpha')}
            </dt>
            <dd data-readout="alpha">{scoreText(alpha)}</dd>
          </div>
          <div title={t('fig.ab.readout.move')}>
            <dt>{t('fig.ab.readout.move')}</dt>
            <dd data-readout="move">{valueCell(data, step)}</dd>
          </div>
          <div title={t('fig.ab.readout.beta')}>
            <dt>
              <span className="ml-fig-ab2__sym">β</span>
              {t('fig.ab.readout.beta')}
            </dt>
            <dd data-readout="beta">{scoreText('+infinity')}</dd>
          </div>
        </dl>

        <div className="ml-fig-ab2__over">
          <Overview
            data={data}
            step={step}
            L={O}
            hatch={hatch}
            title={t('fig.ab.over.title')}
            desc={f.plain('fig.ab.over.desc', { n: n(moves), full: n(fullCount), cut: n(moves - fullCount), visited: n(data.meta.iterationNodes[1]), total: n(minimax) })}
          />
          <input
            className="ml-fig-ab2__scrub"
            type="range"
            min={0}
            max={total}
            step={1}
            value={step}
            aria-label={t('fig.ab.scrub.label')}
            aria-valuetext={t('fig.ab.scrub.value', { k: step, n: total, move: shown ? rootName(shown) : '–', verdict: verdict ? verdictText(verdict) : '–' })}
            onChange={(e) => dispatch({ type: 'set', step: Number(e.currentTarget.value) })}
          />
          <div className="ml-fig-ab2__count">
            <span>{t('fig.ab.counter.label')}</span>
            <output data-counter="">{t('fig.ab.counter', { visited: n(visited), total: n(minimax) })}</output>
            <div className="ml-fig-ab2__bar" aria-hidden="true">
              <i style={{ width: `${((visited / minimax) * 100).toFixed(2)}%` }} />
            </div>
          </div>
          <Legend>
            <LegendItem mark={<path className="ml-ab2-alpha" d="M2 12H14V5H30" />}>{t('fig.ab.legend.alpha')}</LegendItem>
            <LegendItem mark={<circle className="ml-ab2-exact" cx={16} cy={8} r={4} />}>{t('fig.ab.legend.exact')}</LegendItem>
            <LegendItem mark={<path className="ml-ab2-bound" d="M10 5h12M16 5v8" />}>{t('fig.ab.legend.bound')}</LegendItem>
            <LegendItem mark={<rect className="ml-ab2-done" x={8} y={3} width={16} height={10} />}>{t('fig.ab.legend.searched')}</LegendItem>
            <LegendItem mark={<rect className="ml-ab2-skip" style={{ fill: `url(#${hatch})` }} x={8} y={3} width={16} height={10} />}>{t('fig.ab.legend.skipped')}</LegendItem>
            <LegendItem mark={<rect className="ml-ab2-done is-cur" x={8} y={3} width={16} height={10} />}>{t('fig.ab.legend.current')}</LegendItem>
          </Legend>
        </div>

        <div className="ml-fig-ab2__lower">
          <section className="ml-fig-ab2__focus" aria-label={t('fig.ab.focus.label')}>
            <div className="ml-fig-ab2__move">
              <span className="ml-fig-ab2__rank" data-focus="rank">
                {move ? `${step} / ${moves}` : `– / ${moves}`}
              </span>
              <span className="ml-fig-ab2__san" data-focus="move">
                {move ? rootName(move) : step > moves ? rootName(best) : '–'}
              </span>
              <span className="ml-fig-ab2__verdict ml-fig-ab2__stack">
                {(['first', 'best', 'cut', 'result'] as const).map((v) => (
                  <span key={v} className="ml-fig-ab2__tag" data-kind={v === 'first' || v === 'result' ? 'best' : v} data-v={v} {...(verdict === v ? {} : { 'data-off': '', 'aria-hidden': true })}>
                    {verdictText(v)}
                  </span>
                ))}
              </span>
            </div>
            <Gauge
              data={data}
              step={step}
              G={G}
              hatch={hatch}
              title={t('fig.ab.gauge.title')}
              desc={f.plain('fig.ab.gauge.desc', { move: move ? rootName(move) : '–', alpha: scoreText(gaugeAlpha(data, step)), value: move ? scoreText(move.value) : '–' })}
            />
            <div className="ml-fig-ab2__strip" aria-hidden="true" data-strip="">
              {replyStrip(data, step).map((s, i) => (
                <i key={i} data-s={s} />
              ))}
            </div>
            <p className="ml-fig-ab2__tally">
              <span>
                <b data-tally="searched">{move ? move.searched.length : '–'}</b> <PluralLabel t={t} base="fig.ab.tally.searched" form={pluralForm(language, move ? move.searched.length : 2)} />
              </span>
              <span>
                <b data-tally="skipped">{move ? move.skipped : '–'}</b> <PluralLabel t={t} base="fig.ab.tally.skipped" form={pluralForm(language, move ? move.skipped : 2)} />
              </span>
            </p>
          </section>
          <div className="ml-fig-ab2__board">
            <MiniBoard title={t('fig.ab.board.title')} desc={f.plain('fig.ab.board.desc')} pieces={{ a: sideA, b: sideB }} {...(shown ? { fromRings: [shown.from] } : {})}>
              {shown && (
                <g data-chains="">
                  <Chain cells={shown.path} />
                  <Chain cells={replyChain(shown)} reply />
                </g>
              )}
            </MiniBoard>
            <p>{kit('kit.board.orientation', { top: f.ui('side_a'), bottom: f.ui('side_b') })}</p>
          </div>
        </div>
      </div>
    </Figure>
  );
}

/**
 * A label with a singular and a plural form ("reply searched", "replies searched"). Both forms stand in one grid cell and the inactive one
 * is hidden, so the label keeps the width of the longer form and the tally does not wrap differently from step to step (spec, section 10).
 */
function PluralLabel({ t, base, form }: { t: Lookup; base: string; form: 'one' | 'other' }): React.JSX.Element {
  return (
    <span className="ml-fig-ab2__stack ml-fig-ab2__plural">
      {(['one', 'other'] as const).map((f) => (
        <span key={f} {...(f === form ? {} : { 'data-off': '', 'aria-hidden': true })}>
          {t(`${base}.${f}`)}
        </span>
      ))}
    </span>
  );
}

/** The path of a move on the board along its landing cells, ending in an arrow head: solid for the root move, dashed for the reply. */
function Chain({ cells, reply }: { cells: readonly number[]; reply?: boolean }): React.JSX.Element | null {
  if (cells.length < 2) return null;
  const last = cells.length - 1;
  const arrow = arrowGeometry({ from: cells[last - 1]!, to: cells[last]! });
  const lead = cells.slice(0, last).map((c) => cellPoint(c, false));
  const d = `${lead.map((p, i) => `${i === 0 ? 'M' : 'L'}${p.x.toFixed(1)} ${p.y.toFixed(1)}`).join('')}L${arrow.end.x.toFixed(1)} ${arrow.end.y.toFixed(1)}`;
  return (
    <g className={reply ? 'ml-ab2-chain ml-ab2-chain--reply' : 'ml-ab2-chain'} data-chain={reply ? 'reply' : 'move'}>
      <path className="ml-ab2-chain__line" d={d} />
      <path className="ml-ab2-chain__head" d={arrow.head} />
    </g>
  );
}

interface OverviewProps {
  data: AbData;
  step: number;
  L: OverviewLayout;
  hatch: string;
  title: string;
  desc: string;
}

/** The overview: the value band (alpha as a staircase line, a disc for an exact value, a cap with a stem for a bound) above the work band. */
function Overview({ data, step, L, hatch, title, desc }: OverviewProps): React.JSX.Element {
  const id = useId();
  const moves = data.rootMoves;
  const n = moves.length;
  const done = Math.min(step, n);
  const cur = step >= 1 && step <= n ? step - 1 : -1;
  const alpha = alphaPaths(data, done, L);
  const curW = Math.max(L.pitch, 6);
  const tickY = (v: number): number => r2(L.y(v) + 4);
  return (
    <div data-p="over">
      <svg viewBox={`0 0 ${L.width} ${r2(L.height)}`} role="img" aria-labelledby={`${id}-t ${id}-d`} className="ml-fig-ab2__overview">
        <title id={`${id}-t`}>{title}</title>
        <desc id={`${id}-d`}>{desc}</desc>
        {cur >= 0 && <rect className="ml-ab2-cur" x={r2(L.x(cur) - curW / 2)} y={L.top - 3} width={r2(curW)} height={r2(L.workTop + L.workH - L.top + 6)} rx={1.5} />}
        <rect className="ml-ab2-frame" x={L.gutter} y={L.top} width={L.width - L.gutter - 1} height={L.valueH} />
        <text x={L.gutter - 4} y={tickY(L.vMax)} textAnchor="end">
          {scoreText(L.vMax)}
        </text>
        <text x={L.gutter - 4} y={tickY(L.vMin)} textAnchor="end">
          {scoreText(L.vMin)}
        </text>
        <line className="ml-ab2-grid" x1={L.gutter} x2={L.width - 1} y1={L.workTop - 0.5} y2={L.workTop - 0.5} />
        <text x={L.gutter - 4} y={L.workTop + 9} textAnchor="end">
          1
        </text>
        <text x={L.gutter - 4} y={r2(L.workTop + L.workH)} textAnchor="end">
          {L.maxReplies}
        </text>
        {moves.map((m, i) => {
          const x0 = r2(L.x(i) - L.barW / 2);
          const w = r2(L.barW);
          if (i >= done) return <rect key={i} className="ml-ab2-todo" x={x0} y={L.workTop} width={w} height={1.5} data-col={i} />;
          const searchedH = Math.max(m.searched.length * L.unit, L.minBlock);
          const totalH = m.replies * L.unit;
          const isCur = i === cur ? ' is-cur' : '';
          return (
            <g key={i} data-col={i} data-state={i === cur ? 'current' : 'done'}>
              {m.skipped > 0 && (
                <rect
                  className={`ml-ab2-skip${L.barW < 4 ? ' ml-ab2-skip--thin' : ''}${isCur}`}
                  {...(L.barW < 4 ? {} : { style: { fill: `url(#${hatch})` } })}
                  x={x0}
                  y={r2(L.workTop + searchedH)}
                  width={w}
                  height={r2(totalH - searchedH)}
                />
              )}
              <rect className={`ml-ab2-done${isCur}`} x={x0} y={L.workTop} width={w} height={r2(searchedH)} />
            </g>
          );
        })}
        {alpha && (
          <>
            <path className="ml-ab2-alpha" d={alpha.solid} />
            {alpha.ahead && <path className="ml-ab2-alpha-ahead" d={alpha.ahead} />}
            <text className="ml-ab2-strong" x={alpha.label.x} y={r2(alpha.label.y)} textAnchor="end">
              {`α ${scoreText(alpha.label.value)}`}
            </text>
          </>
        )}
        {moves.map((m, i) => {
          if (i >= done) return null;
          const isCur = i === cur ? ' is-cur' : '';
          const cx = r2(L.x(i));
          const cy = r2(L.y(m.value));
          return m.cutoff ? (
            <path key={i} className={`ml-ab2-bound${isCur}`} data-mark="bound" d={`M${r2(L.x(i) - L.barW / 2)} ${cy}h${r2(L.barW)}M${cx} ${cy}v6`} />
          ) : (
            <circle key={i} className={`ml-ab2-exact${isCur}`} data-mark="exact" cx={cx} cy={cy} r={i === cur ? 4 : r2(Math.min(3.2, L.pitch * 0.4 + 1.2))} />
          );
        })}
        {cur >= 0 && <path className="ml-ab2-pointer" d={`M${r2(L.x(cur))} ${r2(L.workTop + L.workH + 4)}l5 8h-10z`} />}
      </svg>
    </div>
  );
}

interface GaugeProps {
  data: AbData;
  step: number;
  G: GaugeLayout;
  hatch: string;
  title: string;
  desc: string;
}

/** The gauge of the close view: the reply scores on a number line, the zone at and below alpha hatched, the deciding reply thick. */
function Gauge({ data, step, G, hatch, title, desc }: GaugeProps): React.JSX.Element {
  const id = useId();
  const m = moveAt(data, step);
  const aB = gaugeAlpha(data, step);
  const hasAlpha = aB !== NEG_INF;
  const min = m ? decidingReply(m)[3] : 0;
  const right = hasAlpha && G.x(aB) < G.width - 60;
  const arrowOn = Boolean(m?.newBest && hasAlpha);
  return (
    <div data-p="gauge">
      <svg viewBox={`0 0 ${G.width} ${G.height}`} role="img" aria-labelledby={`${id}-t ${id}-d`} className="ml-fig-ab2__gauge">
        <title id={`${id}-t`}>{title}</title>
        <desc id={`${id}-d`}>{desc}</desc>
        {hasAlpha && <rect className="ml-ab2-zone" style={{ fill: `url(#${hatch})` }} x={G.pad - 10} y={16} width={r2(G.x(aB) - G.pad + 10)} height={G.axisY - 16} />}
        <line className="ml-ab2-grid" x1={G.pad - 10} x2={G.width - G.pad + 10} y1={G.axisY} y2={G.axisY} />
        {gaugeTicks(G).map((tick) => (
          <g key={tick}>
            <line className="ml-ab2-grid" x1={r2(G.x(tick))} x2={r2(G.x(tick))} y1={G.axisY} y2={G.axisY + 4} />
            <text x={r2(G.x(tick))} y={G.axisY + 17} textAnchor="middle">
              {scoreText(tick)}
            </text>
          </g>
        ))}
        {m && (
          <>
            {m.searched.map((r, i) =>
              r[3] === min ? null : <line key={i} className="ml-ab2-tick" x1={r2(G.x(r[3]))} x2={r2(G.x(r[3]))} y1={26} y2={G.axisY} />,
            )}
            <line className="ml-ab2-tick is-min" x1={r2(G.x(min))} x2={r2(G.x(min))} y1={18} y2={G.axisY} />
            <path className="ml-ab2-pointer" d={`M${r2(G.x(min))} 18l4 -7h-8z`} />
          </>
        )}
        {arrowOn && hasAlpha && <path className="ml-ab2-arrow" d={`M${r2(G.x(aB) + 2)} 7H${r2(G.x(min) - 8)}m-5 -3.5l5 3.5l-5 3.5`} />}
        {hasAlpha && (
          <>
            <line className="ml-ab2-alpha" x1={r2(G.x(aB))} x2={r2(G.x(aB))} y1={4} y2={G.axisY} />
            <text className="ml-ab2-strong" x={r2(G.x(aB) + (right && !arrowOn ? 5 : -5))} y={arrowOn ? 24 : 13} textAnchor={right && !arrowOn ? 'start' : 'end'}>
              α
            </text>
          </>
        )}
      </svg>
    </div>
  );
}
