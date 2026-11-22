// The two parts of figure J6 that the kit does not have: CallLanes (two lanes with three boxes each and a hatched band between them) and
// WireCard (the data in its current form). Both take every text as a prop; identifiers and the lines of the card are data.
// Layout notes: .ml-fig-lanes and .ml-fig-wire in hilla.css. The stable-layout rule of the Info part applies: the card keeps its height and the
// width of its head because every body and every tag pair stands in one grid cell, the others hidden.
import { Fragment } from 'react';
import { BOXES, GAPS, LANE_BOXES, STEPS, WIRE, tagPairs } from './hilla-model';
import type { ArrowId, BoxId, EdgeId, HillaStep, LaneId, Seg, StepId, WaitState, WireForm, WireWhere } from './hilla-model';

type State = 'active' | 'dim' | undefined;

/** `idle` shows everything in ink; otherwise a part is either active in this step or dimmed. */
const stateOf = (step: HillaStep, on: boolean): State => (step.id === 'idle' ? undefined : on ? 'active' : 'dim');

export interface CallLanesLabels {
  title: string;
  lane: Record<LaneId, string>;
  band: string;
  role: Record<BoxId, string>;
  wait: Record<Exclude<WaitState, 'none'>, string>;
  nopath: string;
  build: string;
}

const HEAD_DOWN = 'M0 0 L9 4.5 L0 9 Z';
const ArrowHead = ({ open, back }: { open?: boolean; back?: boolean }): React.JSX.Element =>
  open ? (
    <svg viewBox="0 0 9 9" aria-hidden="true" focusable="false">
      <path d={back ? 'M9 0 L0 4.5 L9 9' : 'M0 0 L9 4.5 L0 9'} fill="none" stroke="currentColor" strokeWidth="2" />
    </svg>
  ) : (
    <svg viewBox="0 0 9 9" aria-hidden="true" focusable="false">
      <path d={back ? 'M9 0 L0 4.5 L9 9 Z' : HEAD_DOWN} fill="currentColor" />
    </svg>
  );

interface ArrowProps {
  state: State;
  /** Pointing left or up (the arrow runs against the reading direction). */
  rev?: boolean;
  vertical?: boolean;
  control?: boolean;
  dot: boolean;
  id?: ArrowId;
}

function Arrow({ state, rev, vertical, control, dot, id }: ArrowProps): React.JSX.Element {
  const cls = ['ml-fig-arr', vertical && 'ml-fig-arr--v', rev && 'ml-fig-arr--rev', control && 'ml-fig-arr--control'].filter(Boolean).join(' ');
  return (
    <span className={cls} data-arrow={id} data-state={state}>
      <span className="ml-fig-arr__line" />
      {vertical ? (
        <svg viewBox="0 0 9 9" aria-hidden="true" focusable="false">
          <path d={rev ? 'M0 9 L4.5 0 L9 9 Z' : 'M0 0 L4.5 9 L9 0 Z'} fill="currentColor" />
        </svg>
      ) : (
        <ArrowHead open={control} back={rev} />
      )}
      {dot && !vertical && <span className="ml-fig-arr__dot" />}
    </span>
  );
}

function Box({ id, step, role }: { id: BoxId; step: HillaStep; role: string }): React.JSX.Element {
  const b = BOXES[id];
  const on = step.boxes.includes(id);
  return (
    <li className="ml-fig-lanes__box" data-box={id} data-kind={b.kind} data-state={stateOf(step, on)} aria-current={on ? 'step' : undefined}>
      <span className="ml-fig-lanes__id">
        {b.id[0]}
        {b.id[1] !== undefined && (
          <>
            <wbr />
            {b.id[1]}
          </>
        )}
      </span>
      <span className="ml-fig-lanes__role">{role}</span>
    </li>
  );
}

