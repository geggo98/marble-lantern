// Figure J3 of topic 6 (java): SIMD with the Vector API. One pass of the inner loop of the hex convolution as a grid of products (4 board cells by the
// 32 output channels of SMALL). A slider sets the lanes per register (1, 4, 8, 16), a stepper walks the loop body (2 loads, 4 broadcasts, 8 FMAs, the
// stores), a number line puts the lane count beside the measured multiple of scalar Java. Texts come from the catalog (`fig.simd.*`); the numbers
// from data/java-simd.json through simd-model.ts. Layout: the narration, the code strip and the note of the number line keep their height (ghosts in
// one grid cell), and the view box of the grid depends on the layout only, so the figure never jumps.
import { useLayoutEffect, useRef, useState } from 'react';
import { Figure, Legend, LegendItem, Manipulate, Presets, Slider, Stepper, stepperLabels, useMeasuredWidth, useStepper } from '../kit';
import { Markup } from '../kit/markup';
import { SourceChip } from '../kit/SourceChip';
import { FigFoot, figLabel, useFigText } from './common';
import type { Catalog } from './common';
import {
  AXIS_MAX, CELLS, CHANNELS, GRID_PRODUCTS, LANE_STOPS, LAST_STEP, MACHINE_NOTES, MEAS, MEAS_VIEW, TILE_REGISTERS, WIDE_FROM_PX, accState, codeLines, fmaIndex, fmasDone, fmasForRow,
  gridGeometry, inputState, laneIndex, lanesAt, longestNarration, machineNote, measX, narration, registerBits, stepKind, weightState, DEFAULT_LANES, CODE_FILE,
} from './simd-model';
import type { CodeLine, Lanes, SimdLayout } from './simd-model';
import './simd.css';

export interface SimdFigureProps {
  catalog: Catalog;
  number: readonly [number, number];
}

const r1 = (v: number): number => Math.round(v * 10) / 10;

/** One source line: number, then the text with the keywords and the identifiers of the current step marked. */
function CodeText({ line }: { line: CodeLine }): React.JSX.Element {
  const re = new RegExp(`\\b(${['for', 'int', 'FloatVector', ...line.hl].join('|')})\\b`);
  return (
    <>
      <span className="ln">{line.n === null ? '   ' : line.n} </span>
      {line.text.split(re).map((part, i) => (i % 2 === 0 ? part : <span key={i} className={line.hl.includes(part) ? 'op' : 'kw'}>{part}</span>))}
    </>
  );
}

function CodeLines({ lines }: { lines: readonly CodeLine[] }): React.JSX.Element {
  return (
    <>
      {lines.map((l, i) => (
        <span key={i}>
          {i > 0 && '\n'}
          <CodeText line={l} />
        </span>
      ))}
    </>
  );
}

/** The steps whose code strips stand as ghosts: one per kind, the load and broadcast steps with the two long lines. */
const GHOST_CODE_STEPS = [0, 1, 2, 3, LAST_STEP];

