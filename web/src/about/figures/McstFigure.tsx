// V10: MCTS, one simulation at a time (Fig. 3.4). The tree, the root table and the PUCT column come from replaying mcts-mid.json
// (puct.ts); the inset chart from sweeps.json. Layout notes: web/design/about/about-notes.md, "V10".
import { useEffect, useState } from 'react';
import { isStep, notation } from '../../ui/board-model';
import { Figure, Legend, LegendItem, Manipulate, Plot, PlotDots, PlotRef, Segmented, Slider, Stepper, stepperLabels, useStepper } from '../kit';
import { Markup } from '../kit/markup';
import data from '../data/mcts-mid.json';
import sweeps from '../data/sweeps.json';
import { inferenceCatalog } from './catalogs';
import { parseSigned } from './numbers';
import { McstIcicle } from './McstIcicle';
import { SUBSTEPS, argmax, edgeQ, movesOf, puctColumn, rootState, visibleSims } from './puct';
import type { McstData, Substep } from './puct';
import { FootChips, useCompact, useFigText } from './shared';

const D = data as unknown as McstData;
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
  const { state, dispatch } = useStepper(D.sims.length);
  const [substep, setSubstep] = useState<Substep>('select');
  const [c, setC] = useState(D.meta.cpuct);
  const [showAll, setShowAll] = useState(false);
  const k = state.step;
  useEffect(() => setShowAll(false), [compact]);

  const seen = visibleSims(k, substep);
  const rs = rootState(D, seen);
  const scores = puctColumn(D, rs, c);
  const next = argmax(scores);
  const sim = k >= 1 ? D.sims[k - 1] : undefined;
  const finished = k === D.sims.length && substep === 'backup';
  const sweep = sweepPoints();

  // ---- tree ----
  const byVisits = D.root.moves.map((_, i) => i).sort((a, b) => (rs.visits[b] as number) - (rs.visits[a] as number) || (D.root.priors[b] as number) - (D.root.priors[a] as number) || a - b);
  const glyphOf = (edge: number): string => (isStep(D.root.moves[edge]![0], D.root.moves[edge]![1]) ? '›' : '»');

  // ---- table ----
  const rowLimit = compact ? 6 : 8;
  const rows = byVisits.slice(0, showAll ? byVisits.length : rowLimit);
  if (!showAll && !rows.includes(next)) rows.push(next);

  // ---- narration ----
  let narration = fig.plain('fig.mcts.table.caption', { k: seen });
  if (sim) {
    const root = D.root.moves[sim.path[0] as number] as [number, number];
    const lastMoves = movesOf(D, sim.nodes[sim.nodes.length - 1] as number);
    const last = lastMoves[sim.path[sim.path.length - 1] as number] ?? root;
    const afterEdge = rootState(D, k);
    const e = sim.path[0] as number;
    narration =
      substep === 'select'
        ? fig.plain('fig.mcts.narration.select', { k, depth: sim.path.length, move: moveText(root) })
        : substep === 'expand'
          ? fig.plain('fig.mcts.narration.expand', { move: moveText(last) })
          : substep === 'evaluate'
            ? fig.plain('fig.mcts.narration.evaluate', { value: fig.fmt.signed(sim.values[D.meta.rootMover] as number, 3), moves: sim.expanded.moves.length })
            : fig.plain('fig.mcts.narration.backup', { move: moveText(root), visits: afterEdge.visits[e] as number, q: fig.fmt.fixed(edgeQ(D, afterEdge, e), 3) });
  }
  const choice = D.root.moves[D.meta.choiceEdge] as [number, number];

  const treeTitle = fig.plain('fig.mcts.tree.title');
  return (
    <Figure
      id="fig-mcts"
      labelWord={`${fig.kit('kit.fig.label')} 3.3`}
      title={fig.plain('fig.mcts.title')}
      panel={
        <Manipulate>
          <Stepper state={state} dispatch={dispatch} labels={stepperLabels(fig.kit, fig.t('fig.mcts.stepper'))} />
          <Segmented
            label={fig.t('fig.mcts.substep.label')}
            value={substep}
            onChange={setSubstep}
            options={SUBSTEPS.map((s) => ({ value: s, label: fig.t(`fig.mcts.substep.${s}`) }))}
          />
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
      status={{ step: fig.kit('kit.stepper.step', { k, n: D.sims.length }), text: narration }}
      caption={<Markup text={fig.t('fig.mcts.caption')} />}
      foot={<FootChips fig={fig} sources={['search/src/main/java/sternhalma/search/Mcts.java', 'search/src/main/java/sternhalma/search/MctsNode.java']} data={['mcts-mid.json', 'sweeps.json']} />}
    >
      <div ref={ref}>
        <McstIcicle
          data={D}
          k={Math.max(k, 0)}
          substep={substep}
          title={treeTitle}
          desc={fig.plain('fig.mcts.tree.desc')}
          rootText={`${fig.pick('fig.mcts.col.visits', true)} = ${seen}`}
          glyphOf={glyphOf}
          compact={compact}
        />
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
                const isChoice = finished && e === D.meta.choiceEdge;
                const visited = (rs.visits[e] as number) > 0;
                return (
                  <tr key={e} data-edge={e} data-next={isNext || undefined} data-final={isChoice || undefined}>
                    <th scope="row">
                      {isNext && compact && <span className="ml-fig-mcts__mark" aria-hidden="true">▸</span>}
                      {moveText(D.root.moves[e] as [number, number])}
                      {isNext && (compact ? <span className="ml-visually-hidden">{fig.t('fig.mcts.next')}</span> : <span className="ml-tag ml-fig-tag">{fig.t('fig.mcts.next')}</span>)}
                    </th>
                    <td className="ml-fig-table__num">{fig.fmt.fixed(D.root.priors[e] as number, 3)}</td>
                    <td className="ml-fig-table__num">{rs.visits[e]}</td>
                    <td className="ml-fig-table__num">{visited ? fig.fmt.fixed(edgeQ(D, rs, e), 3) : '–'}</td>
                    <td className="ml-fig-table__num">{fig.fmt.fixed(scores[e] as number, 3)}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
          <p className="ml-fig-note ml-fig-mcts__symbols">
            {(['prior', 'visits', 'value', 'puct'] as const).map((c) => fig.plain(`fig.mcts.col.${c}`)).join(' · ')}
            {compact && ` · ▸ ${fig.plain('fig.mcts.next')}`}
          </p>
          {rows.length < D.root.moves.length || showAll ? (
            <button type="button" className="ml-btn" aria-expanded={showAll} onClick={() => setShowAll(!showAll)}>
              {showAll ? fig.kit('kit.disclosure.hide') : fig.t('fig.mcts.table.showall', { n: D.root.moves.length })}
            </button>
          ) : null}
          <p className="ml-fig-note"><Markup text={fig.t('fig.mcts.slider.note')} /></p>
          {finished && (
            <p className="ml-fig-final">{fig.plain('fig.mcts.final', { move: moveText(choice), visits: rs.visits[D.meta.choiceEdge] as number })}</p>
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