export function CallLanes({ step, labels, canPlay }: { step: HillaStep; labels: CallLanesLabels; canPlay: boolean }): React.JSX.Element {
  const edge = (id: EdgeId): State => stateOf(step, step.edges.includes(id));
  const lane = (id: LaneId): React.JSX.Element => (
    <section className="ml-fig-lanes__lane" data-lane={id} data-here={String(step.here === id)} aria-labelledby={`fig-hilla-lane-${id}`}>
      <span className="ml-fig-lanes__lanelabel" id={`fig-hilla-lane-${id}`}>
        {labels.lane[id]}
      </span>
      <ol className="ml-fig-lanes__boxes">
        {LANE_BOXES[id].map((box, i) => (
          <Fragment key={box}>
            <Box id={box} step={step} role={labels.role[box]} />
            {i < 2 && (
              <li className="ml-fig-lanes__vgap" aria-hidden="true" data-seg={GAPS[id][i]}>
                {/* the call runs down in the browser lane and up in the server lane; the reply runs the other way */}
                <Arrow vertical rev={id === 'server'} dot={false} id={`${GAPS[id][i]!}:req` as ArrowId} state={stateOf(step, step.arrows.includes(`${GAPS[id][i]!}:req` as ArrowId))} />
                {id === 'browser' && i === 0 ? (
                  <span className="ml-fig-lanes__wait" data-wait={step.wait}>
                    {labels.wait[step.wait === 'done' ? 'done' : 'pending']}
                  </span>
                ) : (
                  <span />
                )}
                <Arrow vertical rev={id === 'browser'} dot={false} id={`${GAPS[id][i]!}:rep` as ArrowId} state={stateOf(step, step.arrows.includes(`${GAPS[id][i]!}:rep` as ArrowId))} />
              </li>
            )}
          </Fragment>
        ))}
      </ol>
    </section>
  );
  return (
    <div className="ml-fig-lanes" role="group" aria-label={labels.title}>
      {lane('browser')}
      <div className="ml-fig-lanes__band" aria-hidden="true">
        <span className="ml-fig-lanes__bandlabel">{labels.band}</span>
        <div className="ml-fig-lanes__cell" data-edge="nopath" data-state={edge('nopath')}>
          <div className="ml-fig-nopath">
            <Arrow state={edge('nopath')} dot={false} />
            <svg className="ml-fig-nopath__bar" viewBox="0 0 16 22" aria-hidden="true" focusable="false">
              <path d="M3 0 V22" stroke="currentColor" strokeWidth="3" />
              <path d="M8 7 l7 8 M15 7 l-7 8" stroke="currentColor" strokeWidth="2" fill="none" />
            </svg>
            <span className="ml-fig-nopath__rest" />
          </div>
          <span className="ml-fig-lanes__edgelabel">{labels.nopath}</span>
        </div>
        <span />
        <div className="ml-fig-lanes__cell" data-edge="build" data-state={edge('build')}>
          <Arrow state={edge('build')} rev control dot={canPlay} />
          <span className="ml-fig-lanes__edgelabel">{labels.build}</span>
        </div>
        <span />
        <div className="ml-fig-lanes__cell">
          <div className="ml-fig-lanes__cell" data-edge="request" data-state={edge('request')}>
            <span className="ml-fig-lanes__edgelabel">POST</span>
            <Arrow state={edge('request')} dot={canPlay} />
          </div>
          <div className="ml-fig-lanes__cell" data-edge="reply" data-state={edge('reply')}>
            <Arrow state={edge('reply')} rev dot={canPlay} />
            <span className="ml-fig-lanes__edgelabel">JSON</span>
          </div>
        </div>
      </div>
      {lane('server')}
    </div>
  );
}

const Segments = ({ segs }: { segs: readonly Seg[] }): React.JSX.Element => (
  <>
    {segs.map((x, i) => (x.t === 'p' ? <Fragment key={i}>{x.s}</Fragment> : <span key={i} className={x.t}>{x.s}</span>))}
  </>
);

export interface WireCardLabels {
  title: string;
  idle: string;
  where: Record<WireWhere, string>;
  form: Record<WireForm, string>;
}

export function WireCard({ stepId, labels }: { stepId: StepId; labels: WireCardLabels }): React.JSX.Element {
  const step = STEPS.find((s) => s.id === stepId)!;
  const live = stepId === 'idle' ? undefined : WIRE[stepId];
  return (
    <section className="ml-fig-wire" aria-labelledby="fig-hilla-wire-title" data-wire-step={stepId}>
      <div className="ml-fig-wire__head">
        <h4 className="ml-fig-wire__title" id="fig-hilla-wire-title">
          {labels.title}
        </h4>
        <div className="ml-fig-wire__tags">
          <div data-live="tags">
            {step.where && step.form && (
              <>
                <span className="ml-fig-wire__tag" data-wire-where={step.where}>
                  {labels.where[step.where]}
                </span>
                <span className="ml-fig-wire__tag ml-fig-wire__tag--form" data-wire-form={step.form}>
                  {labels.form[step.form]}
                </span>
              </>
            )}
          </div>
          {tagPairs().map(([w, f]) => (
            <div key={`${w}|${f}`} className="ml-fig-wire__ghost" aria-hidden="true">
              <span className="ml-fig-wire__tag">{labels.where[w]}</span>
              <span className="ml-fig-wire__tag ml-fig-wire__tag--form">{labels.form[f]}</span>
            </div>
          ))}
        </div>
      </div>
      <div className="ml-fig-wire__body">
        {live ? (
          <pre data-live="body">
            <Segments segs={live} />
          </pre>
        ) : (
          <p className="ml-fig-wire__hint" data-live="body">
            {labels.idle}
          </p>
        )}
        <p className="ml-fig-wire__hint ml-fig-wire__ghost" aria-hidden="true">
          {labels.idle}
        </p>
        {(Object.keys(WIRE) as Exclude<StepId, 'idle'>[]).map((id) => (
          <pre key={id} className="ml-fig-wire__ghost" aria-hidden="true">
            <Segments segs={WIRE[id]} />
          </pre>
        ))}
      </div>
    </section>
  );
}
