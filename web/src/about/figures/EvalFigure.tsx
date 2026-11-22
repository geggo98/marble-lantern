// V6: the 13 features of the linear evaluator on a real mid-game position. A feature table with include switches and a bar per
// contribution, an overlay on the board for the selected feature, a weight slider (a thought experiment) and the sigmoid.
import { useMemo, useRef, useState } from 'react';
import {
  Checkbox,
  Figure,
  Legend,
  LegendItem,
  LiveSwitch,
  Manipulate,
  MiniBoard,
  PLOT_BREAKPOINT_PX,
  Plot,
  PlotCursor,
  PlotLine,
  PlotRef,
  Readout,
  Slider,
  useMeasuredWidth,
  HEAT_RADIUS,
} from '../kit';
import { Icon } from '../kit/icons';
import { Markup } from '../kit/markup';
import { cellPoint } from '../../ui/board-model';
import raw from '../data/eval-mid.json';
import { liveBlockedReason } from '../live/classic';
import { useLive } from '../live/context';
import { runEvalLive } from '../live/eval';
import { useLiveRun } from '../live/useLiveRun';
import { rowsOf } from '../../game/variant';
import { FigFoot, LiveRows5Note, figLabel, useFigText } from './common';
import type { Catalog, DataOrigin } from './common';
import { barShare, contributionOf, overlayOf, scoreOf, weightOf, winProbability } from './eval-model';
import type { EvalData, Setting } from './eval-model';

const EXAMPLE = raw as unknown as EvalData;
const SIGMOID_RANGE = 600;

export interface EvalFigureProps {
  catalog: Catalog;
  number: readonly [number, number];
}

const sign = (v: number): string => (v > 0 ? '+' : '');