function ProductGrid({ layout, lanes, step, title, desc }: { layout: SimdLayout; lanes: Lanes; step: number; title: string; desc: string }): React.JSX.Element {
  const g = gridGeometry(layout, lanes);
  const kind = stepKind(step);
  const f = fmaIndex(step);
  const tileCols = TILE_REGISTERS * lanes;
  const dotR = (): number => r1(Math.min(2, g.box / 3.2));
  const vecX = (j: number): number => g.x(j * lanes);
  const vecW = lanes * g.col - 1;
  const narrow = vecW < 16;
  const mid = g.x(lanes - 1) + g.box + g.gv / 2;
  const cc = f >> 1;
  const cj = f & 1;
  return (
    <svg className="ml-fig-simd__grid" viewBox={`0 0 ${g.W} ${g.H}`} role="img">
      <title>{title}</title>
      <desc>{desc}</desc>
      <rect className="tileband" x={r1(g.x(0) - 3)} y={g.yHead - 3} width={r1(g.x(tileCols - 1) + g.box - g.x(0) + 6)} height={g.bottom - g.yHead + 6} rx={3} />
      {Array.from({ length: g.tiles }, (_, t) => {
        const x0 = r1(g.x(t * tileCols));
        const x1 = r1(g.x(t * tileCols + tileCols - 1) + g.box);
        return <path key={t} className="tile" {...(t === 0 ? { 'data-current': '' } : {})} data-tile={t} d={`M${x0},9 V4 H${x1} V9`} />;
      })}
      {[0, 1].map((j) => {
        const tx = narrow ? (j ? mid + 2 : mid - 2) : vecX(j) + vecW / 2;
        return (
          <text key={j} className="name" data-state={weightState(step, j)} x={r1(tx)} y={21} textAnchor={narrow ? (j ? 'start' : 'end') : 'middle'}>
            {`w${j}`}
          </text>
        );
      })}
      {Array.from({ length: CHANNELS }, (_, ch) => {
        const first = ch < tileCols;
        const st = first ? weightState(step, Math.floor(ch / lanes) & 1) : 'none';
        return (
          <g key={ch}>
            <rect className="wt" data-state={first ? st : 'none'} x={r1(g.x(ch))} y={g.yHead} width={r1(g.box)} height={g.hh} rx={1} />
            {(st === 'loaded' || st === 'current') && <circle className="dot" data-state={st} cx={r1(g.x(ch) + g.box / 2)} cy={g.yHead + g.hh / 2} r={dotR()} />}
          </g>
        );
      })}
      {Array.from({ length: CELLS }, (_, c) => {
        const vs = inputState(step, c);
        return (
          <g key={c}>
            <rect className="vbox" data-state={vs} x={1} y={g.y(c)} width={g.GL - 7} height={g.rh} rx={2} />
            <text className="name" data-state={vs} x={(g.GL - 5) / 2} y={g.y(c) + g.rh / 2 + 4.2} textAnchor="middle">
              {`v${c}`}
            </text>
            {Array.from({ length: CHANNELS }, (_, ch) => {
              const first = ch < tileCols;
              const j = Math.floor(ch / lanes) & 1;
              const st = first ? accState(step, c * 2 + j) : 'none';
              return (
                <g key={ch}>
                  <rect className="lane" data-state={st} data-acc={first ? `a${c}${j}` : ''} x={r1(g.x(ch))} y={g.y(c)} width={r1(g.box)} height={g.rh} rx={1} />
                  {st === 'pending' && step >= 2 && <circle className="dot" data-state={kind === 'bcast' ? 'current' : 'loaded'} cx={r1(g.x(ch) + g.box / 2)} cy={g.y(c) + g.rh / 2} r={dotR()} />}
                </g>
              );
            })}
          </g>
        );
      })}
      {kind === 'fma' && (
        <>
          <rect className="ring" x={r1(vecX(cj) - 2)} y={g.y(cc) - 2} width={r1(vecW + 4)} height={g.rh + 4} rx={2.5} />
          <path className="ptr" d={`M${r1(vecX(cj) + vecW / 2)},${g.bottom + 4} l5,7 h-10z`} />
        </>
      )}
    </svg>
  );
}

function NumberLine({ layout, lanes, title, desc }: { layout: SimdLayout; lanes: Lanes; title: string; desc: string }): React.JSX.Element {
  const { W, H } = MEAS_VIEW[layout];
  const yA = 44;
  const x = (v: number): number => r1(measX(layout, v));
  return (
    <svg viewBox={`0 0 ${W} ${H}`} role="img">
      <title>{title}</title>
      <desc>{desc}</desc>
      <path className="axis" d={`M${x(0)},${yA} H${x(AXIS_MAX)}`} />
      {LANE_STOPS.map((t) => (
        <g key={t}>
          <path className="axis" d={`M${x(t)},${yA} v4`} />
          <text className={t === lanes ? 'cur' : ''} x={x(t)} y={68} textAnchor="middle">
            {t}
          </text>
        </g>
      ))}
      <path className="link" d={`M${x(MEAS.lanes)},${yA - 5} V22 H${r1(x(MEAS.lo) - 5)}`} />
      <circle className="home" cx={x(MEAS.lanes)} cy={yA} r={4} />
      <path className="whisk" d={`M${x(MEAS.lo)},22 H${x(MEAS.hi)} M${x(MEAS.lo)},18 v8 M${x(MEAS.hi)},18 v8`} />
      <circle className="mdot" cx={x(MEAS.dot)} cy={22} r={4.5} />
      <text className="big" x={x(MEAS.dot)} y={12} textAnchor="middle">
        {`${Math.round(MEAS.dot)}×`}
      </text>
      <path className="ptr" data-marker="lanes" d={`M${x(lanes)},${yA + 5} l5,8 h-10z`} />
    </svg>
  );
}

