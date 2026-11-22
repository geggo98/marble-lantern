// Figure J6 of topic 7 (java-web): the data flow of one Hilla call, in eight steps. Two lanes (browser, server) with a hatched band for the network
// between them; a card below shows the form of the data at each step (TypeScript values, JSON text, Java values). The model is hilla-model.ts, the
// two parts that the kit lacks are in HillaParts.tsx, the styles in hilla.css. Stable layout: no step changes the height of any part (the narration,
// the card body and the card tags use ghost texts in one grid cell; the rows of the lanes have fixed heights).
import { useEffect, useId, useRef } from 'react';
import { Chip, Figure, Legend, LegendItem, Manipulate, Stepper, stepperLabels, useStepper } from '../kit';
import { Markup } from '../kit/markup';
import { figLabel, useFigText } from './common';
import type { Catalog } from './common';
import { sourceUrl } from '../kit/SourceChip';
import { CallLanes } from './HillaParts';
import type { CallLanesLabels, WireCardLabels } from './HillaParts';
import { WireCard } from './HillaParts';
import { BOXES, STEPS, STEP_COUNT } from './hilla-model';
import type { BoxId, LaneId, StepId, WireForm, WireWhere } from './hilla-model';
import './hilla.css';

export interface HillaFigureProps {
  catalog: Catalog;
  number: readonly [number, number];
}

/** The foot names sources only; the figure has no data file. */
const SOURCES = ['app/src/main/java/sternhalma/app/GameEndpoint.java', 'web/src/engines/server/client.ts', 'web/src/engines/server/load.ts', 'app/src/main/java/sternhalma/app/Engines.java'];
const BOX_IDS = Object.keys(BOXES) as BoxId[];
const STEP_IDS = STEPS.slice(1).map((s) => s.id) as Exclude<StepId, 'idle'>[];