export function EvalFigure({ catalog, number }: EvalFigureProps): React.JSX.Element {
  const f = useFigText(catalog);
  const { t, kit, n } = f;
  const { input } = useLive();
  const run = useLiveRun<EvalData, never>('eval', (engines, signal, report) => runEvalLive(engines, input!.snapshot, signal, report));
  const live = run.state.kind === 'done' ? run.state.value : null;
  const DATA = live ?? EXAMPLE;
  const mine = run.state.kind === 'starting' || run.state.kind === 'running' || run.state.kind === 'done';
  const blocked = liveBlockedReason(input, 'classical');
  const state = run.state;
  const status = {
    text:
      state.kind === 'starting' ? kit('kit.live.status.loading')
      : state.kind === 'running' ? kit('kit.live.status.running', { done: state.done, total: state.total })
      : state.kind === 'done' ? kit('kit.live.status.done')
      : state.kind === 'stopped' ? kit('kit.live.status.stopped')
      : state.kind === 'failed' ? kit(`kit.live.error.${state.code}`)
      : '',
  };
  const origin: DataOrigin = live ? { kind: 'mine', ply: live.meta.ply } : { kind: 'example', ply: EXAMPLE.meta.ply };
  const cellsOf = (owner: number): number[] => DATA.cells.flatMap((c, i) => (c === owner ? [i] : []));
  const [include, setInclude] = useState<ReadonlySet<string>>(() => new Set(DATA.features.map((x) => x.name)));
  const [weights, setWeights] = useState<Readonly<Record<string, number>>>({});
  const [selected, setSelected] = useState<string | null>(null);
  const [plotOpen, setPlotOpen] = useState(false);
  const holder = useRef<HTMLDivElement>(null);
  const width = useMeasuredWidth(holder, '.ml-fig');
  const compact = width > 0 && width < PLOT_BREAKPOINT_PX;

  const setting: Setting = { include, weights };
  const scale = DATA.meta.scale;
  const score = scoreOf(DATA.features, setting, scale);
  const probability = winProbability(score, scale);
  const largest = Math.max(...DATA.features.map((x) => Math.abs(contributionOf(x, { include: new Set(DATA.features.map((y) => y.name)), weights: {} }, scale))));
  const fmt = (value: number, digits: number): string => new Intl.NumberFormat(f.language, { minimumFractionDigits: digits, maximumFractionDigits: digits }).format(value);
  const label = (name: string): string => t(`fig.eval.feature.${name}.label`);
  const selectedFeature = DATA.features.find((x) => x.name === selected);
  const overlay = useMemo(() => (selected === null ? null : overlayOf(selected, DATA)), [selected, DATA]);

  const toggleInclude = (name: string, on: boolean): void =>
    setInclude((prev) => {
      const next = new Set(prev);
      if (on) next.add(name);
      else next.delete(name);
      return next;
    });
  const changed = (name: string): boolean => weights[name] !== undefined && weights[name] !== DATA.features.find((x) => x.name === name)!.weight;

  const curve = useMemo(() => Array.from({ length: SIGMOID_RANGE / 10 + 1 }, (_, i) => -SIGMOID_RANGE + i * 20).map((x) => ({ x, y: winProbability(x, scale) })), [scale]);
  const clamped = Math.max(-SIGMOID_RANGE, Math.min(SIGMOID_RANGE, score));

  const board = (
    <div className="ml-fig-eval__board">
      <MiniBoard
        title={live ? kit('kit.live.mine', { ply: live.meta.ply }) : t('fig.eval.board.title')}
        desc={live ? f.plain('fig.eval.live.desc', { ply: live.meta.ply, score: `${sign(live.meta.score)}${live.meta.score}` }) : f.plain('fig.eval.board.desc')}
        pieces={{ a: cellsOf(0), b: cellsOf(1) }}
        rings={overlay?.rings ?? []}
        arrows={overlay?.arrows ?? []}
      >
        {overlay && (
          <g aria-hidden="true">
            {overlay.pairs.map(([a, b]) => {
              const p = cellPoint(a, false);
              const q = cellPoint(b, false);
              return <line key={`${a}-${b}`} className="ml-fig-board__pair" x1={p.x} y1={p.y} x2={q.x} y2={q.y} />;
            })}
            {overlay.discs.map((d) => {
              const p = cellPoint(d.cell, false);
              return <circle key={d.cell} className={`ml-fig-heat__v--${d.level} ml-fig-board__disc`} cx={p.x} cy={p.y} r={HEAT_RADIUS[d.level] * 0.6} />;
            })}
          </g>
        )}
      </MiniBoard>
      <p className="ml-fig-eval__explain" aria-live="polite">
        {selected === null ? t('fig.eval.overlay.none') : t(`fig.eval.feature.${selected}.explain`)}
      </p>
      <p className="ml-fig-ab__orientation">{kit('kit.board.orientation', { top: f.ui('side_a'), bottom: f.ui('side_b') })}</p>
    </div>
  );

  const probabilityText = `${n(Math.round(probability * 100))} %`;
  const table = (
    <table className="ml-fig-feat">
      <caption className="ml-visually-hidden">{f.plain('fig.eval.table.caption')}</caption>
      <thead>
        <tr>
          <th scope="col">
            <span className="ml-visually-hidden">{t('fig.eval.include')}</span>
          </th>
          <th scope="col">{t('fig.eval.col.feature')}</th>
          <th scope="col">{t('fig.eval.col.value')}</th>
          <th scope="col">{t('fig.eval.col.weight')}</th>
          <th scope="col">{t('fig.eval.col.contribution')}</th>
        </tr>
      </thead>
      <tbody>
        {DATA.features.map((feature) => {
          const weight = weightOf(feature, setting);
          const contribution = contributionOf(feature, setting, scale);
          const share = barShare(contribution, largest);
          const on = include.has(feature.name);
          return (
            <tr key={feature.name} data-selected={selected === feature.name ? 'true' : undefined} data-off={on ? undefined : 'true'}>
              <td>
                <Checkbox
                  label={<span className="ml-visually-hidden">{`${t('fig.eval.include')}: ${label(feature.name)}`}</span>}
                  checked={on}
                  onChange={(value) => toggleInclude(feature.name, value)}
                />
              </td>
              <th scope="row">
                <button
                  type="button"
                  className="ml-fig-feat__pick"
                  aria-pressed={selected === feature.name}
                  aria-label={t('fig.eval.select', { feature: label(feature.name) })}
                  onClick={() => setSelected((prev) => (prev === feature.name ? null : feature.name))}
                >
                  {label(feature.name)}
                  {changed(feature.name) && <span className="ml-fig-tag">{t('fig.eval.changed')}</span>}
                </button>
              </th>
              <td data-num data-label={t('fig.eval.col.value')}>{n(feature.value)}</td>
              <td data-num data-label={t('fig.eval.col.weight')}>{fmt(weight, 3)}</td>
              <td className="ml-fig-feat__contrib">
                <div className="ml-fig-feat__contrib-in">
                  <span className="ml-fig-bar" aria-hidden="true">
                    <span
                      className="ml-fig-bar__fill"
                      data-sign={share < 0 ? 'neg' : 'pos'}
                      style={{ width: `${Math.abs(share) * 50}%`, insetInlineStart: share < 0 ? `${50 + share * 50}%` : '50%' }}
                    />
                  </span>
                  <span data-num>{`${sign(contribution)}${n(Math.round(contribution) || 0)}`}</span>
                </div>
              </td>
            </tr>
          );
        })}
      </tbody>
    </table>
  );

  const plot = (
    <Plot
      title={t('fig.eval.sigmoid.title')}
      desc={f.plain('fig.eval.sigmoid.desc')}
      layout="fit"
      x={{ scale: 'linear', domain: [-SIGMOID_RANGE, SIGMOID_RANGE], ticks: [-SIGMOID_RANGE / 2, 0, SIGMOID_RANGE / 2], label: t('fig.eval.sigmoid.axis.x') }}
      y={{ scale: 'linear', domain: [0, 1], ticks: [0, 0.5, 1], label: t('fig.eval.sigmoid.axis.y') }}
    >
      <PlotRef y={0.5} />
      <PlotLine points={curve} />
      <PlotCursor x={clamped} y={winProbability(clamped, scale)} />
    </Plot>
  );

  const slider = selectedFeature && (
    <div className="ml-fig-eval__weight">
      <Slider
        label={t('fig.eval.slider.weight', { feature: label(selectedFeature.name) })}
        min={-1}
        max={1}
        step={0.01}
        value={weightOf(selectedFeature, setting)}
        onChange={(value) => setWeights((prev) => ({ ...prev, [selectedFeature.name]: value }))}
        valueText={t('fig.eval.slider.valuetext', { weight: fmt(weightOf(selectedFeature, setting), 2), learned: fmt(selectedFeature.weight, 2) })}
        out={fmt(weightOf(selectedFeature, setting), 2)}
        scale={['−1', '+1']}
      />
      <button
        type="button"
        className="ml-btn"
        aria-disabled={!changed(selectedFeature.name)}
        aria-label={t('fig.eval.reset')}
        onClick={() =>
          setWeights((prev) => {
            const next = { ...prev };
            delete next[selectedFeature.name];
            return next;
          })
        }
      >
        <Icon name="reset" />
        {compact ? t('fig.eval.reset.short') : t('fig.eval.reset')}
      </button>
    </div>
  );

  return (
    <Figure
      id="fig-eval"
      labelWord={figLabel(kit, number[0], number[1])}
      title={t('fig.eval.title')}
      split={!compact}
      panel={
        <Manipulate>
          <LiveSwitch
            label={kit('kit.live.label')}
            exampleLabel={kit('kit.live.example')}
            mineLabel={kit('kit.live.mine', { ply: input?.snapshot.ply ?? 0 })}
            value={mine ? 'mine' : 'example'}
            onChange={(value) => (value === 'mine' ? run.start() : run.reset())}
            reason={blocked ? kit(`kit.live.reason.${blocked}`) : null}
            busy={run.state.kind === 'starting' || run.state.kind === 'running'}
            stopLabel={kit('kit.live.stop')}
            onStop={run.stop}
            failed={run.state.kind === 'failed'}
            retryLabel={kit('kit.live.retry')}
            onRetry={run.start}
          />
        </Manipulate>
      }
      status={status}
      caption={
        <>
          <Markup text={t('fig.eval.caption')} />
          {live && <> <Markup text={t('fig.eval.live.evaluator', { evaluator: '`linear-r3`' })} /></>}
          <LiveRows5Note kit={kit} show={mine && input !== null && rowsOf(input.snapshot.variant) === 5} />
        </>
      }
      foot={<FigFoot kit={kit} paths={['search/src/main/java/sternhalma/search/LinearEvaluator.java', 'search/src/main/java/sternhalma/search/LinearWeights.java']} data="eval-mid.json" origin={origin} />}
    >
      {board}
      <div ref={holder} className="ml-fig-eval__main">
        <Readout
          cells={[
            { label: t('fig.eval.score'), value: `${sign(score)}${n(score)}` },
            { label: compact ? t('fig.eval.probability.short') : t('fig.eval.probability'), value: probabilityText },
          ]}
        />
        {table}
        {slider}
        {compact ? (
          <>
            <button type="button" className="ml-btn" aria-expanded={plotOpen} onClick={() => setPlotOpen((v) => !v)}>
              {plotOpen ? kit('kit.disclosure.hide') : t('fig.eval.sigmoid.show')}
            </button>
            {plotOpen && plot}
          </>
        ) : (
          plot
        )}
        <Legend>
          <LegendItem mark={<circle cx={16} cy={8} r={HEAT_RADIUS[3]} className="ml-fig-heat__v--3" />}>{kit('kit.legend.heat')}</LegendItem>
          <LegendItem mark={<line className="ml-fig-plot__cursor" x1={16} x2={16} y1={0} y2={16} />}>{kit('kit.legend.cursor')}</LegendItem>
        </Legend>
      </div>
    </Figure>
  );
}