export function SimdFigure({ catalog, number }: SimdFigureProps): React.JSX.Element {
  const f = useFigText(catalog);
  const { t, kit } = f;
  const [lanes, setLanes] = useState<Lanes>(DEFAULT_LANES);
  const { state, dispatch } = useStepper(LAST_STEP);
  const { step, playing } = state;
  const holder = useRef<HTMLDivElement>(null);
  const width = useMeasuredWidth(holder, '.ml-fig');
  const layout: SimdLayout = width >= WIDE_FROM_PX ? 'wide' : 'compact';
  const kind = stepKind(step);

  // The test hooks of the spec stand on the figure element, which the kit's Figure does not take props for.
  useLayoutEffect(() => {
    const fig = holder.current?.closest<HTMLElement>('.ml-fig');
    if (!fig) return;
    fig.dataset.lanes = String(lanes);
    fig.dataset.step = String(step);
    fig.dataset.kind = kind;
    fig.dataset.layout = layout;
  }, [lanes, step, kind, layout]);

  const stepText = (k: number): string => kit('kit.stepper.step', { k, n: LAST_STEP });
  const say = (n: { key: string; values: Record<string, string | number> }): string => f.plain(n.key, n.values);
  const now = narration(step, lanes);
  const done = fmasDone(step);
  const grid = gridGeometry(layout, lanes);
  const noteNow = machineNote(lanes);
  const code = codeLines(step);

  return (
    <Figure
      id="fig-simd"
      labelWord={figLabel(kit, number[0], number[1])}
      title={t('fig.simd.title')}
      panel={
        <Manipulate>
          <Slider
            label={t('fig.simd.lanes.label')}
            min={0}
            max={LANE_STOPS.length - 1}
            value={laneIndex(lanes)}
            onChange={(i) => setLanes(lanesAt(i))}
            valueText={f.plain('fig.simd.lanes.value', { lanes, bits: registerBits(lanes) })}
            out={`${lanes} × 32 = ${registerBits(lanes)} bit`}
            scale={LANE_STOPS.map(String)}
          />
          <Presets
            label={t('fig.simd.pre.label')}
            presets={[
              { id: '4', label: t('fig.simd.pre.m3') },
              { id: '16', label: t('fig.simd.pre.avx') },
            ]}
            pressed={lanes === 4 ? '4' : lanes === 16 ? '16' : null}
            onPick={(id) => setLanes(Number(id) as Lanes)}
          />
          <Stepper state={state} dispatch={dispatch} labels={stepperLabels(f.kitT, t('fig.simd.stepper'))} />
        </Manipulate>
      }
      status={{
        step: stepText(step),
        text: say(now),
        all: longestNarration().map((g) => ({ step: stepText(LAST_STEP), text: say(g) })),
        live: !playing,
      }}
      caption={<Markup text={t('fig.simd.caption')} />}
      foot={<FigFoot kit={kit} paths={[CODE_FILE, 'nn/src/main/java/sternhalma/nn/VectorSupport.java', 'docs/network.md']} data="java-simd.json" />}
    >
      <div className="ml-fig-simd" ref={holder} data-lanes={lanes}>
        <div className="ml-fig-simd__code">
          {GHOST_CODE_STEPS.map((s) => (
            <pre key={s} aria-hidden="true" data-ghost={stepKind(s)}>
              <code>
                <CodeLines lines={codeLines(s)} />
              </code>
            </pre>
          ))}
          <pre data-live-code="">
            <code>
              <CodeLines lines={code} />
            </code>
          </pre>
        </div>
        <ProductGrid layout={layout} lanes={lanes} step={step} title={t('fig.simd.title')} desc={f.plain('fig.simd.grid.desc', { tiles: grid.tiles, lanes })} />
        <Legend>
          <LegendItem mark={<path className="tile" data-current="" d="M3,12 V5 H29 V12" />}>{t('fig.simd.legend.tile')}</LegendItem>
          <LegendItem
            mark={
              <>
                <rect className="wt" data-state="loaded" x={10} y={2} width={12} height={12} rx={1} />
                <circle className="dot" cx={16} cy={8} r={2} />
              </>
            }
          >
            {t('fig.simd.legend.value')}
          </LegendItem>
          <LegendItem mark={<rect className="lane" data-state="done" x={10} y={2} width={12} height={12} rx={1} />}>{t('fig.simd.legend.done')}</LegendItem>
          <LegendItem
            mark={
              <>
                {[5, 12, 19].map((x) => (
                  <rect key={x} className="lane" data-state="current" x={x} y={3} width={6} height={10} />
                ))}
                <rect className="ring" x={3} y={1} width={24} height={14} rx={2.5} />
              </>
            }
          >
            {t('fig.simd.legend.current')}
          </LegendItem>
        </Legend>
        <div className="ml-fig-simd__lower">
          <dl className="ml-fig-readout">
            {[
              { label: t('fig.simd.read.register'), value: `${lanes} × 32 bit`, sub: `= ${registerBits(lanes)} bit`, id: 'register' },
              { label: t('fig.simd.read.pass'), value: `${done} / ${CELLS * TILE_REGISTERS}`, sub: `${done * lanes} / ${CELLS * TILE_REGISTERS * lanes} ${t('fig.simd.read.products')}`, id: 'pass' },
              { label: t('fig.simd.read.row'), value: String(fmasForRow(lanes)), sub: `${GRID_PRODUCTS} ${t('fig.simd.read.products')}`, id: 'row' },
            ].map((c) => (
              <div key={c.id} className="ml-fig-readout__cell" data-readout={c.id}>
                <dt>{c.label}</dt>
                <dd className="ml-fig-readout__value">{c.value}</dd>
                <dd className="ml-fig-simd__sub">{c.sub}</dd>
              </div>
            ))}
          </dl>
          <div className="ml-fig-simd__meas">
            <NumberLine layout={layout} lanes={lanes} title={t('fig.simd.title')} desc={f.plain('fig.simd.meas.desc', { lanes })} />
            <ul className="ml-fig-simd__facts">
              <li>
                <svg viewBox="0 0 32 20" aria-hidden="true" focusable="false">
                  <path className="ptr" d="M16,5 l6,10 h-12z" />
                </svg>
                <span>{f.plain('fig.simd.meas.lanes', { lanes })}</span>
              </li>
              <li>
                <svg viewBox="0 0 32 20" aria-hidden="true" focusable="false">
                  <path className="whisk" d="M4,10 H28 M4,6 v8 M28,6 v8" />
                  <circle className="mdot" cx={16} cy={10} r={4.5} />
                </svg>
                <span>
                  <Markup text={t('fig.simd.meas.measured', { lo: MEAS.textLo, hi: MEAS.textHi })} /> <SourceChip path="docs/network.md" lines={[480, 480]} />
                </span>
              </li>
              <li>
                <svg viewBox="0 0 32 20" aria-hidden="true" focusable="false">
                  <path className="link" d="M4,10 H28" />
                </svg>
                <span className="ml-fig-simd__slot">
                  {MACHINE_NOTES.map((m) => (
                    <span key={m} data-note={m} {...(m === noteNow ? {} : { 'aria-hidden': 'true' })}>
                      <Markup text={t(`fig.simd.meas.note.${m}`)} />
                    </span>
                  ))}
                </span>
              </li>
            </ul>
          </div>
        </div>
      </div>
    </Figure>
  );
}
