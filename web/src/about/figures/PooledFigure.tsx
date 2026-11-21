// V12: many games (virtual threads), one device thread (Fig. 4.3). A schematic with two cohorts that take turns on the GPU; in CPU mode
// every game has its own evaluator. Layout notes: web/design/about/about-notes.md, "V12". Data: pooled.json.
import { useId, useState } from 'react';
import { Figure, Legend, LegendItem, Manipulate, Segmented, Stepper, stepperLabels, useStepper } from '../kit';
import { Markup } from '../kit/markup';
import { trainingCatalog } from './catalogs';
import { STEPS, layoutFor } from './pooled';
import type { EdgeId, Rect } from './pooled';
import { FootChips, useCompact, useFigText } from './shared';

type Mode = 'cpu' | 'gpu';

const Box = ({ r, label, state, sub, top }: { r: Rect; label: string; state?: 'active' | 'dim'; sub?: string; top?: boolean }): React.JSX.Element => (
  <g data-state={state} className="ml-fig-pooled__box">
    <rect x={r.x} y={r.y} width={r.w} height={r.h} rx={6} />
    <text x={r.x + r.w / 2} y={top ? r.y + 16 : r.y + (sub ? r.h / 2 - 5 : r.h / 2)} textAnchor="middle" dominantBaseline="central">{label}</text>
    {sub && <text className="ml-fig-pooled__sub" x={r.x + r.w / 2} y={r.y + r.h / 2 + 12} textAnchor="middle" dominantBaseline="central">{sub}</text>}
  </g>
);

