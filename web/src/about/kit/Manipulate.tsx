// Manipulate (design notes, section 6 "Manipulate" and section 8 "Keys"): the control panel of a figure and its controls. Every control is
// a native element with a label, 44 px high at least. All text arrives as props. The stepper state comes from useStepper.
import { useId } from 'react';
import type { KeyboardEvent, ReactNode } from 'react';
import { Icon } from './icons';
import type { StepperAction, StepperState } from './useStepper';

/** The band above the stage. Holds control rows (`Ctl`, `Slider`, ...). */
export function Manipulate({ children }: { children: ReactNode }): React.JSX.Element {
  return <div className="ml-fig__panel">{children}</div>;
}

export interface CtlProps {
  /** Id of the control element the label belongs to. */
  htmlFor?: string;
  label: ReactNode;
  /** Readout at the end of the first line (compact) or in the last column (wide). */
  out?: ReactNode;
  /** A control without a readout spans the last two columns. */
  wide?: boolean;
  children: ReactNode;
  /** For a group control (segmented switch, presets, stepper) the label names the group instead of a single input. */
  labelId?: string;
}

/** One control row: label and readout on the first line, the control below (compact) or in three columns (wide). */
export function Ctl({ htmlFor, label, out, wide, children, labelId }: CtlProps): React.JSX.Element {
  return (
    <div className={wide || out === undefined ? 'ml-fig-ctl ml-fig-ctl--wide' : 'ml-fig-ctl'}>
      {htmlFor ? (
        <label className="ml-fig-ctl__label" htmlFor={htmlFor}>
          {label}
        </label>
      ) : (
        <span className="ml-fig-ctl__label" id={labelId}>
          {label}
        </span>
      )}
      {out !== undefined && <span className="ml-fig-ctl__out">{out}</span>}
      <div className="ml-fig-ctl__input">{children}</div>
    </div>
  );
}

export interface SliderProps {
  label: ReactNode;
  min: number;
  max: number;
  step?: number;
  value: number;
  onChange: (value: number) => void;
  /** Spoken value (`aria-valuetext`); a slider does not write into the live region. */
  valueText?: string;
  /** Visible readout. */
  out?: ReactNode;
  /** The two ends, written under the track. */
  scale?: readonly [string, string];
  disabled?: boolean;
}

/** Keys Left, Right, Up, Down, Page Up, Page Down, Home, End come from the native range input. */
export function Slider({ label, min, max, step = 1, value, onChange, valueText, out, scale, disabled }: SliderProps): React.JSX.Element {
  const id = useId();
  const fill = max > min ? ((value - min) / (max - min)) * 100 : 0;
  return (
    <Ctl htmlFor={id} label={label} out={out}>
      <input
        id={id}
        className="ml-slider"
        type="range"
        min={min}
        max={max}
        step={step}
        value={value}
        disabled={disabled}
        aria-valuetext={valueText}
        style={{ '--ml-slider-fill': `${fill}%` } as React.CSSProperties}
        onChange={(e) => onChange(Number(e.currentTarget.value))}
      />
      {scale && (
        <div className="ml-fig-scale" aria-hidden="true">
          <span>{scale[0]}</span>
          <span>{scale[1]}</span>
        </div>
      )}
    </Ctl>
  );
}

export interface SegmentedProps<T extends string> {
  label: ReactNode;
  /** A disabled option is a disabled native radio: no click and no arrow key selects it, and the arrow keys skip it. `describedBy` is the id of the element that says why. */
  options: readonly { value: T; label: ReactNode; disabled?: boolean; describedBy?: string }[];
  value: T;
  onChange: (value: T) => void;
}

/** A radiogroup of native radios; the browser gives one tab stop and the arrow keys with wrap-around. */
export function Segmented<T extends string>({ label, options, value, onChange }: SegmentedProps<T>): React.JSX.Element {
  const name = useId();
  const labelId = `${name}-l`;
  return (
    <Ctl label={label} labelId={labelId}>
      <div className="ml-fig-seg" role="radiogroup" aria-labelledby={labelId}>
        {options.map((o) => (
          <label key={o.value} className="ml-fig-seg__opt" {...(o.disabled ? { 'data-disabled': '' } : {})}>
            <input
              type="radio"
              name={name}
              value={o.value}
              checked={o.value === value}
              disabled={o.disabled}
              aria-describedby={o.disabled ? o.describedBy : undefined}
              onChange={() => onChange(o.value)}
            />
            <span>{o.label}</span>
          </label>
        ))}
      </div>
    </Ctl>
  );
}