export function HillaFigure({ catalog, number }: HillaFigureProps): React.JSX.Element {
  const f = useFigText(catalog);
  const { t, kit } = f;
  const { state, dispatch } = useStepper(STEP_COUNT);
  const uid = useId();
  const step = STEPS[state.step]!;
  const title = f.plain('fig.hilla.title');
  const stepLabel = (k: number): string => kit('kit.stepper.step', { k, n: STEP_COUNT });

  const lanes: CallLanesLabels = {
    title,
    lane: Object.fromEntries((['browser', 'server'] as const).map((l) => [l, f.plain(`fig.hilla.lane.${l}`)])) as Record<LaneId, string>,
    band: f.plain('fig.hilla.band'),
    role: Object.fromEntries(BOX_IDS.map((b) => [b, f.plain(`fig.hilla.box.${b}`)])) as Record<BoxId, string>,
    wait: { pending: f.plain('fig.hilla.wait.pending'), done: f.plain('fig.hilla.wait.done') },
    nopath: f.plain('fig.hilla.edge.nopath'),
    build: f.plain('fig.hilla.edge.build'),
  };
  const wire: WireCardLabels = {
    title: f.plain('fig.hilla.wire.title'),
    idle: f.plain('fig.hilla.wire.idle'),
    where: Object.fromEntries((['build', 'browser', 'network', 'server'] as const).map((w) => [w, f.plain(`fig.hilla.where.${w}`)])) as Record<WireWhere, string>,
    form: Object.fromEntries((['source', 'ts', 'json', 'java'] as const).map((w) => [w, f.plain(`fig.hilla.form.${w}`)])) as Record<WireForm, string>,
  };
  const stage = useRef<HTMLDivElement>(null);
  // The hook of the spec is on the figure itself (#fig-hilla[data-step]); the Figure frame takes no attributes, so the stage passes it up.
  useEffect(() => {
    stage.current?.closest('figure')?.setAttribute('data-step', String(state.step));
  }, [state.step]);
  const hatch = `${uid}-hatch`;

  return (
    <Figure
      id="fig-hilla"
      labelWord={figLabel(kit, number[0], number[1])}
      title={title}
      panel={
        <Manipulate>
          <Stepper state={state} dispatch={dispatch} labels={stepperLabels(f.kitT, t('fig.hilla.stepper'))} />
        </Manipulate>
      }
      status={{
        ...(state.step > 0 ? { step: stepLabel(state.step) } : {}),
        text: f.plain(`fig.hilla.narr.${step.id}`),
        live: !state.playing,
        all: [{ text: f.plain('fig.hilla.narr.idle') }, ...STEP_IDS.map((id, i) => ({ step: stepLabel(i + 1), text: f.plain(`fig.hilla.narr.${id}`) }))],
      }}
      caption={<Markup text={t('fig.hilla.caption')} />}
      foot={
        <>
          {SOURCES.map((p) => (
            <Chip key={p} prefix={kit('kit.chip.src')} href={sourceUrl(p)}>
              {p}
            </Chip>
          ))}
        </>
      }
    >
      <div ref={stage} id="fig-hilla-stage" className="ml-fig-hilla" data-step={state.step}>
        <CallLanes step={step} labels={lanes} canPlay={state.canPlay} />
        <ol className="ml-visually-hidden" aria-label={f.plain('fig.hilla.steplist')}>
          {STEP_IDS.map((id) => (
            <li key={id}>{f.plain(`fig.hilla.step.${id}`)}</li>
          ))}
        </ol>
        <WireCard stepId={step.id} labels={wire} />
        <Legend>
          <LegendItem mark={<rect x={3} y={2} width={26} height={12} rx={3} fill="none" stroke="currentColor" strokeWidth={1.5} />}>{f.plain('fig.hilla.legend.own')}</LegendItem>
          <LegendItem mark={<rect x={3} y={2} width={26} height={12} rx={3} fill="none" stroke="currentColor" strokeWidth={1.5} strokeDasharray="4 3" />}>{f.plain('fig.hilla.legend.lib')}</LegendItem>
          <LegendItem
            mark={
              <>
                <rect x={3} y={2} width={26} height={12} rx={3} fill="none" stroke="currentColor" strokeWidth={2} strokeDasharray="1 3" strokeLinecap="round" />
                <path d="M22 2 h7 v7 Z" fill="currentColor" />
              </>
            }
          >
            {f.plain('fig.hilla.legend.gen')}
          </LegendItem>
          <LegendItem
            mark={
              <>
                <path d="M2 8 H22" stroke="currentColor" strokeWidth={2} />
                <path d="M22 3.5 L31 8 L22 12.5 Z" fill="currentColor" />
              </>
            }
          >
            {kit('kit.edge.data')}
          </LegendItem>
          <LegendItem
            mark={
              <>
                <path d="M2 8 H29" stroke="currentColor" strokeWidth={1.5} strokeDasharray="6 4" />
                <path d="M22 3.5 L31 8 L22 12.5" fill="none" stroke="currentColor" strokeWidth={1.5} />
              </>
            }
          >
            {f.plain('fig.hilla.legend.build')}
          </LegendItem>
          <LegendItem
            mark={
              <>
                <path d="M2 8 H10" stroke="currentColor" strokeWidth={2} />
                <path d="M10 4.5 L17 8 L10 11.5 Z" fill="currentColor" />
                <path d="M20 0 V16" stroke="currentColor" strokeWidth={3} />
                <path d="M24 4 l6 8 M30 4 l-6 8" stroke="currentColor" strokeWidth={2} />
              </>
            }
          >
            {f.plain('fig.hilla.legend.nopath')}
          </LegendItem>
          <LegendItem
            mark={
              <>
                <defs>
                  <pattern id={hatch} width={6} height={6} patternUnits="userSpaceOnUse" patternTransform="rotate(45)">
                    <path d="M0 0 V6" stroke="currentColor" strokeWidth={1} />
                  </pattern>
                </defs>
                <rect x={3} y={1} width={26} height={14} fill={`url(#${hatch})`} />
              </>
            }
          >
            {f.plain('fig.hilla.legend.band')}
          </LegendItem>
        </Legend>
      </div>
    </Figure>
  );
}