export function PooledFigure(): React.JSX.Element {
  const fig = useFigText(trainingCatalog);
  const { ref, compact } = useCompact();
  const [mode, setMode] = useState<Mode>('gpu');
  const { state, dispatch } = useStepper(STEPS.length);
  const uid = useId();
  const hatch = `${uid}-hatch`;
  const L = layoutFor(compact);
  const cur = mode === 'gpu' && state.step > 0 ? STEPS[state.step - 1] : undefined;
  const dim = (on: boolean): 'active' | 'dim' | undefined => (cur ? (on ? 'active' : 'dim') : undefined);

  const narration =
    mode === 'cpu' ? fig.plain('fig.pooled.cpu.note') : state.step === 0 ? fig.plain('fig.pooled.idle') : fig.plain(`fig.pooled.step.${state.step}`);
  const title = fig.plain('fig.pooled.title');

  return (
    <Figure
      id="fig-pooled"
      labelWord={`${fig.kit('kit.fig.label')} 4.3`}
      title={title}
      panel={
        <Manipulate>
          <Segmented
            label={fig.t('fig.pooled.mode.label')}
            value={mode}
            onChange={setMode}
            options={[
              { value: 'cpu', label: fig.pick('fig.pooled.mode.cpu', compact) },
              { value: 'gpu', label: fig.pick('fig.pooled.mode.gpu', compact) },
            ]}
          />
          {mode === 'gpu' && <Stepper state={state} dispatch={dispatch} labels={stepperLabels(fig.kit, fig.t('fig.pooled.stepper'))} />}
        </Manipulate>
      }
      status={{
        ...(mode === 'gpu' && state.step > 0 ? { step: fig.kit('kit.stepper.step', { k: state.step, n: STEPS.length }) } : {}),
        text: narration,
        // every narration of the figure, so that its status area keeps the height of the longest one
        all: [
          { text: fig.plain('fig.pooled.cpu.note') },
          { text: fig.plain('fig.pooled.idle') },
          ...STEPS.map((_, i) => ({ step: fig.kit('kit.stepper.step', { k: i + 1, n: STEPS.length }), text: fig.plain(`fig.pooled.step.${i + 1}`) })),
        ],
      }}
      caption={<Markup text={fig.t('fig.pooled.caption')} />}
      foot={
        <FootChips
          fig={fig}
          sources={['selfplay/src/main/java/sternhalma/selfplay/LoopGamesJob.java', 'nn/src/main/java/sternhalma/nn/PooledEvaluator.java']}
          data={['pooled.json']}
        />
      }
    >
      <div ref={ref} className="ml-fig-pooled">
        <svg viewBox={`0 0 ${L.w} ${L.h}`} role="img" aria-labelledby={`${uid}-t ${uid}-d`} className="ml-fig-pooled__svg" data-mode={mode}>
          <title id={`${uid}-t`}>{title}</title>
          <desc id={`${uid}-d`}>{fig.plain('fig.pooled.desc')}</desc>
          <defs>
            <pattern id={hatch} width={5} height={5} patternUnits="userSpaceOnUse" patternTransform="rotate(45)">
              <rect width={5} height={5} className="ml-fig-pooled__hatchbg" />
              <line x1={0} y1={0} x2={0} y2={5} className="ml-fig-pooled__hatchline" />
            </pattern>
          </defs>
          {mode === 'gpu' && (
            <>
              <Box top r={L.evaluator} label={fig.plain('fig.pooled.evaluator')} state={dim(cur?.edges.some((e) => e === 'aIn' || e === 'bIn') ?? false)} />
              <Box r={L.trayA} label={`${fig.plain('fig.pooled.batch')} A`} state={dim(cur?.trays.includes('A') ?? false)} />
              <Box r={L.trayB} label={`${fig.plain('fig.pooled.batch')} B`} state={dim(cur?.trays.includes('B') ?? false)} />
              <Box r={L.device} label={fig.plain('fig.pooled.device')} state={dim(cur?.device ?? false)} />
              {(Object.entries(L.edges) as [EdgeId, string][]).map(([id, d]) => {
                const on = cur?.edges.includes(id) ?? false;
                return <path key={id} d={d} className="ml-fig-pooled__edge" data-edge={id} data-state={dim(on)} markerEnd={`url(#${uid}-head${on ? '-on' : ''})`} />;
              })}
              <defs>
                <marker id={`${uid}-head`} viewBox="0 0 10 10" refX={8} refY={5} markerWidth={7} markerHeight={7} orient="auto">
                  <path d="M0 0L10 5L0 10Z" className="ml-fig-pooled__head" />
                </marker>
                <marker id={`${uid}-head-on`} viewBox="0 0 10 10" refX={8} refY={5} markerWidth={9} markerHeight={9} orient="auto">
                  <path d="M0 0L10 5L0 10Z" className="ml-fig-pooled__head ml-fig-pooled__head--on" />
                </marker>
              </defs>
            </>
          )}
          <text className="ml-fig-pooled__cohort" x={L.labelA.x} y={L.labelA.y}>{fig.plain('fig.pooled.cohort.a')}</text>
          <text className="ml-fig-pooled__cohort" x={L.labelB.x} y={L.labelB.y}>{fig.plain('fig.pooled.cohort.b')}</text>
          {L.tokens.map((tk, i) => {
            const on = cur?.tokens.includes(tk.cohort) ?? false;
            return (
              <g key={i} data-state={dim(on)} data-cohort={tk.cohort} className="ml-fig-pooled__token">
                <circle cx={tk.x} cy={tk.y} r={11} fill={tk.cohort === 'A' ? undefined : `url(#${hatch})`} className={tk.cohort === 'A' ? 'ml-fig-pooled__solid' : 'ml-fig-pooled__hatched'} />
                <text x={tk.x} y={tk.y} textAnchor="middle" dominantBaseline="central" className={tk.cohort === 'A' ? 'ml-fig-pooled__letter--solid' : 'ml-fig-pooled__letter'}>{tk.cohort}</text>
                {mode === 'cpu' && <rect x={tk.x + 14} y={tk.y - 4} width={8} height={8} className="ml-fig-pooled__own" />}
              </g>
            );
          })}
        </svg>
        <Legend>
          <LegendItem mark={<circle cx={16} cy={8} r={6} className="ml-fig-pooled__solid" />}>{fig.plain('fig.pooled.game')} · {fig.plain('fig.pooled.cohort.a')}</LegendItem>
          <LegendItem mark={<circle cx={16} cy={8} r={6} fill={`url(#${hatch})`} className="ml-fig-pooled__hatched" />}>{fig.plain('fig.pooled.cohort.b')}</LegendItem>
          <span className="ml-tag">{fig.t('fig.pooled.schematic')}</span>
        </Legend>
        <p className="ml-fig-note"><Markup text={fig.t('fig.pooled.note.measured')} /></p>
      </div>
    </Figure>
  );
}