export interface LiveSwitchProps {
  /** `kit.live.label`. */
  label: ReactNode;
  /** `kit.live.example`, and `kit.live.mine` with the ply filled in. */
  exampleLabel: ReactNode;
  mineLabel: ReactNode;
  value: 'example' | 'mine';
  onChange: (value: 'example' | 'mine') => void;
  /** Why My game cannot be chosen (`kit.live.reason.*`); null when it can. */
  reason: string | null;
  /** A run is starting or going on: the Stop button shows. */
  busy: boolean;
  stopLabel: string;
  onStop: () => void;
  /** The run failed: the Try again button shows. */
  failed: boolean;
  retryLabel: string;
  onRetry: () => void;
}

/**
 * The live switch of a figure: Example or My game. The reason text under a disabled My game is linked to it with aria-describedby. The
 * running status is not here: it belongs in the narration line of the figure (`Figure status`), the figure's only live region.
 */
export function LiveSwitch({ label, exampleLabel, mineLabel, value, onChange, reason, busy, stopLabel, onStop, failed, retryLabel, onRetry }: LiveSwitchProps): React.JSX.Element {
  const reasonId = useId();
  return (
    <div className="ml-fig-live" data-live-switch="">
      <Segmented
        label={label}
        options={[
          { value: 'example', label: exampleLabel },
          { value: 'mine', label: mineLabel, disabled: reason !== null, describedBy: reasonId },
        ]}
        value={value}
        onChange={onChange}
      />
      {reason !== null && (
        <p className="ml-fig-live__reason" id={reasonId} data-live-reason="">
          {reason}
        </p>
      )}
      {(busy || failed) && (
        <div className="ml-fig-live__actions">
          {busy && (
            <button type="button" className="ml-btn" data-live-stop="" onClick={onStop}>
              <Icon name="pause" />
              {stopLabel}
            </button>
          )}
          {failed && (
            <button type="button" className="ml-btn" data-live-retry="" onClick={onRetry}>
              <Icon name="reset" />
              {retryLabel}
            </button>
          )}
        </div>
      )}
    </div>
  );
}

export interface PresetsProps {
  label: ReactNode;
  presets: readonly { id: string; label: ReactNode }[];
  /** The pressed preset; null when the values match none (a slider changed afterwards). */
  pressed: string | null;
  onPick: (id: string) => void;
}

export function Presets({ label, presets, pressed, onPick }: PresetsProps): React.JSX.Element {
  const labelId = useId();
  return (
    <Ctl label={label} labelId={labelId}>
      <div className="ml-fig-presets" role="group" aria-labelledby={labelId}>
        {presets.map((p) => (
          <button key={p.id} type="button" className="ml-btn" aria-pressed={pressed === p.id} onClick={() => onPick(p.id)}>
            <Icon name="check" />
            {p.label}
          </button>
        ))}
      </div>
    </Ctl>
  );
}

export interface CheckboxProps {
  label: ReactNode;
  checked: boolean;
  onChange: (checked: boolean) => void;
  disabled?: boolean;
}

export function Checkbox({ label, checked, onChange, disabled }: CheckboxProps): React.JSX.Element {
  return (
    <label className="ml-fig-check">
      <input type="checkbox" checked={checked} disabled={disabled} onChange={(e) => onChange(e.currentTarget.checked)} />
      <span className="ml-fig-check__box">
        <Icon name="check" />
      </span>
      <span>{label}</span>
    </label>
  );
}

export interface SelectProps {
  label: ReactNode;
  options: readonly { value: string; label: string }[];
  value: string;
  onChange: (value: string) => void;
  disabled?: boolean;
}

