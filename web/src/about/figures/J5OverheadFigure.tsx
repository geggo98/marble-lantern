// Figure J5 of topic 6 (java): what one forward call on the GPU costs through TornadoVM, and what batching changes. Four blocks: this call
// (a bar of work and rest, a readout, a verdict), across batches (time per position on a log axis, a table), inside the call (the six
// phases, the split that the C++ twin measured) and where the project batches (three cards). The state is machine, network and batch; a
// pure model (j5-model.ts) derives everything else from data/j5-overhead.json.
// Stable layout (design spec, section 8): no state changes the height of any part. The three verdicts and the variants of the split share
// one grid cell each; the table always has five rows; headings carry no values. There is no narration line and no live region: the slider
// speaks through aria-valuetext (design notes, section 8, "Screen reader").
import { useId, useLayoutEffect, useRef, useState } from 'react';
import { Ctl, Figure, Legend, LegendItem, Manipulate, PLOT_BREAKPOINT_PX, Plot, PlotCursor, PlotDots, PlotRef, Presets, Segmented, useMeasuredWidth, usePlot } from '../kit';
import type { Dot } from '../kit';
import { Markup } from '../kit/markup';
import { FigFoot, figLabel, useFigText } from './common';
import type { Catalog } from './common';
import { ShareBar } from './J5ShareBar';
import type { ShareSegment } from './J5ShareBar';
import {
  BATCHES, DEFAULT_STATE, J5_DATA, NETS, PLOT_DOMAIN, PLOT_TICKS, callPoint, cpuPerPosition, durationParts, factorValue, isUseActive, kernelsOf, percentText, presetFor, rateValue,
  seriesOf, splitFor, widthPercent,
} from './j5-model';
import type { J5Data, Machine, Net, UseId, Verdict } from './j5-model';
import './j5-overhead.css';

export interface J5OverheadFigureProps {
  catalog: Catalog;
  number: readonly [number, number];
  /** Rows to draw; the data file by default (a test passes its own). */
  data?: J5Data;
  /** The state at the start. */
  initial?: { machine?: Machine; net?: Net; batch?: number };
}

/** The figure that shows the pool step by step: V12 in topic training. Its number is fixed in PooledFigure ("4.3"). */
const POOL_FIGURE_NUMBER = '4.3';
const POOL_FIGURE_HREF = '#/about/training/throughput';
const VERDICTS: readonly Verdict[] = ['dominates', 'mixed', 'amortized'];

/** The pictograms of the three cards: many dots into one box, a tree with three leaves, a path with one leaf. */
function UseIcon({ use }: { use: UseId }): React.JSX.Element {
  switch (use) {
    case 'pool':
      return (
        <svg viewBox="0 0 40 40" aria-hidden="true" focusable="false">
          <circle cx="6" cy="8" r="3" />
          <circle cx="6" cy="20" r="3" />
          <circle cx="6" cy="32" r="3" />
          <path d="M10 8 L20 18 M10 20 H20 M10 32 L20 22" />
          <rect x="20" y="13" width="8" height="14" rx="1.5" />
          <path d="M28 20 H34" />
          <path className="f" d="M33 16 L39 20 L33 24 Z" />
        </svg>
      );
    case 'tree':
      return (
        <svg viewBox="0 0 40 40" aria-hidden="true" focusable="false">
          <circle cx="20" cy="7" r="3" />
          <path d="M18 9.5 L9 18 M22 9.5 L31 18 M20 10 V18" />
          <circle cx="8" cy="21" r="3" />
          <circle cx="20" cy="21" r="3" />
          <circle cx="32" cy="21" r="3" />
          <path d="M8 24 V30 M20 24 V30 M32 24 V30" />
          <rect className="f" x="5" y="30" width="6" height="6" />
          <rect className="f" x="17" y="30" width="6" height="6" />
          <rect className="f" x="29" y="30" width="6" height="6" />
        </svg>
      );
    case 'one':
      return (
        <svg viewBox="0 0 40 40" aria-hidden="true" focusable="false">
          <circle cx="20" cy="7" r="3" />
          <path d="M20 10 V18" />
          <circle cx="20" cy="21" r="3" />
          <path d="M20 24 V30" />
          <rect className="f" x="17" y="30" width="6" height="6" />
        </svg>
      );
  }
}

/** The ring around the dot of the chosen batch (a mark of this figure, not of the kit: the kit cursor ring is 8 units and has no accent stroke). */
function Ring({ x, y, r }: { x: number; y: number; r: number }): React.JSX.Element {
  const { sx, sy } = usePlot();
  return <circle className="ml-fig-j5__ring" cx={sx(x)} cy={sy(y)} r={r} data-ring="" />;
}

