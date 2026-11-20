// The dialog that stands before a game: "Do you want to begin?", "Continue this game?" for a game link, and the error
// for a link that does not work. A modal dialog: focus moves in, Tab stays inside, focus returns to where it was.
// Escape, a click on the backdrop beside the panel and the "keep" button close the dialog only when a game is already running
// (`onCancel`); otherwise there is nothing to go back to.
import { useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import type { Side } from '../game/board-state';
import { DEFAULT_VARIANT, campsOf, moveLimit, withCamps } from '../game/variant';
import type { ForeignCamps, JumpRule, Player, Variant } from '../game/variant';
import type { GameMode, StartChoice } from '../app/start-choice';
import { useNetSelection } from '../app/net-selection';
import type { NetSelectionStore } from '../app/net-selection';
import { NetWarnings } from './net-warnings';
import { DialogLanguageRow } from './LanguageToggle';
import { useT } from './texts';
import { useDialog } from './useDialog';
import './start-dialog.css';

export type StartDialogModel =
  | {
      kind: 'begin';
      /** What the dialog shows first: the last choice of this person, or the default. */
      initial: StartChoice;
      /** `humanBegins` answers "Do you want to begin?"; it counts for the two-player mode only. */
      onStart: (choice: StartChoice, humanBegins: boolean) => void;
      /** The selection of the net engines: when the selected net engine or model cannot play the variant chosen here, the dialog says so. */
      net?: NetSelectionStore;
    }
  | {
      kind: 'link';
      ply: number;
      /** The jump rule of the linked game when it is not the standard one: the dialog names it. */
      jump?: JumpRule | undefined;
      sideToMove: Side;
      /** The side that the link names as the human's, if it does; this one gets the first focus. */
      preferred?: Side | undefined;
      onContinueAs: (humanSide: Side) => void;
      onNew: () => void;
      /** The variant of the linked game and the selection of the net engines: the dialog warns when the selected net or model cannot play it. */
      variant?: Variant | undefined;
      net?: NetSelectionStore | undefined;
    }
  | { kind: 'broken'; reason: 'moves' | 'link'; onNew: () => void };

export interface StartDialogProps {
  model: StartDialogModel;
  /** Present when a game is running: Escape, a click on the backdrop and the "keep" button close the dialog and leave that game alone. */
  onCancel?: (() => void) | undefined;
}

export function StartDialog({ model, onCancel }: StartDialogProps): React.JSX.Element {
  const { t } = useT();
  const { ref, onKeyDown } = useDialog<HTMLElement>({ onEscape: onCancel, focusKey: model.kind });

  const pressedInPanel = useRef(false);
  const [choice, setChoice] = useState<StartChoice>(() => (model.kind === 'begin' ? model.initial : DEFAULT_CHOICE_FALLBACK));
  const titleId = 'ml-start-title';
  const bodyId = 'ml-start-body';
  let title: string;
  let body: React.JSX.Element;
  let actions: React.JSX.Element;

  if (model.kind === 'begin') {
    title = t('start_title');
    body = <p id={bodyId}>{t('start_lead')}</p>;
    actions = (
      <>
        <BeginActions choice={choice} onStart={model.onStart} />
        {onCancel && (
          <button type="button" className="ml-btn ml-btn--quiet" data-choice="begin-keep" onClick={onCancel}>
            {t('link_keep')}
          </button>
        )}
      </>
    );
  } else if (model.kind === 'link') {
    title = t('link_title');
    body = (
      <>
        <p id={bodyId}>{t('link_info', { ply: model.ply, limit: moveLimit(model.variant ?? DEFAULT_VARIANT) })}</p>
        {model.jump === 'long' && <p data-testid="link-variant">{t('link_variant', { jump: t('start_jump_long') })}</p>}
        {model.variant && campsOf(model.variant) !== 'closed' && (
          <p data-testid="link-camps">{t('link_camps', { rule: t(CAMPS_LABEL[campsOf(model.variant)]) })}</p>
        )}
        <p className="ml-hint">{t('link_as_hint')}</p>
      </>
    );
    const other: Side = model.sideToMove === 0 ? 1 : 0;
    const focusSide: Side = model.preferred ?? model.sideToMove;
    const asButton = (side: Side, choice: string): React.JSX.Element => (
      <button
        type="button"
        className={side === focusSide ? 'ml-btn ml-btn--primary' : 'ml-btn'}
        data-choice={choice}
        {...(side === focusSide ? { 'data-autofocus': true } : {})}
        onClick={() => model.onContinueAs(side)}
      >
        {t(choice === 'link-side-to-move' ? 'link_as_mover' : 'link_as_other')}
      </button>
    );
    actions = (
      <>
        {asButton(model.sideToMove, 'link-side-to-move')}
        {asButton(other, 'link-other-side')}
        <button type="button" className="ml-btn" data-choice="link-new" onClick={model.onNew}>
          {t('link_new')}
        </button>
        {onCancel && (
          <button type="button" className="ml-btn ml-btn--quiet" data-choice="link-keep" onClick={onCancel}>
            {t('link_keep')}
          </button>
        )}
      </>
    );
  } else {
    title = t('bad_link_title');
    body = <p id={bodyId}>{t(model.reason === 'moves' ? 'bad_link_moves' : 'bad_link_body')}</p>;
    actions = (
      <>
        <button type="button" className="ml-btn ml-btn--primary" data-autofocus data-choice="broken-new" onClick={model.onNew}>
          {t('bad_link_new')}
        </button>
        {onCancel && (
          <button type="button" className="ml-btn ml-btn--quiet" data-choice="link-keep" onClick={onCancel}>
            {t('link_keep')}
          </button>
        )}
      </>
    );
  }

  return createPortal(
    // A press that starts and ends on the backdrop cancels; one that starts in the panel (a text selection dragged out) does not.
    <div
      className="ml-overlay ml-overlay--start"
      data-testid="start-backdrop"
      onPointerDown={(e) => {
        pressedInPanel.current = e.target !== e.currentTarget;
      }}
      onClick={(e) => {
        const fromBackdrop = !pressedInPanel.current && e.target === e.currentTarget;
        pressedInPanel.current = false;
        if (fromBackdrop && onCancel) onCancel();
      }}
    >
      <section
        ref={ref}
        className="ml-sheet ml-start"
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        aria-describedby={bodyId}
        data-dialog={model.kind}
        onKeyDown={onKeyDown}
      >
        <h2 className="ml-sheet__title" id={titleId}>{title}</h2>
        {body}
        {model.kind === 'begin' && <BeginChoices choice={choice} onChange={setChoice} />}
        {model.kind === 'begin' && model.net && <StartNetWarning store={model.net} variant={withCamps({ jump: choice.jump, players: choice.mode === 'two' ? 2 : 3 }, choice.camps)} />}
        {model.kind === 'link' && model.net && model.variant && <StartNetWarning store={model.net} variant={model.variant} />}
        <div className="ml-start__actions">{actions}</div>
        <DialogLanguageRow />
      </section>
    </div>,
    document.body,
  );
}

/**
 * The warnings of the selected net engine for the variant of the game that is about to start (the choice above, or the link); starting
 * stays possible (the engine plays random moves). The store names the engine that plays (App resets it to the loaded one).
 */
function StartNetWarning({ store, variant }: { store: NetSelectionStore; variant: Variant }): React.JSX.Element {
  const { evaluation } = useNetSelection(store, variant);
  return <NetWarnings warnings={evaluation.warnings} live />;
}

const DEFAULT_CHOICE_FALLBACK: StartChoice = { jump: 'adjacent', camps: 'closed', mode: 'two', humanSeat: 0, engineSeat: 2 };

/** The short name of each foreign-corner rule (the labels of the radio group). */
const CAMPS_LABEL = { closed: 'start_camps_closed', 'pass-through': 'start_camps_pass', open: 'start_camps_open' } as const;

interface Option<T> {
  value: T;
  label: string;
  hint?: string;
  choice: string;
}

/** A radio group that looks like a row of buttons (`seg`) or a list (`list`). Native radios: arrow keys, one tab stop, and the group is announced. */
function Radios<T extends string | number>(props: {
  legend: string;
  name: string;
  value: T;
  options: readonly Option<T>[];
  layout: 'seg' | 'list';
  onChange: (value: T) => void;
}): React.JSX.Element {
  const selectedHint = props.options.find((o) => o.value === props.value)?.hint;
  return (
    <fieldset className="ml-fieldset ml-start__group">
      <legend className="ml-kicker">{props.legend}</legend>
      <div className={props.layout === 'seg' ? 'ml-start__seg' : 'ml-start__list'}>
        {props.options.map((o) => (
          <label key={o.value} className="ml-start__opt" {...(o.value === props.value ? { 'data-checked': '' } : {})}>
            <input
              type="radio"
              name={props.name}
              value={o.value}
              checked={o.value === props.value}
              data-choice={o.choice}
              onChange={() => props.onChange(o.value)}
            />
            <span className="ml-start__label">{o.label}</span>
          </label>
        ))}
      </div>
      {selectedHint && <p className="ml-start__desc">{selectedHint}</p>}
    </fieldset>
  );
}

function BeginChoices({ choice, onChange }: { choice: StartChoice; onChange: (c: StartChoice) => void }): React.JSX.Element {
  const { t } = useT();
  const seats = (name: string): Option<Player>[] =>
    ([0, 1, 2] as const).map((seat) => ({ value: seat, label: t(`start_seat_${seat + 1}` as 'start_seat_1'), choice: `${name}-${seat + 1}` }));
  return (
    <div className="ml-start__choices">
      <Radios<JumpRule>
        legend={t('start_jump_legend')}
        name="ml-jump"
        value={choice.jump}
        layout="seg"
        options={[
          { value: 'adjacent', label: t('start_jump_adjacent'), hint: t('start_jump_adjacent_hint'), choice: 'jump-adjacent' },
          { value: 'long', label: t('start_jump_long'), hint: t('start_jump_long_hint'), choice: 'jump-long' },
        ]}
        onChange={(jump) => onChange({ ...choice, jump })}
      />
      <Radios<ForeignCamps>
        legend={t('start_camps_legend')}
        name="ml-camps"
        value={choice.camps}
        layout="seg"
        options={[
          { value: 'closed', label: t('start_camps_closed'), hint: t('start_camps_closed_hint'), choice: 'camps-closed' },
          { value: 'pass-through', label: t('start_camps_pass'), hint: t('start_camps_pass_hint'), choice: 'camps-pass' },
          { value: 'open', label: t('start_camps_open'), hint: t('start_camps_open_hint'), choice: 'camps-open' },
        ]}
        onChange={(camps) => onChange({ ...choice, camps })}
      />
      <Radios<GameMode>
        legend={t('start_mode_legend')}
        name="ml-mode"
        value={choice.mode}
        layout="list"
        options={[
          { value: 'two', label: t('start_mode_two'), choice: 'mode-two' },
          { value: 'hotseat', label: t('start_mode_hotseat'), choice: 'mode-hotseat' },
          { value: 'engines', label: t('start_mode_engines'), choice: 'mode-engines' },
        ]}
        onChange={(mode) => onChange({ ...choice, mode })}
      />
      {choice.mode === 'engines' && (
        <Radios<Player>
          legend={t('start_seat_you_legend')}
          name="ml-human-seat"
          value={choice.humanSeat}
          layout="seg"
          options={seats('human-seat')}
          onChange={(humanSeat) => onChange({ ...choice, humanSeat })}
        />
      )}
      {choice.mode === 'hotseat' && (
        <Radios<Player>
          legend={t('start_seat_engine_legend')}
          name="ml-engine-seat"
          value={choice.engineSeat}
          layout="seg"
          options={seats('engine-seat')}
          onChange={(engineSeat) => onChange({ ...choice, engineSeat })}
        />
      )}
      {choice.mode !== 'two' && <p className="ml-hint">{t('start_seat_hint')}</p>}
    </div>
  );
}

/** Two players: the old question "Do you want to begin?" answers the seat. Three players: one button, the seats are chosen above. */
function BeginActions({ choice, onStart }: { choice: StartChoice; onStart: (c: StartChoice, humanBegins: boolean) => void }): React.JSX.Element {
  const { t } = useT();
  if (choice.mode !== 'two') {
    return (
      <button type="button" className="ml-btn ml-btn--primary" data-autofocus data-choice="begin-start" onClick={() => onStart(choice, true)}>
        {t('start_go')}
      </button>
    );
  }
  return (
    <>
      <p className="ml-start__question">{t('start_begin_q')}</p>
      <button type="button" className="ml-btn ml-btn--primary" data-autofocus data-choice="begin-yes" onClick={() => onStart(choice, true)}>
        {t('start_yes')}
      </button>
      <button type="button" className="ml-btn" data-choice="begin-no" onClick={() => onStart(choice, false)}>
        {t('start_no')}
      </button>
    </>
  );
}