export function Select({ label, options, value, onChange, disabled }: SelectProps): React.JSX.Element {
  const id = useId();
  return (
    <Ctl htmlFor={id} label={label} wide>
      <select id={id} className="ml-fig-select" value={value} disabled={disabled} onChange={(e) => onChange(e.currentTarget.value)}>
        {options.map((o) => (
          <option key={o.value} value={o.value}>
            {o.label}
          </option>
        ))}
      </select>
    </Ctl>
  );
}

export interface ReadoutProps {
  cells: readonly { label: ReactNode; value: ReactNode }[];
}

/** Label and value cells (alpha, beta, score, nodes): not controls. */
export function Readout({ cells }: ReadoutProps): React.JSX.Element {
  return (
    <dl className="ml-fig-readout">
      {cells.map((c, i) => (
        <div key={i} className="ml-fig-readout__cell">
          <dt>{c.label}</dt>
          <dd className="ml-fig-readout__value">{c.value}</dd>
        </div>
      ))}
    </dl>
  );
}

export interface StepperLabels {
  /** Label of the whole stepper (`fig.<id>.stepper`). */
  group: string;
  reset: string;
  back: string;
  play: string;
  pause: string;
  forward: string;
  /** Spoken prefix of the position "3 / 6" (`kit.stepper.position`). */
  position: string;
}

export interface StepperProps {
  state: StepperState;
  dispatch: (action: StepperAction) => void;
  labels: StepperLabels;
}

// The bounds use aria-disabled, not disabled: a disabled button that has the focus drops it, and the arrow keys would stop working.
/**
 * Reset, back, play or pause, forward, the position and the track. Left and Right go one step back or forward, Home and End to the first
 * and last step, without leaving the button. The play button is not rendered when `state.canPlay` is false (reduced motion).
 */
export function Stepper({ state, dispatch, labels }: StepperProps): React.JSX.Element {
  const onKeyDown = (event: KeyboardEvent<HTMLDivElement>): void => {
    const action: StepperAction | null =
      event.key === 'ArrowLeft' ? { type: 'back' } : event.key === 'ArrowRight' ? { type: 'forward' } : event.key === 'Home' ? { type: 'home' } : event.key === 'End' ? { type: 'end' } : null;
    if (!action || event.altKey || event.ctrlKey || event.metaKey) return;
    event.preventDefault();
    dispatch(action);
  };
  const { step, count, playing, canPlay } = state;
  return (
    <Ctl label={labels.group} wide>
      <div className="ml-fig-stepper" role="group" aria-label={labels.group} onKeyDown={onKeyDown}>
        <button type="button" className="ml-btn ml-btn--icon" aria-label={labels.reset} aria-disabled={step === 0} onClick={() => dispatch({ type: 'reset' })}>
          <Icon name="reset" />
        </button>
        <button type="button" className="ml-btn ml-btn--icon" aria-label={labels.back} aria-disabled={step === 0} onClick={() => dispatch({ type: 'back' })}>
          <Icon name="step-back" />
        </button>
        {canPlay && (
          <button type="button" className="ml-btn ml-btn--icon" aria-label={playing ? labels.pause : labels.play} onClick={() => dispatch({ type: 'toggle' })}>
            <Icon name={playing ? 'pause' : 'play'} />
          </button>
        )}
        <button type="button" className="ml-btn ml-btn--icon" aria-label={labels.forward} aria-disabled={step >= count} onClick={() => dispatch({ type: 'forward' })}>
          <Icon name="step-fwd" />
        </button>
        <span className="ml-fig-stepper__pos">
          <span className="ml-visually-hidden">{labels.position} </span>
          {step} / {count}
        </span>
        <div className="ml-fig-stepper__track" aria-hidden="true">
          {count <= 12 ? (
            Array.from({ length: count }, (_, i) => (
              <span key={i} className="ml-fig-stepper__seg" data-state={i + 1 < step ? 'done' : i + 1 === step ? 'current' : 'todo'} />
            ))
          ) : (
            <span className="ml-progress" style={{ gridColumn: '1 / -1' }}>
              <span className="ml-progress__fill" style={{ display: 'block', width: `${(step / count) * 100}%` }} />
            </span>
          )}
        </div>
      </div>
    </Ctl>
  );
}
