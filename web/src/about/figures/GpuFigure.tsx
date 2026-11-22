// Figure J4 of topic 6 (java): the anatomy of a GPU as five zoom levels (the machine, the inside of the GPU, one unit, one warp, the kernel grid),
// a hardware picker (M3 Max, T4, RTX 5090) and a stepper with eight steps that follows one call from the Java array to one GPU thread and back.
// Spec: design/j4-gpu/spec.md. Numbers: data/gpu-anatomy.json through gpu-model.ts. Text: catalog keys `fig.gpu.*` of i18n/{en,de}/java.json.
// Stable layout: the narration keeps the height of the longest text of any step and any machine (ghost lines); in the compact layout the five
// levels lie in one grid cell, the others hidden and inert; a value never changes with the step.
import { useId, useRef, useState } from 'react';
import { Figure, FigureDetail, Legend, LegendItem, Manipulate, Segmented, Stepper, stepperLabels, useStepper } from '../kit';
import { Markup } from '../kit/markup';
import { FigFoot, figLabel, useFigText } from './common';
import type { Catalog } from './common';
import { useCompact } from './shared';
import { ChainPicture, CopyArrow, DepthMark, DistanceLadder, Pipe, SimtField, UnitField, WarpBars } from './gpu-parts';
import {
  CMP_ROWS,
  DEFAULT_HW,
  GROUP_THREADS,
  GROUP_WARPS,
  HW_IDS,
  MACHINES,
  SPARK,
  STEP_COUNT,
  WARP_SIZE,
  akaText,
  cmpCell,
  cyclesText,
  gridCounts,
  ladderRows,
  narrationKey,
  narrationValues,
  partFacts,
  partState,
  partsOf,
  quantityText,
  regsText,
  roundedCount,
  stepAt,
} from './gpu-model';
import type { HwId, PartState } from './gpu-model';
import './gpu.css';

export interface GpuFigureProps {
  catalog: Catalog;
  number: readonly [number, number];
  /** Initial hardware; the figure starts on the T4. */
  initialHw?: HwId;
}

const DASH = '–';
/** The two lines of KernelsHexConv.java:98-99, shortened: a literal, not catalog text. */
const CODE = 'for (@Parallel int r …)\n  for (@Parallel int o …)';

