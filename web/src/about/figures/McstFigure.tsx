// V10: MCTS, one simulation at a time (Fig. 3.4). The tree, the root table and the PUCT column come from replaying mcts-mid.json
// (puct.ts); the inset chart from sweeps.json. Layout notes: web/design/about/about-notes.md, "V10".
import { useEffect, useRef, useState } from 'react';
import { isStep, notation } from '../../ui/board-model';
import { Figure, Legend, LegendItem, LiveSwitch, Manipulate, Plot, PlotDots, PlotRef, Segmented, Slider, Stepper, stepperLabels, useStepper } from '../kit';
import { Markup } from '../kit/markup';
import data from '../data/mcts-mid.json';
import sweeps from '../data/sweeps.json';
import { inferenceCatalog } from './catalogs';
import { parseSigned } from './numbers';
import { McstIcicle } from './McstIcicle';
import { SUBSTEPS, argmax, edgeQ, movesOf, puctColumn, rootState, visibleSims } from './puct';
import type { McstData, Substep } from './puct';
import { FootChips, useCompact, useFigText } from './shared';
import { FigFoot, originText } from './common';
import { liveBlockedReason } from '../live/classic';
import { useLive } from '../live/context';
import { ALL_KS, MODEL_ID, liveRootState, liveTable, runMcts } from '../live/mcts';
import type { McstLive } from '../live/mcts';
import { liveStatusText, liveValue } from '../live/status';
import { useLiveRun } from '../live/useLiveRun';

const D = data as unknown as McstData;
const EXAMPLE_PLY = (data as unknown as { meta: { ply: number } }).meta.ply;
const glyphMark = (g: string): React.JSX.Element => (
  <text x={16} y={8} textAnchor="middle" dominantBaseline="central" fontSize={16} fontWeight={700} fill="currentColor">
    {g}
  </text>
);
const moveText = (m: readonly [number, number]): string => notation({ from: m[0], to: m[1], jump: !isStep(m[0], m[1]) });

/** The c values of the sweep and their Elo with the 95 % interval (1.96 standard errors), from sweeps.json. */
export function sweepPoints(): { c: number; elo: number; lo: number; hi: number }[] {
  return sweeps.mctsSweep.cpuctBatch1.map((r) => {
    const elo = parseSigned(r.elo.value);
    const se = Number(r.elo.se);
    return { c: Number(r.c), elo, lo: elo - 1.96 * se, hi: elo + 1.96 * se };
  });
}

export const C_MIN = 0.25;
export const C_MAX = 4;

