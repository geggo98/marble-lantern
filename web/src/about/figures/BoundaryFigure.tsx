// Figure J7 of topic java-web (Fig. 7.2): one call of choose() from the page to a browser engine and back, across two boundaries: the thread
// boundary (page to Web Worker, messages) and the call boundary (TypeScript wrapper to engine module, a function call). A switch selects one
// of three engine builds and changes only the call boundary. Design: design spec j7-teavm (spec.md, mock.html); model: boundary-model.ts.
// Stable layout (spec section 9): the narration is one grid cell with ghosts, every text that changes per step or per variant stands in a
// GhostCell, and the direction badges are always laid out. Texts: catalog keys `fig.boundary.*` (about/i18n/{en,de}/java-web.json).
import { useId, useLayoutEffect, useRef, useState } from 'react';
import { Chip, Figure, Legend, LegendItem, Manipulate, Readout, Segmented, Stepper, stepperLabels, useStepper } from '../kit';
import { GhostCell, Lane, LaneDiagram, LaneGroup, StableBold, Wall } from '../kit/LaneDiagram';
import { Markup } from '../kit/markup';
import { sourceUrl } from '../kit/SourceChip';
import { figLabel, useFigText } from './common';
import type { Catalog } from './common';
import { javaWebCatalog } from './java-web-catalog';
import {
  ALSO,
  DEFAULT_VARIANT,
  ENGINE_FILE,
  GLUE_FILE,
  HOWS,
  HOW_MARK,
  LANES,
  PAGE_FILE,
  STEPS,
  STEP_COUNT,
  TABLE_ROWS,
  THREAD_STATES,
  VARIANTS,
  WALL_KIND,
  callsText,
  narrationKey,
  payload,
  rowOfStep,
} from './boundary-model';
import type { Lane as LaneId, PayLine, RowId, ThreadState, Variant } from './boundary-model';
import './boundary.css';

export interface BoundaryFigureProps {
  catalog?: Catalog;
  /** Topic number and figure number in the topic ("Fig. 7.2"). */
  number?: readonly [number, number];
  initialVariant?: Variant;
}

const SOURCES = [
  'web/src/engines/client.ts',
  'web/src/engines/java/core.ts',
  'web/src/engines/cpp/core.ts',
  'teavm-web/src/main/java/sternhalma/web/Web.java',
] as const;

/** The mark of a thread state (spec section 4): hollow circle, circle with a dot, filled disc, two bars. */
const PILL_MARK: Record<ThreadState, React.JSX.Element> = {
  idle: <circle className="p-stroke" cx="6" cy="6" r="4.2" />,
  free: (
    <>
      <circle className="p-stroke" cx="6" cy="6" r="4.2" />
      <circle className="p-fill" cx="6" cy="6" r="1.6" />
    </>
  ),
  busy: <circle className="p-fill" cx="6" cy="6" r="5" />,
  incall: (
    <>
      <rect className="p-fill" x="2" y="1.5" width="3" height="9" />
      <rect className="p-fill" x="7" y="1.5" width="3" height="9" />
    </>
  ),
};

const ALSO_ICON: Record<(typeof ALSO)[number], string> = {
  transfer: 'M3 7h11M10 3l4 4-4 4M17 13H6M10 9l-4 4 4 4',
  cancel: 'M17 10a7 7 0 1 1-14 0 7 7 0 0 1 14 0ZM5.2 5.2l9.6 9.6',
  batch: 'M3 4h10v7H3zM6 14h11V7M9 17h11',
};