export function GpuFigure({ catalog, number, initialHw = DEFAULT_HW }: GpuFigureProps): React.JSX.Element {
  const f = useFigText(catalog);
  const { t, kit, n } = f;
  const uid = useId();
  const { ref, compact } = useCompact();
  const [hw, setHw] = useState<HwId>(initialHw);
  const [detail, setDetail] = useState<string | null>(null);
  const opener = useRef<HTMLElement | null>(null);
  const { state, dispatch } = useStepper(STEP_COUNT);
  const step = state.step;
  const cur = stepAt(step);
  const m = MACHINES[hw];
  const unified = m.kind === 'unified';
  const atMost = (value: string): string => t('fig.gpu.unit.atmost', { n: value });
  const q = (x: Parameters<typeof quantityText>[0]): string => quantityText(x, n, atMost);
  const unknown = t('fig.gpu.unknown');
  const detailId = `${uid}-detail`;
  const openPart = detail !== null && partsOf(hw).has(detail) ? detail : null;
  const stateOf = (id: string): PartState => partState(hw, step, id);
  const unitsText = (h: HwId): string => t(MACHINES[h].kind === 'unified' ? 'fig.gpu.unit.cores' : 'fig.gpu.unit.sm', { n: n(MACHINES[h].units) });

  const close = (): void => {
    const back = opener.current;
    setDetail(null);
    if (back) requestAnimationFrame(() => back.focus());
  };
  const toggle = (id: string, el: HTMLElement): void => {
    opener.current = el;
    if (openPart === id) close();
    else setDetail(id);
  };

  /** A part of the anatomy: always a button; `value` undefined has no value line, null is a value that the maker does not publish.
   *  A plain function, not a component: a component declared here would be a new type on every render and remount the button (and drop its focus). */
  const part = (id: string, o: { value?: string | null; cls?: string } = {}, children?: React.ReactNode): React.JSX.Element => {
    const st = stateOf(id);
    const { value, cls = '' } = o;
    const classes = ['ml-fig-part', cls, value === null ? 'ml-fig-part--unknown' : ''].filter(Boolean).join(' ');
    return (
      <button type="button" key={id} className={classes} data-part={id} {...(st ? { 'data-state': st } : {})} aria-expanded={openPart === id} aria-controls={detailId} onClick={(e) => toggle(id, e.currentTarget)}>
        <span>{t(`fig.gpu.part.${id}`)}</span>
        {value !== undefined && <span className="ml-fig-part__v">{value ?? unknown}</span>}
        {children}
      </button>
    );
  };

  const link = m.link;
  const pipeState: PartState = step === 0 ? undefined : cur.slots.includes('link') ? 'active' : 'dim';
  const memLine = `${q(m.mem)} · ${q(m.memRate)}`;

  // ---- the five levels ----
  const level1 = unified ? (
    <div className="gp-sys" data-kind="unified">
      <div className="gp-sys__cap">
        <span>{t('fig.gpu.sys.chip')}</span>
      </div>
      <div className="gp-sys__grp gp-sys__grp--host">
        {part('cpu', { value: 'JVM' })}
      </div>
      <div />
      <div className="gp-sys__grp gp-sys__grp--gpu">
        {part('gpu', { value: unitsText(hw) })}
      </div>
      {part('umem', { value: memLine, cls: 'ml-fig-part--store gp-sys__umem' }, (
<>
        <span className="gp-sys__in">
          <span className="c">{t('fig.gpu.sys.arrays')}</span>
          <CopyArrow state={pipeState} />
          <span className="c">{t('fig.gpu.sys.buffers')}</span>
        </span>
      </>
))}
      {part('copy', { value: `< ${n(m.copyBelowMs ?? 0)} ms`, cls: 'ml-fig-part--plain gp-sys__link' })}
    </div>
  ) : (
    <div className="gp-sys" data-kind="discrete">
      <div className="gp-sys__cap">
        <span>{t('fig.gpu.sys.host')}</span>
        <span>{t('fig.gpu.sys.card')}</span>
      </div>
      <div className="gp-sys__grp gp-sys__grp--host">
        {part('cpu', { value: 'JVM' })}
        {part('hostmem', { value: 'FloatArray', cls: 'ml-fig-part--store' })}
      </div>
      <Pipe thickness={link?.thickness ?? 4} dir={cur.dir} state={pipeState} />
      <div className="gp-sys__grp gp-sys__grp--gpu">
        {part('gpu', { value: unitsText(hw) })}
        {part('devmem', { value: q(m.mem), cls: 'ml-fig-part--store' })}
      </div>
      {part('link', { value: link ? `${link.name} · ${q(link.rate)}` : null, cls: 'ml-fig-part--plain gp-sys__link' })}
    </div>
  );

  const level2 = (
    <>
      {part(unified ? 'umem' : 'devmem', { value: memLine, cls: 'ml-fig-part--store' })}
      {unified ? part('cache', { value: null }) : part('l2', { value: m.l2 ? q(m.l2) : null, cls: m.l2 ? 'ml-fig-part--store' : '' })}
      {part(unified ? 'core' : 'sm', { cls: 'ml-fig-part--field' }, (
<>
        <span className="ml-fig-part__v ml-fig-part__row">
          <span>× {n(m.units)}</span>
          <span>{m.cudaCores !== null ? t('fig.gpu.unit.cudacores', { n: n(m.cudaCores) }) : ''}</span>
        </span>
        <UnitField units={m.units} />
      </>
))}
    </>
  );

  const level3 = (
    <>
      <div className="gp-two">
        {unified ? part('tgmem', { value: m.threadgroup ? q(m.threadgroup.mem) : null, cls: 'ml-fig-part--store' }) : part('l1', { value: m.l1 ? q(m.l1) : null, cls: 'ml-fig-part--store' })}
        {part('reg', { value: m.regs ? regsText(m.regs, n) : null })}
      </div>
      {part(unified ? 'simd' : 'warp', { cls: 'ml-fig-part--field' }, (
<>
        <span className="ml-fig-part__v ml-fig-part__row">
          <span>{m.maxWarps !== null ? t('fig.gpu.unit.atmost', { n: n(m.maxWarps) }) : unknown}</span>
          <span>{t('fig.gpu.unit.threads', { n: n(WARP_SIZE) })}</span>
        </span>
        <WarpBars known={m.maxWarps !== null} />
      </>
))}
    </>
  );

  const ladderId = `${uid}-ladder`;
  const ladder = ladderRows(hw);
  const level4 = (
    <>
      {part('simt', { cls: 'ml-fig-part--field' }, (
<>
        <span className="ml-fig-part__v">{`${n(WARP_SIZE)} × 1`}</span>
        <SimtField lineActive={cur.id === 'warp'} />
      </>
))}
      {part('thread', { value: 'i = 13' })}
      <div>
        <div className="ml-fig-zoom__head" id={ladderId}>
          {t('fig.gpu.ladder.title')}
        </div>
        <DistanceLadder
          titleId={ladderId}
          rows={ladder.rows}
          reserved={ladder.reserved}
          active={cur.id === 'thread'}
          label={(id) => t(`fig.gpu.part.${id}`)}
          value={(r) => (r.cycles ? t('fig.gpu.ladder.cycles', { n: cyclesText(r.cycles.lo, r.cycles.hi, n) }) : DASH)}
          reserveLabel={t('fig.gpu.part.l2')}
          dash={DASH}
        />
      </div>
    </>
  );

  const b1 = gridCounts(hw, 1);
  const b256 = gridCounts(hw, 256);
  const about = (v: number): string => `≈${n(roundedCount(v))}`;
  const gridRows: [string, string, string][] = [
    ['variant', b1.variant, b256.variant],
    ['threads', about(b1.threads), about(b256.threads)],
    ['groups', b1.groups === null ? DASH : about(b1.groups), b256.groups === null ? DASH : about(b256.groups)],
    ['warps', about(b1.warps), about(b256.warps)],
    ['perunit', about(b1.perUnit), about(b256.perUnit)],
  ];
  const level5 = (
    <div className="gp-grid">
      <div className="ml-fig-zoom__body">
        <pre className="gp-code" role="group" aria-label={t('fig.gpu.grid.code.label')}>
          {CODE}
        </pre>
        <ChainPicture id={`${uid}-chain`} active={cur.id === 'grid'} title={t('fig.gpu.grid.svg.title')} desc={f.plain('fig.gpu.grid.svg.desc')} />
        <Legend>
          <LegendItem mark={<rect className="gp-unit gp-unit--cur" x="4" y="4" width="24" height="8" />}>{t('fig.gpu.legend.group')}</LegendItem>
          <LegendItem mark={<rect className="gp-line" x="1" y="3" width="30" height="10" rx="2" />}>{t('fig.gpu.legend.warp')}</LegendItem>
          <LegendItem mark={<rect className="gp-fillacc" x="14" y="2" width="4" height="12" />}>{t('fig.gpu.legend.thread')}</LegendItem>
        </Legend>
        {part('group', { value: `${n(GROUP_THREADS)} = ${n(GROUP_WARPS)} × ${n(WARP_SIZE)}` })}
      </div>
      <table className="gp-num" data-table="grid">
        <caption className="ml-visually-hidden">{t('fig.gpu.grid.table.caption')}</caption>
        <thead>
          <tr>
            <th scope="col">
              <span className="ml-visually-hidden">{t('fig.gpu.grid.col.what')}</span>
            </th>
            <th scope="col">{t('fig.gpu.grid.col', { batch: n(1) })}</th>
            <th scope="col">{t('fig.gpu.grid.col', { batch: n(256) })}</th>
          </tr>
        </thead>
        <tbody>
          {gridRows.map(([id, one, many]) => (
            <tr key={id} data-row={id}>
              <th scope="row">{t(`fig.gpu.grid.row.${id}`)}</th>
              <td>{one}</td>
              <td>{many}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );

  const levels: { n: number; title: string; body: React.ReactNode; cls?: string }[] = [
    { n: 1, title: 'fig.gpu.level.system', body: level1 },
    { n: 2, title: 'fig.gpu.level.gpu', body: level2 },
    { n: 3, title: unified ? 'fig.gpu.level.core' : 'fig.gpu.level.sm', body: level3 },
    { n: 4, title: unified ? 'fig.gpu.level.simd' : 'fig.gpu.level.warp', body: level4 },
    { n: 5, title: 'fig.gpu.level.grid', body: level5, cls: 'ml-fig-zoom__panel--grid' },
  ];

  // ---- the detail panel ----
  const facts = openPart ? partFacts(hw, openPart, n, atMost) : [];
  const aka = openPart ? akaText(openPart) : null;
  const detailPanel = openPart ? (
    <FigureDetail id={detailId} kicker={m.name} title={t(`fig.gpu.part.${openPart}`)} closeLabel={kit('kit.detail.close')} onClose={close}>
      <p>
        <Markup text={t(`fig.gpu.part.${openPart}.what`)} />
      </p>
      <p className="gp-detail__java">
        <span className="gp-tag">{t('fig.gpu.detail.java')}</span>
        <span>
          <Markup text={t(`fig.gpu.part.${openPart}.java`)} />
        </span>
      </p>
      {aka && (
        <p className="gp-detail__aka">
          {t('fig.gpu.detail.aka')} <span>{aka}</span>
        </p>
      )}
      {facts.length > 0 ? (
        <dl className="gp-detail__facts">
          {facts.map((x) => (
            <div key={x.key}>
              <dt>{t(`fig.gpu.fact.${x.key}`)}</dt>
              <dd>{x.value}</dd>
            </div>
          ))}
        </dl>
      ) : (
        <p>{t('fig.gpu.unknown.long')}</p>
      )}
    </FigureDetail>
  ) : (
    <div id={detailId} hidden />
  );

  // ---- the comparison table ----
  const cmp = (
    <>
      <table className="ml-fig-cmptable" data-table="cmp">
        <caption>{t('fig.gpu.cmp.caption')}</caption>
        <thead>
          <tr>
            <th scope="col">
              <span className="ml-visually-hidden">{t('fig.gpu.cmp.col.what')}</span>
            </th>
            {HW_IDS.map((id) => (
              <th key={id} scope="col" {...(id === hw ? { 'data-sel': '' } : {})}>
                <span className="h">{MACHINES[id].short}</span>
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {CMP_ROWS.map((row) => (
            <tr key={row} data-row={row} {...(cur.rows.includes(row) ? { 'data-state': 'active' } : {})}>
              <th scope="row">{t(`fig.gpu.cmp.row.${row}`)}</th>
              {HW_IDS.map((id) => {
                const cell = cmpCell(row, id, n, atMost);
                const text = cell.kind === 'text' ? cell.text : cell.kind === 'units' ? unitsText(id) : cell.kind === 'none' ? t('fig.gpu.cmp.none') : DASH;
                return (
                  <td key={id} {...(id === hw ? { 'data-sel': '' } : {})}>
                    {text}
                  </td>
                );
              })}
            </tr>
          ))}
        </tbody>
      </table>
      <ul className="ml-fig-cmptable__notes">
        <li>
          <Markup text={t('fig.gpu.cmp.note.unknown')} />
        </li>
        <li>
          <Markup text={t('fig.gpu.cmp.note.project')} />
        </li>
        <li>
          <Markup text={t('fig.gpu.cmp.note.spark', { mem: q(SPARK.mem), bw: q(SPARK.rate) })} />
        </li>
      </ul>
    </>
  );

  // ---- narration: the live line and one ghost per text that any step or machine can show, so that the area never changes height ----
  const stepLabel = (k: number): string => kit('kit.stepper.step', { k, n: STEP_COUNT });
  const narrate = (h: HwId, k: number): string => f.plain(narrationKey(h, k), narrationValues(h, n, atMost));
  const seen = new Set<string>();
  const ghosts: { step: string; text: string }[] = [];
  for (const h of HW_IDS) {
    for (let k = 0; k <= STEP_COUNT; k++) {
      const line = { step: k > 0 ? stepLabel(k) : '', text: narrate(h, k) };
      const key = `${line.step}|${line.text}`;
      if (seen.has(key)) continue;
      seen.add(key);
      ghosts.push(line);
    }
  }
  const status = {
    ...(step > 0 ? { step: stepLabel(step) } : {}),
    text: narrate(hw, step),
    all: ghosts.map((g) => ({ ...(g.step ? { step: g.step } : {}), text: g.text })),
  };

  const onKeyDown = (event: React.KeyboardEvent<HTMLElement>): void => {
    if (event.key === 'Escape' && openPart) {
      event.stopPropagation();
      close();
    }
  };
  const choose = (next: HwId): void => {
    setHw(next);
    if (detail !== null && !partsOf(next).has(detail)) setDetail(null);
  };

  return (
    <Figure
      id="fig-gpu"
      labelWord={figLabel(kit, number[0], number[1])}
      title={t('fig.gpu.title')}
      panel={
        <Manipulate>
          <Segmented<HwId> label={t('fig.gpu.hw.label')} options={HW_IDS.map((id) => ({ value: id, label: MACHINES[id].short }))} value={hw} onChange={choose} />
          <Stepper state={state} dispatch={dispatch} labels={stepperLabels(f.kitT, t('fig.gpu.stepper.label'))} />
        </Manipulate>
      }
      status={status}
      caption={<Markup text={t('fig.gpu.caption')} />}
      foot={<FigFoot kit={kit} paths={['docs/tornado.md', 'nn-tornado/src/main/java/sternhalma/tornado/KernelsHexConv.java']} data="gpu-anatomy.json" />}
    >
      <div ref={ref} className="gp-stage" data-hw={hw} data-step={cur.id} onKeyDown={onKeyDown}>
        <div className="ml-fig-zoom" role="group" aria-label={t('fig.gpu.anat.label')}>
          {levels.map((lv) => {
            const current = lv.n === cur.level;
            const headId = `${uid}-lv${lv.n}`;
            return (
              <section
                key={lv.n}
                className={`ml-fig-zoom__panel${lv.cls ? ` ${lv.cls}` : ''}`}
                data-panel={lv.n}
                {...(current && step > 0 ? { 'data-cur': '' } : {})}
                {...(current ? {} : { 'data-off': '' })}
                inert={compact && !current}
                aria-labelledby={headId}
              >
                <h4 className="ml-fig-zoom__head" id={headId}>
                  <DepthMark level={lv.n} />
                  <span>{t(lv.title)}</span>
                </h4>
                <div className="ml-fig-zoom__body">{lv.body}</div>
              </section>
            );
          })}
        </div>
        {detailPanel}
        <div className="gp-below">{cmp}</div>
      </div>
    </Figure>
  );
}

