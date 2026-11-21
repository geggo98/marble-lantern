// Figure V14 of topic 5 (tuning): the staircase of seven levels with the measured effect of each, and the four panels behind it
// (Elo curve, MCTS sweeps, futility grid, sweep 1). The staircase stands in section `what`; each panel stands in the section that
// explains its experiment, and a row of the staircase links to that section (plan section 3, brief V14; deviation noted in the hand-over).
// All text comes from the topic catalog (`fig.story.*`) and the shared kit catalog (`kit.*`); all numbers come through tuning-model.ts.
import { useMemo, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import {
  Chip, Figure, Legend, LegendItem, Manipulate, Plot, PlotBand, PlotCursor, PlotDots, PlotLine, PlotRef, Readout, Segmented, Slider, usePlot,
  useMeasuredWidth, plotBoxFor, linearScale, linearTicks, heatLevel, HEAT_RADIUS,
} from '../kit';
import type { Dot } from '../kit';
import { Markup } from '../kit/markup';
import { sourceUrl } from '../kit/SourceChip';
import { kitCatalog } from '../i18n/kit';
import { useT } from '../../ui/texts';
import { hrefForAbout } from '../../ui/Chrome';
import { plainText } from '../topics/plain-text';
import { tuningCatalog } from './tuning-catalog';
import type { TuningKey } from './tuning-catalog';
import {
  SCORE_MAX, SCORE_MIN, eloCurve, eloPoint, futilityRange, futilityRows, levels, mctsSweeps, saturationScores, staircaseDomain, sweep1, sweep1Domain, sweepsDomain,
} from './tuning-model';
import type { FutilityCell, Level, LevelId, SweepDot } from './tuning-model';
import './tuning.css';

const DATA_LADDER = 'web/src/about/data/tuning-ladder.json';
const DATA_SWEEPS = 'web/src/about/data/sweeps.json';
const SRC = {
  elo: 'search/src/main/java/sternhalma/search/Elo.java',
  arena: 'search/src/main/java/sternhalma/search/Arena.java',
  strategies: 'search/src/main/java/sternhalma/search/Strategies.java',
  mcts: 'search/src/main/java/sternhalma/search/MctsOptions.java',
  sweep: 'selfplay/src/main/java/sternhalma/selfplay/Sweep.java',
  gate: 'selfplay/src/main/java/sternhalma/selfplay/Gate.java',
} as const;

/** The lookups every figure of this file needs. */
function useFig(): {
  text: (key: TuningKey, values?: Record<string, string | number>) => string;
  plain: (key: TuningKey, values?: Record<string, string | number>) => string;
  md: (key: TuningKey, values?: Record<string, string | number>) => ReactNode;
  kit: (key: Parameters<ReturnType<typeof kitCatalog.useAboutT>>[0], values?: Record<string, string | number>) => string;
  fmt: (value: number, digits?: number) => string;
  signed: (value: number) => string;
} {
  const text = tuningCatalog.useAboutT();
  const kit = kitCatalog.useAboutT();
  const { n, language } = useT();
  const plain = (key: TuningKey, values?: Record<string, string | number>): string => plainText(text(key, values), n);
  const md = (key: TuningKey, values?: Record<string, string | number>): ReactNode => <Markup text={text(key, values)} />;
  const fmt = (value: number, digits = 3): string =>
    new Intl.NumberFormat(language, { minimumFractionDigits: 0, maximumFractionDigits: digits }).format(value);
  const signed = (value: number): string => `${value < 0 ? '−' : '+'}${new Intl.NumberFormat(language, { maximumFractionDigits: 0 }).format(Math.abs(Math.round(value)))}`;
  return { text, plain, md, kit, fmt, signed };
}

const chips = (kit: ReturnType<typeof useFig>['kit'], sources: readonly string[], data: readonly string[]): ReactNode => (
  <>
    {sources.map((p) => (
      <Chip key={p} href={sourceUrl(p)} prefix={kit('kit.chip.src')}>
        {p}
      </Chip>
    ))}
    {data.map((p) => (
      <Chip key={p} href={sourceUrl(p)} prefix={kit('kit.chip.data')} data>
        {p}
      </Chip>
    ))}
  </>
);

/** True below the figure breakpoint (640 px), measured on `ref`; false while there is no layout (jsdom, first paint). */
function useCompact(ref: React.RefObject<HTMLElement | null>): boolean {
  return plotBoxFor(useMeasuredWidth(ref)).name === 'compact';
}

const levelKey = (id: LevelId, part: 'label' | 'example' | 'effect'): TuningKey => `fig.story.level.${id}.${part}` as TuningKey;

// ---------------------------------------------------------------------------------------------------------------------------
// The staircase
// ---------------------------------------------------------------------------------------------------------------------------

type Filter = 'all' | 'measured';

const ROW_H = 24;
const PAD = 14;

function Whisker({ level, domain, width, ticks, label, desc }: { level: Level; domain: [number, number]; width: number; ticks: number[]; label: string; desc: string }): React.JSX.Element {
  const sx = linearScale(domain, [PAD, width - PAD]);
  const cy = ROW_H / 2;
  return (
    <svg className="ml-fig-story__whisker" viewBox={`0 0 ${width} ${ROW_H}`} width={width} height={ROW_H} role="img" aria-label={label} data-elo={level.elo}>
      <title>{label}</title>
      <desc>{desc}</desc>
      {ticks.map((t) => (
        <line key={t} className="ml-fig-plot__grid" x1={sx(t)} x2={sx(t)} y1={2} y2={ROW_H - 2} />
      ))}
      {level.elo !== undefined && level.ci !== undefined && (
        <path
          className="ml-fig-plot__whisker"
          d={`M${sx(level.elo - level.ci)} ${cy}H${sx(level.elo + level.ci)}M${sx(level.elo - level.ci)} ${cy - 5}V${cy + 5}M${sx(level.elo + level.ci)} ${cy - 5}V${cy + 5}`}
        />
      )}
      {level.elo !== undefined && <circle className="ml-fig-plot__pt--1" cx={sx(level.elo)} cy={cy} r={4.5} />}
    </svg>
  );
}

export function StoryFigure(): React.JSX.Element {
  const { text, plain, md, kit } = useFig();
  const all = useMemo(levels, []);
  const [filter, setFilter] = useState<Filter>('all');
  const [selected, setSelected] = useState<LevelId>('hand-tuning');
  const list = useRef<HTMLOListElement>(null);
  const compact = useCompact(list);

  const shown = filter === 'all' ? all : all.filter((l) => l.measured);
  const current = shown.find((l) => l.id === selected) ?? shown[0];
  const domain = staircaseDomain(all);
  const width = compact ? 120 : 280;
  const ticks = linearTicks(domain[0], domain[1], compact ? 4 : 7);
  const sx = linearScale(domain, [PAD, width - PAD]);

  const effect = (l: Level): string =>
    plain(levelKey(l.id, 'effect'), l.eloText !== undefined ? { elo: l.eloText, ci: l.ciText ?? '' } : undefined);

  return (
    <Figure
      id="fig-story"
      labelWord={kit('kit.fig.label')}
      number={5.1}
      title={text('fig.story.title')}
      panel={
        <Manipulate>
          <Segmented
            label={text('fig.story.filter.label')}
            value={filter}
            onChange={setFilter}
            options={[
              { value: 'all', label: text('fig.story.filter.all') },
              { value: 'measured', label: text('fig.story.filter.measured') },
            ]}
          />
        </Manipulate>
      }
      status={{ text: current ? effect(current) : '' }}
      detail={
        current && (
          <section className="ml-fig__detail" aria-labelledby="fig-story-detail-title" data-level={current.id}>
            <p className="ml-kicker">{text('fig.story.level.number', { k: current.k })}</p>
            <h4 className="ml-fig__detail-title" id="fig-story-detail-title">
              {md(levelKey(current.id, 'label'))}
            </h4>
            <div className="ml-fig__detail-text">
              <p>{md(levelKey(current.id, 'example'))}</p>
              {current.k > 0 && (
                <p>
                  <a className="ml-about-link" href={hrefForAbout('tuning', current.id)}>
                    {plain('fig.story.panel.open', { level: plain(levelKey(current.id, 'label')) })}
                  </a>
                </p>
              )}
            </div>
          </section>
        )
      }
      caption={md('fig.story.caption')}
      foot={chips(kit, [SRC.elo, SRC.arena], [DATA_LADDER])}
    >
      <div className="ml-fig-story" data-layout={compact ? 'compact' : 'wide'}>
        <div className="ml-fig-story__axisrow">
          <span className="ml-fig-story__axislabel">{text('fig.story.axis.elo')}</span>
          <svg className="ml-fig-story__axis" viewBox={`0 0 ${width} 22`} width={width} height={22} aria-hidden="true" focusable="false">
            {ticks.map((t) => (
              <g key={t}>
                <line className="ml-fig-plot__tick" x1={sx(t)} x2={sx(t)} y1={0} y2={5} />
                <text x={sx(t)} y={17} textAnchor="middle">
                  {t}
                </text>
              </g>
            ))}
          </svg>
        </div>
        <ol ref={list} className="ml-fig-story__list" aria-label={text('fig.story.staircase.label')}>
          {shown.map((l) => (
            <li key={l.id} className="ml-fig-story__item" style={{ '--ml-story-level': l.k } as React.CSSProperties}>
              <button type="button" className="ml-fig-story__row" aria-pressed={current?.id === l.id} onClick={() => setSelected(l.id)} data-level={l.id}>
                <span className="ml-fig-story__text">
                  <span className="ml-kicker">{text('fig.story.level.number', { k: l.k })}</span>
                  <span className="ml-fig-story__label">{md(levelKey(l.id, 'label'))}</span>
                  <span className="ml-fig-story__example">{md(levelKey(l.id, 'example'))}</span>
                </span>
                {l.measured ? (
                  <Whisker level={l} domain={domain} width={width} ticks={ticks} label={effect(l)} desc={plain(levelKey(l.id, 'label'))} />
                ) : (
                  <span className="ml-tag ml-fig-story__tag">{text('fig.story.unmeasured')}</span>
                )}
              </button>
            </li>
          ))}
        </ol>
      </div>
    </Figure>
  );
}

// ---------------------------------------------------------------------------------------------------------------------------
// Panel: Elo from a score
// ---------------------------------------------------------------------------------------------------------------------------

/** The two reference lines of loop run 4, with one label that stands left of the first line (it would run over the second otherwise). */
function Saturation({ label, scores }: { label: string; scores: [number, number] }): React.JSX.Element {
  const { sx, area } = usePlot();
  return (
    <g data-saturation={scores.join(',')}>
      <PlotRef x={scores[0]} />
      <PlotRef x={scores[1]} />
      <text x={sx(scores[0]) - 4} y={area.y0 + 12} textAnchor="end">
        {label}
      </text>
    </g>
  );
}

export function EloFigure(): React.JSX.Element {
  const { text, plain, md, kit, fmt, signed } = useFig();
  const [score, setScore] = useState(0.64);
  const [pairs, setPairs] = useState(200);
  const holder = useRef<HTMLDivElement>(null);
  const compact = useCompact(holder);
  const curve = useMemo(() => eloCurve(pairs), [pairs]);
  const point = eloPoint(score, pairs);
  const scores = saturationScores();
  const clip = (v: number): number => Math.max(-200, Math.min(1200, v));

  return (
    <Figure
      id="fig-story-elo"
      labelWord={kit('kit.fig.label')}
      number={5.2}
      title={text('fig.story.panel.elo.title')}
      panel={
        <Manipulate>
          <Slider
            label={text('fig.story.panel.elo.slider.score')}
            min={SCORE_MIN}
            max={SCORE_MAX}
            step={0.001}
            value={score}
            onChange={(v) => setScore(Math.round(v * 1000) / 1000)}
            out={fmt(score)}
            valueText={plain('fig.story.panel.elo.slider.valuetext', { score: fmt(score), pairs, elo: signed(point.elo), ci: Math.round(point.ci) })}
            scale={[fmt(SCORE_MIN), fmt(SCORE_MAX)]}
          />
          <Slider
            label={text('fig.story.panel.elo.slider.pairs')}
            min={50}
            max={1000}
            step={50}
            value={pairs}
            onChange={setPairs}
            out={fmt(pairs)}
            scale={[fmt(50), fmt(1000)]}
          />
          <Readout
            cells={[
              { label: text('fig.story.panel.elo.axis.y'), value: signed(point.elo) },
              { label: kit('kit.legend.interval'), value: `± ${fmt(Math.round(point.ci))}` },
            ]}
          />
        </Manipulate>
      }
      caption={md('fig.story.panel.elo.caption')}
      foot={chips(kit, [SRC.elo], [DATA_SWEEPS, DATA_LADDER])}
    >
      <div ref={holder} data-point-elo={point.elo.toFixed(3)}>
        <Plot
          title={plain('fig.story.panel.elo.title')}
          desc={plain('fig.story.panel.elo.desc')}
          x={{ scale: 'linear', domain: [0.5, 1], ticks: [0.5, 0.6, 0.7, 0.8, 0.9, 1], format: (v) => fmt(v, 1), label: text('fig.story.panel.elo.axis.x') }}
          y={{ scale: 'linear', domain: [-200, 1200], ticks: [0, 300, 600, 900, 1200], label: text('fig.story.panel.elo.axis.y') }}
        >
          <PlotRef y={0} />
          <PlotBand xs={curve.map((p) => p.score)} lo={curve.map((p) => clip(p.lo))} hi={curve.map((p) => clip(p.hi))} />
          <Saturation label={plain(compact ? 'fig.story.panel.elo.saturation.short' : 'fig.story.panel.elo.saturation')} scores={scores} />
          <PlotLine points={curve.map((p) => ({ x: p.score, y: p.elo }))} />
          <PlotCursor x={score} y={point.elo} />
        </Plot>
      </div>
      <Legend>
        <LegendItem mark={<path className="ml-fig-plot__line ml-fig-plot__line--1" d="M2 8H30" />}>{text('fig.story.panel.elo.axis.y')}</LegendItem>
        <LegendItem
          mark={
            <>
              <rect className="ml-fig-plot__band" x={2} y={3} width={28} height={10} />
              <path className="ml-fig-plot__bandedge" d="M2 3H30M2 13H30" />
            </>
          }
        >
          {kit('kit.legend.interval')}
        </LegendItem>
        <LegendItem mark={<path className="ml-fig-plot__cursor" d="M16 1V15" />}>{kit('kit.legend.cursor')}</LegendItem>
        <LegendItem mark={<path className="ml-fig-plot__ref" d="M2 8H30" />}>{text('fig.story.panel.elo.saturation')}</LegendItem>
      </Legend>
    </Figure>
  );
}

// ---------------------------------------------------------------------------------------------------------------------------
// Panel: MCTS sweeps
// ---------------------------------------------------------------------------------------------------------------------------

function sweepMarks(rows: readonly SweepDot[], signed: (v: number) => string): Dot[] {
  const best = Math.max(...rows.map((r) => r.elo));
  return rows.map((r) => ({
    x: r.x,
    y: r.elo,
    lo: r.elo - r.ci,
    hi: r.elo + r.ci,
    ...(r.elo === best ? { valueLabel: signed(r.elo), best: true } : {}),
  }));
}

export function SweepsFigure(): React.JSX.Element {
  const { text, plain, md, kit, fmt, signed } = useFig();
  const data = useMemo(mctsSweeps, []);
  const [lo, hi] = useMemo(() => sweepsDomain(data), [data]);
  const yTicks = linearTicks(lo, hi, 4);
  const plots: { id: string; key: TuningKey; rows: SweepDot[]; x: Parameters<typeof Plot>[0]['x'] }[] = [
    { id: 'c', key: 'fig.story.panel.sweeps.c', rows: data.c, x: { scale: 'linear', domain: [0, 4.5], ticks: data.c.map((r) => r.x), format: (v) => fmt(v, 1) } },
    { id: 'batch', key: 'fig.story.panel.sweeps.batch', rows: data.batch, x: { scale: 'log', domain: [0.7, 90], ticks: data.batch.map((r) => r.x), minor: false, format: (v) => fmt(v) } },
    { id: 'ptemp', key: 'fig.story.panel.sweeps.ptemp', rows: data.ptemp, x: { scale: 'linear', domain: [0.5, 2.8], ticks: data.ptemp.map((r) => r.x), format: (v) => fmt(v, 1) } },
  ];
  return (
    <Figure
      id="fig-story-sweeps"
      labelWord={kit('kit.fig.label')}
      number={5.4}
      title={text('fig.story.panel.sweeps.title')}
      caption={md('fig.story.panel.sweeps.caption')}
      foot={chips(kit, [SRC.mcts], [DATA_SWEEPS])}
    >
      <div className="ml-fig-story__small">
        {plots.map((p, i) => (
          <div key={p.id} className="ml-fig-story__smallplot" data-sweep={p.id}>
            <p className="ml-fig-story__smalltitle">{md(p.key)}</p>
            <Plot
              layout="compact"
              title={plain(p.key)}
              desc={i === 0 ? plain('fig.story.panel.sweeps.desc') : plain('fig.story.panel.sweeps.axis.elo')}
              x={p.x}
              y={{ scale: 'linear', domain: [lo, hi], ticks: yTicks, label: text('fig.story.panel.sweeps.axis.elo') }}
            >
              <PlotRef y={0} />
              <PlotDots dots={sweepMarks(p.rows, signed)} />
            </Plot>
          </div>
        ))}
      </div>
      <Legend>
        <LegendItem mark={<circle className="ml-fig-plot__pt--1" cx={16} cy={8} r={4.5} />}>{text('fig.story.panel.sweeps.axis.elo')}</LegendItem>
      </Legend>
    </Figure>
  );
}

// ---------------------------------------------------------------------------------------------------------------------------
// Panel: the futility grid
// ---------------------------------------------------------------------------------------------------------------------------

function EloCell({ c, range, label, empty }: { c: FutilityCell | undefined; range: [number, number]; label: string; empty: string }): React.JSX.Element {
  const { fmt } = useFig();
  if (!c) {
    return (
      <td role="cell" className="ml-fig-story__cell ml-fig-story__cell--empty" data-label={label}>
        {empty}
      </td>
    );
  }
  const level = heatLevel(c.elo, range[0], range[1]);
  return (
    <td role="cell" className="ml-fig-story__cell" data-label={label} data-elo={c.elo} data-level={level ?? undefined}>
      <svg viewBox="-14 -14 28 28" width={28} height={28} aria-hidden="true" focusable="false">
        {level !== null && <circle className={`ml-fig-heat__v--${level}`} r={HEAT_RADIUS[level]} />}
      </svg>
      <span className="ml-fig-story__num">
        {c.eloText} <span className="ml-fig-story__ci">{`± ${fmt(c.ci)}`}</span>
      </span>
    </td>
  );
}

export function FutilityFigure(): React.JSX.Element {
  const { text, plain, md, kit } = useFig();
  const rows = useMemo(futilityRows, []);
  const range = useMemo(() => futilityRange(rows), [rows]);
  const col = (k: 'fut' | 'futk' | 'fut2' | 'seed31' | 'seed32'): string => plain(`fig.story.panel.futility.col.${k}` as TuningKey);
  return (
    <Figure
      id="fig-story-futility"
      labelWord={kit('kit.fig.label')}
      number={5.3}
      title={text('fig.story.panel.futility.title')}
      caption={md('fig.story.panel.futility.caption')}
      foot={chips(kit, [SRC.strategies, SRC.arena], [DATA_LADDER])}
    >
      <div className="ml-fig-story__tablewrap">
        <table className="ml-fig-story__table" role="table">
          <caption className="ml-visually-hidden">{plain('fig.story.panel.futility.desc')}</caption>
          <thead role="rowgroup">
            <tr role="row">
              {(['fut', 'futk', 'fut2', 'seed31', 'seed32'] as const).map((k) => (
                <th key={k} role="columnheader" scope="col">
                  {md(`fig.story.panel.futility.col.${k}` as TuningKey)}
                </th>
              ))}
            </tr>
          </thead>
          <tbody role="rowgroup">
            {rows.map((r, i) => (
              <tr key={i} role="row" className="ml-fig-story__line" data-chosen={r.chosen ? 'true' : undefined}>
                <td role="cell" className="ml-fig-story__cell" data-label={col('fut')}>
                  {r.fut}
                  {r.chosen && <span className="ml-tag ml-tag--solid ml-fig-story__chosen">{text('fig.story.panel.futility.chosen')}</span>}
                </td>
                <td role="cell" className="ml-fig-story__cell" data-label={col('futk')}>
                  {r.futk}
                </td>
                <td role="cell" className="ml-fig-story__cell" data-label={col('fut2')}>
                  {r.fut2 ?? text('fig.story.panel.futility.off')}
                </td>
                <EloCell c={r.seed31} range={range} label={col('seed31')} empty={text('fig.story.panel.futility.empty')} />
                <EloCell c={r.seed32} range={range} label={col('seed32')} empty={text('fig.story.panel.futility.empty')} />
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <Legend>
        <LegendItem
          mark={
            <>
              <circle className="ml-fig-heat__v--1" cx={9} cy={8} r={HEAT_RADIUS[1]} />
              <circle className="ml-fig-heat__v--5" cx={22} cy={8} r={HEAT_RADIUS[5] - 4.5} />
            </>
          }
        >
          {kit("kit.legend.heat")}
        </LegendItem>
      </Legend>
    </Figure>
  );
}

// ---------------------------------------------------------------------------------------------------------------------------
// Panel: sweep 1
// ---------------------------------------------------------------------------------------------------------------------------

export function Sweep1Figure(): React.JSX.Element {
  const { text, plain, md, kit, fmt } = useFig();
  const data = useMemo(sweep1, []);
  const [lo, hi] = sweep1Domain(data);
  const index = (id: string): number => data.variants.findIndex((v) => v.id === id);
  const four = (v: number): string => fmt(v, 4);
  const first: Dot[] = data.variants.map((v, i) => ({
    x: i,
    y: v.score,
    lo: v.score - v.half,
    hi: v.score + v.half,
  }));
  const confirmed: Dot[] = data.confirmation.map((c) => ({
    x: index(c.id) + 0.3,
    y: c.score,
    lo: c.score - c.half,
    hi: c.score + c.half,
    ...(c.id === data.winner ? { valueLabel: four(c.score), best: true } : {}),
  }));
  return (
    <Figure
      id="fig-story-sweep1"
      labelWord={kit('kit.fig.label')}
      number={5.5}
      title={text('fig.story.panel.sweep1.title')}
      caption={
        <>
          {md('fig.story.panel.sweep1.caption')} {md('fig.story.panel.sweep1.bias')}
        </>
      }
      foot={chips(kit, [SRC.sweep, SRC.gate], [DATA_SWEEPS])}
    >
      <div>
        <Plot
          title={plain("fig.story.panel.sweep1.title")}
          desc={plain('fig.story.panel.sweep1.desc')}
          x={{ scale: 'category', categories: data.variants.map((v) => v.id), label: text('fig.story.panel.sweep1.axis.x') }}
          y={{ scale: 'linear', domain: [lo, hi], ticks: linearTicks(lo, hi, 5), label: text('fig.story.panel.sweep1.axis.y'), format: (v) => fmt(v, 2) }}
        >
          <PlotRef y={0.5} />
          <PlotDots dots={first} series={1} />
          <PlotDots dots={confirmed} series={2} />
        </Plot>
      </div>
      <Legend>
        <LegendItem mark={<circle className="ml-fig-plot__pt--1" cx={16} cy={8} r={4.5} />}>{text('fig.story.panel.sweep1.firstpass')}</LegendItem>
        <LegendItem mark={<rect className="ml-fig-plot__pt--2" x={12} y={4} width={8} height={8} />}>{text('fig.story.panel.sweep1.confirmed')}</LegendItem>
        <LegendItem mark={<path className="ml-fig-plot__ref" d="M2 8H30" />}>{plain('fig.story.panel.sweep1.threshold.short')}</LegendItem>
      </Legend>
      <ul className="ml-fig-story__variants">
        {data.variants.map((v) => (
          <li key={v.id} data-variant={v.id} data-winner={v.id === data.winner ? "true" : undefined}>
            {md(`fig.story.panel.sweep1.variant.${v.id}` as TuningKey)}
          </li>
        ))}
      </ul>
    </Figure>
  );
}