/** One phase of a call: number (CSS counter), swatch, text, and the swatch's meaning as hidden text. */
function Phase({ id, scales, label, meaning }: { id: string; scales: 'call' | 'batch'; label: string; meaning: string }): React.JSX.Element {
  return (
    <li className="ml-fig-j5__phase" data-phase={id} data-scales={scales}>
      <span className="ml-fig-share__key" data-fill={scales === 'batch' ? 'solid' : 'hatch'} aria-hidden="true" />
      <span>{label}</span>
      <span className="ml-visually-hidden">, {meaning}</span>
    </li>
  );
}

export function J5OverheadFigure({ catalog, number, data = J5_DATA, initial }: J5OverheadFigureProps): React.JSX.Element {
  const f = useFigText(catalog);
  const { t, kit, n } = f;
  const uid = useId();
  const [machine, setMachine] = useState<Machine>(initial?.machine ?? DEFAULT_STATE.machine);
  const [net, setNet] = useState<Net>(initial?.net ?? DEFAULT_STATE.net);
  const [requested, setBatch] = useState<number>(initial?.batch ?? DEFAULT_STATE.batch);

  const root = useRef<HTMLDivElement>(null);
  const plotHolder = useRef<HTMLDivElement>(null);
  const plotWidth = useMeasuredWidth(plotHolder);
  const compact = plotWidth > 0 && plotWidth < PLOT_BREAKPOINT_PX;

  const rows = seriesOf(machine, net, data);
  // callPoint takes the measured batch nearest to the requested one, so a series that lacks a size never leaves the figure without a row
  const p = callPoint(machine, net, requested, data);
  const batch = p.batch;
  const index = BATCHES.indexOf(batch);
  const cpu = cpuPerPosition(machine, net, data);
  const kernels = kernelsOf(machine, net, data);
  const split = splitFor(machine, net, batch, data);

  const dur = (ms: number): string => {
    const d = durationParts(ms);
    return t(`fig.j5.unit.${d.unit}`, { v: n(d.value) });
  };
  const fixed2 = (v: number): string => new Intl.NumberFormat(f.language, { minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(v);

  // The hooks of the tests and the description sit on the <figure>, which the kit's Figure renders without extra props.
  const descId = `${uid}-desc`;
  useLayoutEffect(() => {
    const figure = root.current?.closest('figure');
    if (!figure) return;
    figure.dataset.machine = machine;
    figure.dataset.net = net;
    figure.dataset.batch = String(batch);
    figure.dataset.verdict = p.verdict;
    figure.setAttribute('aria-describedby', descId);
  });
  // the bold category label of the chosen batch
  useLayoutEffect(() => {
    plotHolder.current?.querySelectorAll('.ml-fig-plot__catlabel').forEach((el, i) => {
      if (i === index) el.setAttribute('data-current', '');
      else el.removeAttribute('data-current');
    });
  });

  // ---- panel ----
  const sliderId = `${uid}-batch`;
  const valueText = t('fig.j5.batch.valuetext', { batch: n(batch), call: dur(p.ms), position: dur(p.perPos) });
  const panel = (
    <Manipulate>
      <Segmented<Machine>
        label={t('fig.j5.machine.label')}
        options={[
          { value: 'mac', label: t('fig.j5.machine.mac') },
          { value: 't4', label: t('fig.j5.machine.t4') },
        ]}
        value={machine}
        onChange={setMachine}
      />
      <Segmented<Net>
        label={t('fig.j5.net.label')}
        options={NETS.map((v) => ({ value: v, label: t(v === 'SMALL' ? 'fig.j5.net.small' : 'fig.j5.net.large') }))}
        value={net}
        onChange={setNet}
      />
      <Presets
        label={t('fig.j5.preset.label')}
        presets={[
          { id: 'one', label: t('fig.j5.preset.one') },
          { id: 'pool', label: t('fig.j5.preset.pool') },
        ]}
        pressed={presetFor(batch, data)}
        onPick={(id) => setBatch(id === 'one' ? data.where.one.batch : data.where.pool.batch)}
      />
      <Ctl htmlFor={sliderId} label={t('fig.j5.batch.label')} out={<span className="ml-fig-j5__out">{t('fig.j5.batch.out', { batch: n(batch) })}</span>}>
        <input
          id={sliderId}
          className="ml-slider"
          type="range"
          min={0}
          max={BATCHES.length - 1}
          step={1}
          value={index}
          aria-valuetext={valueText}
          style={{ '--ml-slider-fill': `${(index / (BATCHES.length - 1)) * 100}%` } as React.CSSProperties}
          onChange={(e) => setBatch(BATCHES[Number(e.currentTarget.value)] ?? batch)}
        />
        <div className="ml-fig-ticks" aria-hidden="true">
          {BATCHES.map((b) => (
            <span key={b} {...(b === batch ? { 'aria-current': 'true' as const } : {})}>
              {n(b)}
            </span>
          ))}
        </div>
      </Ctl>
    </Manipulate>
  );

  // ---- block A: this call ----
  const callSegments: ShareSegment[] = [
    { id: 'work', fill: 'solid', widthPct: widthPercent(p.work, p.ms), label: t('fig.j5.call.work'), value: dur(p.work), share: percentText(1 - p.share) },
    { id: 'rest', fill: 'hatch', widthPct: widthPercent(p.rest, p.ms), label: t('fig.j5.call.rest'), value: dur(p.rest), share: percentText(p.share) },
  ];
  const cells: { id: string; label: string; value: string }[] = [
    { id: 'perpos', label: t('fig.j5.readout.perpos'), value: dur(p.perPos) },
    { id: 'rate', label: t('fig.j5.readout.rate'), value: n(rateValue(p.evals)) },
    { id: 'factor', label: t('fig.j5.readout.factor'), value: `${n(factorValue(p.factor))}×` },
    { id: 'cpu', label: t('fig.j5.readout.cpu'), value: dur(cpu) },
  ];

  // ---- block B: across batches ----
  const dots: { batch: number; dot: Dot }[] = rows.flatMap((r) => {
    const x = BATCHES.indexOf(r.batch);
    if (x < 0) return [];
    const near = r.batch === 1 || r.batch === batch;
    return [{ batch: r.batch, dot: { x, y: r.ms / r.batch, ...(compact ? {} : { valueLabel: dur(r.ms / r.batch), labelSide: near ? ('right' as const) : ('above' as const) }) } }];
  });
  const first = rows[0]!;
  const last = rows[rows.length - 1]!;
  const plotDesc = f.plain('fig.j5.plot.desc', { first: dur(first.ms / first.batch), last: dur(last.ms / last.batch), cpu: dur(cpu) });
  const cpuText = dur(cpu);

  // ---- block C: split ----
  const splitLabel = (id: string): string => t(`fig.j5.split.${id}`);
  const splitBar = (view: NonNullable<typeof split>, m: Machine, attrs: Record<string, string | undefined>, part?: string): React.JSX.Element => (
    <ShareBar
      {...(part ? { part } : {})}
      head={t(`fig.j5.split.total.${m}`)}
      total={dur(view.total)}
      rows={3}
      attrs={attrs}
      segments={view.segs.map((s) => ({ id: s.id, fill: s.fill, widthPct: widthPercent(s.ms, view.total), label: splitLabel(s.id), value: dur(s.ms), share: percentText(s.ms / view.total) }))}
      {...(view.copies !== undefined ? { note: { id: 'copies', label: splitLabel('copies'), value: dur(view.copies) } } : {})}
    />
  );
  // hidden copies that give the cell its size: the tallest variant of each machine (three legend rows)
  const sizer = (m: Machine): React.JSX.Element =>
    splitBar(
      m === 'mac'
        ? { total: 1, segs: [{ id: 'gpu', fill: 'solid', ms: 0.5 }, { id: 'outside', fill: 'hatch', ms: 0.5 }], copies: 0.005 }
        : { total: 1, segs: [{ id: 'h2d', fill: 'dots', ms: 0.1 }, { id: 'kernel', fill: 'solid', ms: 0.8 }, { id: 'd2h', fill: 'dots', ms: 0.1 }] },
      m,
      {},
    );

  // ---- block D: where the project batches ----
  const w = data.where;
  const useCard = (id: UseId, text: React.ReactNode, extra?: React.ReactNode): React.JSX.Element => (
    <li className="ml-fig-j5__use" data-use={id} {...(isUseActive(id, batch, data) ? { 'data-active': '' } : {})}>
      <UseIcon use={id} />
      <b>{t(`fig.j5.where.${id}.title`)}</b>
      <p>{text}</p>
      {extra && <span className="ml-fig-j5__uselink">{extra}</span>}
    </li>
  );

  const heading = (id: string, key: string, tag?: string): React.JSX.Element => (
    <h4 className="ml-fig-j5__h" id={`${uid}-${id}`}>
      <span>{t(key)}</span>
      {tag && <span className="ml-tag">{t(tag)}</span>}
    </h4>
  );

  return (
    <Figure
      id="fig-j5"
      labelWord={figLabel(kit, number[0], number[1])}
      title={t('fig.j5.title')}
      panel={panel}
      caption={<Markup text={t('fig.j5.caption')} />}
      foot={<FigFoot kit={kit} paths={['docs/tornado.md', 'docs/cpp-comparison.md', 'nn/src/main/java/sternhalma/nn/PooledEvaluator.java']} data="j5-overhead.json" />}
    >
      <div className="ml-fig-j5" ref={root}>
        <p className="ml-visually-hidden" id={descId}>
          {f.plain('fig.j5.desc')}
        </p>

        <section className="ml-fig-j5__block" aria-labelledby={`${uid}-a`} data-block="call">
          {heading('a', 'fig.j5.call.heading', 'fig.j5.tag.derived')}
          <ShareBar part="call" head={t('fig.j5.call.total')} total={dur(p.ms)} rows={2} ariaLabel={t('fig.j5.call.aria')} attrs={{ 'data-rest-pct': String(Math.round(p.share * 100)) }} segments={callSegments} />
          <dl className="ml-fig-readout" data-part="readout">
            {cells.map((c) => (
              <div key={c.id} className="ml-fig-readout__cell" data-cell={c.id}>
                <dt>{c.label}</dt>
                <dd className="ml-fig-readout__value">{c.value}</dd>
              </div>
            ))}
          </dl>
          <div className="ml-fig-j5__verdict ml-fig-j5__stack" data-part="verdict">
            {VERDICTS.map((v) => (
              <p key={v} data-verdict={v} {...(v === p.verdict ? {} : { 'data-off': '', 'aria-hidden': 'true' as const })}>
                <b>{t(`fig.j5.verdict.${v}.head`)}</b>
                <Markup text={t(`fig.j5.verdict.${v}.text`)} />
              </p>
            ))}
          </div>
        </section>

        <section className="ml-fig-j5__block" aria-labelledby={`${uid}-b`} data-block="curve">
          {heading('b', 'fig.j5.curve.heading')}
          <div className="ml-fig-j5__plot" data-part="plot" ref={plotHolder}>
            <Plot
              title={t('fig.j5.plot.title')}
              desc={plotDesc}
              x={{ scale: 'category', categories: BATCHES.map((b) => n(b)), label: t('fig.j5.plot.axis.x') }}
              y={{ scale: 'log', domain: PLOT_DOMAIN, ticks: PLOT_TICKS, minor: false, label: t('fig.j5.plot.axis.y'), format: dur }}
              margin={{ top: 34 }}
            >
              <g data-ref="cpu">
                <PlotRef y={cpu} {...(compact ? {} : { label: t('fig.j5.plot.cpu', { value: cpuText }) })} />
              </g>
              <PlotCursor x={index} />
              {dots.map((d) => (
                <g key={d.batch} data-dot={d.batch}>
                  <PlotDots series={1} leaders dots={[d.dot]} />
                </g>
              ))}
              <Ring x={index} y={p.perPos} r={compact ? 8 : 9} />
            </Plot>
          </div>
          <Legend>
            <LegendItem mark={<circle className="ml-fig-plot__pt--1" cx="16" cy="8" r="4.5" />}>{t('fig.j5.legend.gpu')}</LegendItem>
            <LegendItem mark={<line className="ml-fig-plot__ref" x1="1" x2="31" y1="8" y2="8" />}>{t('fig.j5.legend.cpu', { value: cpuText })}</LegendItem>
            <LegendItem
              mark={
                <>
                  <circle className="ml-fig-plot__pt--1" cx="16" cy="8" r="3" />
                  <circle className="ml-fig-j5__ring" cx="16" cy="8" r="6.5" />
                </>
              }
            >
              {t('fig.j5.legend.current')}
            </LegendItem>
          </Legend>
          <table className="ml-fig-j5__table" data-part="table">
            <caption>{t('fig.j5.table.caption')}</caption>
            <thead>
              <tr>
                <th scope="col">{t('fig.j5.table.batch')}</th>
                <th scope="col">{t('fig.j5.table.call')}</th>
                <th scope="col">{t('fig.j5.table.perpos')}</th>
                <th scope="col">{t('fig.j5.table.rate')}</th>
              </tr>
            </thead>
            <tbody>
              {BATCHES.map((b) => {
                const r = rows.find((x) => x.batch === b);
                if (!r)
                  return (
                    <tr key={b} data-batch={b} data-unmeasured="">
                      <th scope="row">{n(b)}</th>
                      <td colSpan={3}>{t('fig.j5.table.unmeasured')}</td>
                    </tr>
                  );
                return (
                  <tr key={b} data-batch={b} {...(b === batch ? { 'data-current': '', 'aria-current': 'true' as const } : {})}>
                    <th scope="row">{n(b)}</th>
                    <td>{dur(r.ms)}</td>
                    <td>{dur(r.ms / b)}</td>
                    <td>{n(rateValue(r.evals))}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </section>

        <div className="ml-fig-j5__cols">
          <section className="ml-fig-j5__block" aria-labelledby={`${uid}-c`} data-block="phases">
            {heading('c', 'fig.j5.phases.heading', 'fig.j5.tag.schematic')}
            <ol className="ml-fig-j5__phases" data-part="phases">
              <Phase id="prep" scales="batch" label={t('fig.j5.phase.prep')} meaning={t('fig.j5.phases.key.batch')} />
              <li className="ml-fig-j5__group" data-group="execute">
                <span className="ml-fig-j5__grouplabel">{t('fig.j5.phases.execute')}</span>
                <ol>
                  <Phase id="copyin" scales="batch" label={t('fig.j5.phase.copyin')} meaning={t('fig.j5.phases.key.batch')} />
                  <li className="ml-fig-j5__loop" data-loop={kernels}>
                    <span className="ml-fig-j5__looplabel">{t('fig.j5.phases.loop', { kernels })}</span>
                    <ol>
                      <Phase id="launch" scales="call" label={t('fig.j5.phase.launch')} meaning={t('fig.j5.phases.key.call')} />
                      <Phase id="kernel" scales="batch" label={t('fig.j5.phase.kernel')} meaning={t('fig.j5.phases.key.batch')} />
                    </ol>
                  </li>
                  <Phase id="copyout" scales="batch" label={t('fig.j5.phase.copyout')} meaning={t('fig.j5.phases.key.batch')} />
                </ol>
              </li>
              <Phase id="sync" scales="call" label={t('fig.j5.phase.sync')} meaning={t('fig.j5.phases.key.call')} />
            </ol>
            <div className="ml-fig-j5__keys" aria-hidden="true">
              <span>
                <span className="ml-fig-share__key" data-fill="hatch" />
                {t('fig.j5.phases.key.call')}
              </span>
              <span>
                <span className="ml-fig-share__key" data-fill="solid" />
                {t('fig.j5.phases.key.batch')}
              </span>
            </div>
          </section>

          <section className="ml-fig-j5__block" aria-labelledby={`${uid}-d`} data-block="split">
            {heading('d', 'fig.j5.split.heading', 'fig.j5.tag.measured')}
            <div className="ml-fig-j5__stack">
              {split && splitBar(split, machine, { 'data-split': `${machine}-${net}-${batch}` }, 'split')}
              <div className="ml-fig-share__empty" {...(split ? { 'data-off': '', 'aria-hidden': 'true' as const } : { 'data-part': 'split', 'data-empty': '' })}>
                <span>
                  <Markup text={t('fig.j5.split.empty')} />
                </span>
              </div>
              <div data-off="" aria-hidden="true">
                {sizer('mac')}
              </div>
              <div data-off="" aria-hidden="true">
                {sizer('t4')}
              </div>
            </div>
            <p className="ml-fig-j5__note">
              <Markup text={t('fig.j5.split.note')} />
            </p>
          </section>
        </div>

        <section className="ml-fig-j5__block" aria-labelledby={`${uid}-e`} data-block="where">
          {heading('e', 'fig.j5.where.heading')}
          <ul className="ml-fig-j5__where" data-part="where">
            {useCard(
              'pool',
              <Markup
                text={t('fig.j5.where.pool.text', {
                  games: n(w.pool.games),
                  cohorts: n(w.pool.cohorts),
                  batch: n(w.pool.batch),
                  quiet: dur(w.pool.quietMicros / 1000),
                  maxWait: dur(w.pool.maxWaitMs),
                })}
              />,
              <a href={POOL_FIGURE_HREF}>{t('fig.j5.where.pool.link', { fig: POOL_FIGURE_NUMBER })}</a>,
            )}
            {useCard(
              'tree',
              <Markup
                text={t('fig.j5.where.tree.text', {
                  low: n(w.tree.batchBelow800),
                  high: n(w.tree.batchAt800),
                  lossLow: fixed2(w.tree.lossBatch16Sims200[0]),
                  lossHigh: fixed2(w.tree.lossBatch16Sims200[1]),
                })}
              />,
              <span className="ml-tag">{t('fig.j5.where.tree.tag')}</span>,
            )}
            {useCard('one', <Markup text={t('fig.j5.where.one.text')} />)}
          </ul>
        </section>
      </div>
    </Figure>
  );
}