export function BoundaryFigure({ catalog = javaWebCatalog as unknown as Catalog, number = [7, 2], initialVariant = DEFAULT_VARIANT }: BoundaryFigureProps): React.JSX.Element {
  const f = useFigText(catalog);
  const { t, kit } = f;
  const uid = useId();
  const [variant, setVariant] = useState<Variant>(initialVariant);
  const { state, dispatch } = useStepper(STEP_COUNT);
  const step = state.step;
  const def = STEPS[step]!;
  const hook = useRef<HTMLDivElement>(null);

  // Test hooks on the <figure> (spec section 13). The kit's Figure takes no attributes, so the stage root sets them on its figure.
  useLayoutEffect(() => {
    const fig = hook.current?.closest('figure');
    if (!fig) return;
    fig.setAttribute('data-testid', 'fig-boundary');
    fig.setAttribute('data-step', String(step));
    fig.setAttribute('data-step-id', def.id);
    fig.setAttribute('data-variant', variant);
  }, [step, def.id, variant]);

  const stepLabel = (k: number): string => kit('kit.stepper.step', { k, n: STEP_COUNT });
  const laneState = (id: LaneId): 'active' | 'dim' | undefined => (step === 0 ? undefined : def.active.includes(id) ? 'active' : 'dim');
  const laneIndex = (id: LaneId): number => LANES.indexOf(id);

  const pill = (id: LaneId): React.JSX.Element => (
    <GhostCell
      attrs={{ 'data-pill': id }}
      active={def.states[laneIndex(id)]!}
      items={THREAD_STATES.map((s) => ({
        key: s,
        node: (
          <span className="ml-fig-lanes__pill">
            <svg viewBox="0 0 12 12" aria-hidden="true" focusable="false">
              {PILL_MARK[s]}
            </svg>
            {f.plain(`fig.boundary.state.${s}`)}
          </span>
        ),
      }))}
    />
  );
  const perVariant = (render: (v: Variant) => React.ReactNode, className?: string): React.JSX.Element => (
    <GhostCell className={className} active={variant} items={VARIANTS.map((v) => ({ key: v, node: render(v) }))} />
  );

  const payLines = (lines: readonly PayLine[]): React.ReactNode =>
    lines.map((line, i) => (
      <span key={i}>
        {i > 0 && '\n'}
        {'code' in line ? line.code : <span className="c-dim">{`${line.comment ? '// ' : ''}${f.plain(line.note)}`}</span>}
      </span>
    ));

  const sel = (v: Variant): Record<string, string> => ({ 'data-col': v, ...(v === variant ? { 'data-sel': '' } : {}) });
  const markedRow = rowOfStep(step);
  const rowProps = (id: RowId): Record<string, string> => ({ 'data-row': id, ...(markedRow === id ? { 'data-on': '' } : {}) });
  const pay = payload(step, variant);
  const title = f.plain('fig.boundary.title');

  return (
    <Figure
      id="fig-boundary"
      labelWord={figLabel(kit, number[0], number[1])}
      title={title}
      panel={
        <Manipulate>
          <Segmented<Variant>
            label={t('fig.boundary.variant.label')}
            options={VARIANTS.map((v) => ({ value: v, label: f.plain(`fig.boundary.variant.${v}`) }))}
            value={variant}
            onChange={setVariant}
          />
          <Stepper state={state} dispatch={dispatch} labels={stepperLabels(kit, t('fig.boundary.stepper'))} />
        </Manipulate>
      }
      status={{
        step: stepLabel(step),
        text: f.plain(narrationKey(step, variant)),
        // the seven narrations of this variant: the status area keeps the height of the longest one, at every step
        all: STEPS.map((_, i) => ({ step: stepLabel(i), text: f.plain(narrationKey(i, variant)) })),
      }}
      caption={<Markup text={t('fig.boundary.caption')} />}
      foot={SOURCES.map((p) => (
        <Chip key={p} href={sourceUrl(p)} prefix={kit('kit.chip.src')}>
          {p}
        </Chip>
      ))}
    >
      <div className="ml-fig-j7" ref={hook}>
        <div data-testid="boundary-readout">
          <Readout
            cells={[
              { label: t('fig.boundary.read.msgs'), value: <span data-r="msgs">{`${def.msgs} / 2`}</span> },
              { label: t('fig.boundary.read.calls'), value: <span data-r="calls">{callsText(variant, step)}</span> },
              {
                label: t('fig.boundary.read.worker'),
                value: (
                  <GhostCell
                    attrs={{ 'data-r': 'worker' }}
                    active={def.worker}
                    items={(['idle', 'busy'] as const).map((s) => ({ key: s, node: f.plain(`fig.boundary.state.${s}`) }))}
                  />
                ),
              },
            ]}
          />
        </div>

        <LaneDiagram label={t('fig.boundary.lanes.aria')}>
          <Lane id="page" name={f.plain('fig.boundary.lane.page')} state={laneState('page')}>
            {pill('page')}
            <span className="ml-fig-lanes__code">{PAGE_FILE}</span>
            <span className="ml-fig-lanes__mem">{f.plain('fig.boundary.lane.page.note')}</span>
          </Lane>
          <Wall id="msg" kind="msg" name={f.plain('fig.boundary.wall.msg')} tag={f.plain('fig.boundary.wall.msg.tag')} dir={def.wallA} />
          <LaneGroup id={`${uid}-worker`} label={f.plain('fig.boundary.group.worker')}>
            <Lane id="glue" name={f.plain('fig.boundary.lane.glue')} state={laneState('glue')}>
              {pill('glue')}
              {perVariant((v) => GLUE_FILE[v], 'ml-fig-lanes__code')}
              <span className="ml-fig-lanes__mem">{f.plain('fig.boundary.lane.glue.note')}</span>
            </Lane>
            <Wall
              id="call"
              kind={WALL_KIND[variant]}
              name={f.plain('fig.boundary.wall.call')}
              tag={perVariant((v) => f.plain(`fig.boundary.wall.call.tag.${v}`))}
              dir={def.wallB}
            />
            <Lane id="engine" name={f.plain('fig.boundary.lane.engine')} state={laneState('engine')}>
              {pill('engine')}
              {perVariant((v) => ENGINE_FILE[v], 'ml-fig-lanes__code')}
              {perVariant((v) => f.plain(`fig.boundary.lane.engine.mem.${v}`), 'ml-fig-lanes__mem')}
            </Lane>
          </LaneGroup>
        </LaneDiagram>

        <Legend>
          <LegendItem mark={<rect className="ml-fig-j7__lg-active" x="3" y="2" width="26" height="12" rx="3" />}>{f.plain('fig.boundary.legend.active')}</LegendItem>
          <LegendItem mark={<path className="ml-fig-j7__lg-stroke" strokeWidth="2" strokeDasharray="5 3" d="M1 5h30M1 11h30" />}>{f.plain('fig.boundary.legend.msg')}</LegendItem>
          <LegendItem mark={<path className="ml-fig-j7__lg-stroke" strokeWidth="2" d="M1 5h30M1 11h30" />}>{f.plain('fig.boundary.legend.convert')}</LegendItem>
          <LegendItem mark={<path className="ml-fig-j7__lg-stroke" strokeWidth="6" d="M1 8h30" />}>{f.plain('fig.boundary.legend.slot')}</LegendItem>
          <LegendItem mark={<path className="ml-fig-j7__lg-dim" strokeWidth="2" strokeDasharray="2 3" d="M1 8h30" />}>{f.plain('fig.boundary.legend.none')}</LegendItem>
        </Legend>

        <section className="ml-fig-j7__pay" data-testid="boundary-payload" data-how={pay.how} aria-labelledby={`${uid}-pay`}>
          <div className="ml-fig-j7__payhead">
            <span id={`${uid}-pay`}>{f.plain('fig.boundary.pay.label')}</span>
            <GhostCell
              className="ml-fig-j7__how"
              active={pay.how}
              items={HOWS.map((h) => ({
                key: h,
                node: (
                  <span data-how={h}>
                    <span aria-hidden="true">{HOW_MARK[h]}</span>
                    {f.plain(`fig.boundary.how.${h}`)}
                  </span>
                ),
              }))}
            />
          </div>
          <GhostCell
            as="div"
            itemAs="div"
            attrs={{ 'data-pay': '' }}
            active={`${def.id}:${variant}`}
            items={STEPS.flatMap((s, i) =>
              VARIANTS.map((v) => ({ key: `${s.id}:${v}`, node: <pre className="ml-fig-j7__code">{payLines(payload(i, v).lines)}</pre> })),
            )}
          />
        </section>

        <table className="ml-fig-j7__cmp" data-testid="boundary-compare">
          <caption>{f.plain('fig.boundary.cmp.caption')}</caption>
          <thead>
            <tr>
              <td />
              {VARIANTS.map((v) => (
                <th key={v} scope="col" {...sel(v)}>
                  {f.plain(`fig.boundary.variant.${v}`)}
                  {v === variant && <span className="ml-visually-hidden"> {f.plain('fig.boundary.cmp.selected')}</span>}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {TABLE_ROWS.map((r) => (
              <tr key={r.id} {...rowProps(r.id)}>
                <th scope="row">
                  <StableBold>{f.plain(`fig.boundary.row.${r.id}`)}</StableBold>
                </th>
                {VARIANTS.map((v) => (
                  <td key={v} {...sel(v)}>
                    {r.code[v] && <code>{r.code[v]}</code>}
                    <span>{f.plain(`fig.boundary.cell.${r.id}.${v}`)}</span>
                  </td>
                ))}
              </tr>
            ))}
            <tr {...rowProps('thread')}>
              <th scope="row">
                <StableBold>{f.plain('fig.boundary.row.thread')}</StableBold>
              </th>
              {/* One cell for the three builds shows "the same for all"; in the compact layout only one column exists, so a second cell of one column stands in. */}
              <td className="ml-fig-j7__same" colSpan={3} data-span="wide">
                <span>{f.plain('fig.boundary.cell.thread.all')}</span>
              </td>
              <td className="ml-fig-j7__same" data-span="compact">
                <span>{f.plain('fig.boundary.cell.thread.all')}</span>
              </td>
            </tr>
          </tbody>
        </table>

        <div>
          <p className="ml-fig-j7__alsohead">{f.plain('fig.boundary.also.head')}</p>
          <ul className="ml-fig-j7__also">
            {ALSO.map((id) => (
              <li key={id} data-also={id}>
                <svg viewBox="0 0 20 20" aria-hidden="true" focusable="false">
                  <path d={ALSO_ICON[id]} />
                </svg>
                <span>{f.plain(`fig.boundary.also.${id}`)}</span>
              </li>
            ))}
          </ul>
        </div>
      </div>
    </Figure>
  );
}