export function McstFigure(): React.JSX.Element {
  const fig = useFigText(inferenceCatalog);
  const { ref, compact } = useCompact();
  const { input } = useLive();
  const run = useLiveRun<McstLive, McstLive>('mcts', async (engines, signal, report) => {
    const snapshot = input!.snapshot; // start() does nothing without a snapshot
    const engine = await engines.net(report.progress);
    report.step(0, ALL_KS.length);
    return runMcts(engine, snapshot.history, MODEL_ID, ALL_KS, signal, (partial, done, total) => report.step(done, total, partial));
  });
  const mine = liveValue(run.state) === 'mine';
  const live: McstLive | null = !mine ? null : run.state.kind === 'done' ? run.state.value : run.state.kind === 'running' ? (run.state.partial ?? null) : null;
  const blocked = liveBlockedReason(input, 'net');
  const ply = input?.snapshot.ply ?? 0;
  const liveCount = live ? live.ks.length : 0;
  const { state, dispatch } = useStepper(live ? liveCount : D.sims.length);
  const [substep, setSubstep] = useState<Substep>('select');
  const [c, setC] = useState(D.meta.cpuct);
  const [showAll, setShowAll] = useState(false);
  const k = state.step;
  useEffect(() => setShowAll(false), [compact]);
  // While the run goes on, a reader who stands on the newest finished step follows it; one who stepped back stays where they are.
  const previous = useRef(0);
  useEffect(() => {
    if (live && state.step === previous.current) dispatch({ type: 'set', step: liveCount });
    previous.current = liveCount;
  }, [liveCount]);

  // Live mode shows the root table only: the table of the reader's position after `kk` simulations. The tree below is the example's.
  const T: McstData = live ? liveTable(live, D.meta) : D;
  const idx = k - 1;
  const kk = live ? (live.ks[idx] ?? 0) : k;
  const noQ = live !== null && idx >= 0 && live.values[idx] === undefined;
  const effSubstep: Substep = live ? 'backup' : substep;
  const seen = live ? kk : visibleSims(k, substep);
  const rs = live ? liveRootState(live, idx) : rootState(D, seen);
  const scores = puctColumn(T, rs, c);
  const next = argmax(scores);
  const sim = !live && k >= 1 ? D.sims[k - 1] : undefined;
  const finished = live ? run.state.kind === 'done' && k === liveCount && liveCount > 0 : k === D.sims.length && substep === 'backup';
  const sweep = sweepPoints();

  // ---- tree ----
  const byVisits = T.root.moves.map((_, i) => i).sort((a, b) => (rs.visits[b] as number) - (rs.visits[a] as number) || (T.root.priors[b] as number) - (T.root.priors[a] as number) || a - b);
  const glyphOf = (edge: number): string => (isStep(D.root.moves[edge]![0], D.root.moves[edge]![1]) ? '›' : '»');

  // ---- table ----
  const rowLimit = compact ? 6 : 8;
  const rows = byVisits.slice(0, showAll ? byVisits.length : rowLimit);
  if (!showAll && !rows.includes(next)) rows.push(next);

  // ---- narration ----
  const narrationOf = (simNo: number, sub: Substep, seenNo: number): string => {
    const sm = D.sims[simNo - 1];
    if (!sm) return fig.plain('fig.mcts.table.caption', { k: seenNo });
    const root = D.root.moves[sm.path[0] as number] as [number, number];
    const lastMoves = movesOf(D, sm.nodes[sm.nodes.length - 1] as number);
    const last = lastMoves[sm.path[sm.path.length - 1] as number] ?? root;
    const afterEdge = rootState(D, simNo);
    const e = sm.path[0] as number;
    return sub === 'select'
      ? fig.plain('fig.mcts.narration.select', { k: simNo, depth: sm.path.length, move: moveText(root) })
      : sub === 'expand'
        ? fig.plain('fig.mcts.narration.expand', { move: moveText(last) })
        : sub === 'evaluate'
          ? fig.plain('fig.mcts.narration.evaluate', { value: fig.fmt.signed(sm.values[D.meta.rootMover] as number, 3), moves: sm.expanded.moves.length })
          : fig.plain('fig.mcts.narration.backup', { move: moveText(root), visits: afterEdge.visits[e] as number, q: fig.fmt.fixed(edgeQ(D, afterEdge, e), 3) });
  };
  const narration = narrationOf(sim ? k : 0, substep, seen);
  // every narration the figure can show (every simulation in every sub-step, the table caption of the last step), so that its status area keeps the height of the longest one
  const stepLabel = (at: number): string => fig.kit('kit.stepper.step', { k: at, n: live ? ALL_KS.length : D.sims.length });
  const SUBSTEPS: readonly Substep[] = ['select', 'expand', 'evaluate', 'backup'];
  const narrations = [
    ...D.sims.flatMap((_, i) => SUBSTEPS.map((sub) => ({ step: stepLabel(i + 1), text: narrationOf(i + 1, sub, 0) }))),
    { step: stepLabel(Math.max(D.sims.length, live ? ALL_KS.length : 0)), text: fig.plain('fig.mcts.table.caption', { k: Math.max(D.sims.length, ...ALL_KS) }) },
  ];
  const choice = T.root.moves[T.meta.choiceEdge] as [number, number];
  const runText = liveStatusText(fig.kit, run.state);
  const statusText = run.state.kind === 'idle' ? narration : runText;

  const treeTitle = fig.plain('fig.mcts.tree.title');
  return (
    <Figure
      id="fig-mcts"
      labelWord={`${fig.kit('kit.fig.label')} 3.3`}
      title={fig.plain('fig.mcts.title')}
      panel={
        <Manipulate>
          <LiveSwitch
            label={fig.kit('kit.live.label')}
            exampleLabel={fig.kit('kit.live.example')}
            mineLabel={fig.kit('kit.live.mine', { ply: input ? ply : '–' })}
            value={mine ? 'mine' : 'example'}
            onChange={(value) => (value === 'mine' ? run.start() : run.reset())}
            reason={blocked === null ? null : fig.kit(`kit.live.reason.${blocked}`)}
            busy={run.state.kind === 'starting' || run.state.kind === 'running'}
            stopLabel={fig.kit('kit.live.stop')}
            onStop={run.stop}
            failed={run.state.kind === 'failed'}
            retryLabel={fig.kit('kit.live.retry')}
            onRetry={run.start}
          />
          <Stepper state={state} dispatch={dispatch} labels={stepperLabels(fig.kit, fig.t('fig.mcts.stepper'))} />
          {!live && (
            <Segmented
              label={fig.t('fig.mcts.substep.label')}
              value={substep}
              onChange={setSubstep}
              options={SUBSTEPS.map((s) => ({ value: s, label: fig.t(`fig.mcts.substep.${s}`) }))}
            />
          )}
          <Slider
            label={fig.t('fig.mcts.slider.c')}
            min={C_MIN}
            max={C_MAX}
            step={0.25}
            value={c}
            onChange={setC}
            out={fig.fmt.fixed(c, 2)}
            valueText={fig.plain('fig.mcts.slider.valuetext', { c: fig.fmt.fixed(c, 2) })}
            scale={[fig.fmt.fixed(C_MIN, 2), fig.fmt.fixed(C_MAX, 2)]}
          />
        </Manipulate>
      }
      status={{ step: fig.kit('kit.stepper.step', { k, n: live ? ALL_KS.length : D.sims.length }), text: statusText, all: narrations }}
      caption={
        live ? (
          <>
            <Markup text={fig.t('fig.mcts.live.caption', { ply, sims: live.ks[live.ks.length - 1] ?? 0, model: live.modelId })} /> <Markup text={fig.t('fig.mcts.live.settings')} />
            {noQ && <> <Markup text={fig.t('fig.mcts.live.noQ')} /></>}
          </>
        ) : (
          <Markup text={fig.t('fig.mcts.caption')} />
        )
      }
      foot={
        <>
          <FigFoot
            kit={fig.kit}
            paths={['search/src/main/java/sternhalma/search/Mcts.java', 'search/src/main/java/sternhalma/search/MctsNode.java']}
            data="mcts-mid.json"
            origin={live ? { kind: 'mine', ply } : { kind: 'example', ply: EXAMPLE_PLY }}
          />
          <FootChips fig={fig} sources={[]} data={['sweeps.json']} />
        </>
      }
    >
      <div ref={ref}>
        <McstIcicle
          data={D}
          k={Math.max(kk, 0)}
          substep={effSubstep}
          title={treeTitle}
          desc={fig.plain('fig.mcts.tree.desc')}
          rootText={`${fig.pick('fig.mcts.col.visits', true)} = ${seen}`}
          glyphOf={glyphOf}
          compact={compact}
        />
        {live && (
          <p className="ml-fig-note" data-origin-trace="exampleOnly">
            {originText(fig.kit, { kind: 'exampleOnly' })}
          </p>
        )}
        <Legend>
          <LegendItem mark={<rect x={8} y={3} width={16} height={9} className="ml-fig-ice__cell ml-fig-ice__cell--visited" />}>{fig.kit('kit.legend.visited')}</LegendItem>
          <LegendItem mark={<rect x={8} y={3} width={16} height={9} className="ml-fig-ice__cell ml-fig-ice__cell--path" />}>{fig.kit('kit.legend.active')}</LegendItem>
          <LegendItem mark={<rect x={8} y={3} width={16} height={9} className="ml-fig-ice__cell ml-fig-ice__cell--active" />}>{fig.kit('kit.legend.activenode')}</LegendItem>
          <LegendItem mark={<rect x={8} y={3} width={16} height={9} className="ml-fig-ice__cell ml-fig-ice__cell--todo" />}>{fig.kit('kit.legend.todo')}</LegendItem>
          <LegendItem mark={glyphMark('»')}>{fig.kit('kit.legend.jump')}</LegendItem>
          <LegendItem mark={glyphMark('›')}>{fig.kit('kit.legend.step')}</LegendItem>
        </Legend>
      </div>
      <div className="ml-fig-mcts__lower">
        <div>
          <table className="ml-fig-table ml-fig-mcts__table">
            <caption>{fig.plain('fig.mcts.table.caption', { k: seen })}</caption>
            <thead>
              <tr>
                <th scope="col">{fig.pick('fig.mcts.col.move', compact)}</th>
                <th scope="col" className="ml-fig-table__num">{fig.pick('fig.mcts.col.prior', true)}</th>
                <th scope="col" className="ml-fig-table__num">{fig.pick('fig.mcts.col.visits', true)}</th>
                <th scope="col" className="ml-fig-table__num">{fig.pick('fig.mcts.col.value', true)}</th>
                <th scope="col" className="ml-fig-table__num">{fig.pick('fig.mcts.col.puct', true)}</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((e) => {
                const isNext = e === next;
                const isChoice = finished && e === T.meta.choiceEdge;
                const visited = (rs.visits[e] as number) > 0;
                return (
                  <tr key={e} data-edge={e} data-next={isNext || undefined} data-final={isChoice || undefined}>
                    <th scope="row">
                      {isNext && compact && <span className="ml-fig-mcts__mark" aria-hidden="true">▸</span>}
                      {moveText(T.root.moves[e] as [number, number])}
                      {isNext && (compact ? <span className="ml-visually-hidden">{fig.t('fig.mcts.next')}</span> : <span className="ml-tag ml-fig-tag">{fig.t('fig.mcts.next')}</span>)}
                    </th>
                    <td className="ml-fig-table__num">{fig.fmt.fixed(T.root.priors[e] as number, 3)}</td>
                    <td className="ml-fig-table__num">{rs.visits[e]}</td>
                    <td className="ml-fig-table__num">{visited && !noQ ? fig.fmt.fixed(edgeQ(T, rs, e), 3) : '–'}</td>
                    <td className="ml-fig-table__num">{noQ ? '–' : fig.fmt.fixed(scores[e] as number, 3)}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
          <p className="ml-fig-note ml-fig-mcts__symbols">
            {(['prior', 'visits', 'value', 'puct'] as const).map((c) => fig.plain(`fig.mcts.col.${c}`)).join(' · ')}
            {compact && ` · ▸ ${fig.plain('fig.mcts.next')}`}
          </p>
          {rows.length < T.root.moves.length || showAll ? (
            <button type="button" className="ml-btn" aria-expanded={showAll} onClick={() => setShowAll(!showAll)}>
              {showAll ? fig.kit('kit.disclosure.hide') : fig.t('fig.mcts.table.showall', { n: T.root.moves.length })}
            </button>
          ) : null}
          <p className="ml-fig-note"><Markup text={fig.t('fig.mcts.slider.note')} /></p>
          {finished && (
            <p className="ml-fig-final">{fig.plain('fig.mcts.final', { move: moveText(choice), visits: rs.visits[T.meta.choiceEdge] as number })}</p>
          )}
        </div>
        <div>
          <h4 className="ml-fig-subtitle">{fig.t('fig.mcts.inset.title')}</h4>
          <Plot
            title={fig.plain('fig.mcts.inset.title')}
            desc={fig.plain('fig.mcts.inset.desc')}
            layout="fit"
            height={200}
            x={{ scale: 'category', categories: sweep.map((p) => fig.fmt.fixed(p.c, 1)), label: fig.plain('fig.mcts.inset.axis.x') }}
            y={{ scale: 'linear', domain: [-100, 500], ticks: [0, 100, 200, 300, 400, 500], label: fig.plain('fig.mcts.inset.axis.y') }}
          >
            <PlotRef y={0} />
            <PlotDots
              dots={sweep.map((p, i) => ({
                x: i,
                y: p.elo,
                lo: p.lo,
                hi: p.hi,
                valueLabel: fig.fmt.signed(p.elo),
                best: p.elo === Math.max(...sweep.map((q) => q.elo)),
              }))}
            />
          </Plot>
          <p className="ml-fig-note"><Markup text={fig.t('fig.mcts.inset.caption')} /></p>
        </div>
      </div>
    </Figure>
  );
}
