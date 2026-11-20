// The dialog that stands before a game: "Do you want to begin?", "Continue this game?" for a game link, and the error
// for a link that does not work. A modal dialog: focus moves in, Tab stays inside, focus returns to where it was.
// Escape, a click on the backdrop beside the panel and the "keep" button close the dialog only when a game is already running
// (`onCancel`); otherwise there is nothing to go back to.
import { useId, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import type { Side } from '../game/board-state';
import { DEFAULT_VARIANT, campsOf, moveLimit, rowsOf } from '../game/variant';
import type { BoardRows, JumpRule, Player, Variant } from '../game/variant';
import type { GameMode, StartChoice } from '../app/start-choice';
import { RULES, changedRules, choiceMoveLimit, resetRules, ruleApplies, variantOfChoice, variantWithRule } from '../app/start-rules';
import type { RuleDef } from '../app/start-rules';
import { useNetSelection } from '../app/net-selection';
import type { NetSelectionStore } from '../app/net-selection';
import { evaluateSelection } from '../engines/net/selection';
import { listDefinitions } from '../engines/registry';
import { NetWarnings } from './net-warnings';
import { Icon } from './primitives';
import { DialogLanguageRow } from './LanguageToggle';
import { useT } from './texts';
import type { TextKey } from './texts';
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
  /** The begin dialog keeps "keep my game" and the language row below its sticky actions; the other dialogs end with the language row. */
  let tail: React.JSX.Element | null = null;

  if (model.kind === 'begin') {
    title = t('start_title');
    body = <p id={bodyId}>{t('start_lead')}</p>;
    actions = <BeginActions choice={choice} onStart={model.onStart} />;
    tail = (
      <div className="ml-start__tail">
        {onCancel && (
          <button type="button" className="ml-btn ml-btn--quiet" data-choice="begin-keep" onClick={onCancel}>
            {t('link_keep')}
          </button>
        )}
        <DialogLanguageRow />
      </div>
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
        {model.variant && <p data-testid="link-rows">{t('link_rows', { rows: rowsOf(model.variant) })}</p>}
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
        {model.kind === 'begin' && <BeginChoices choice={choice} onChange={setChoice} net={model.net} />}
        {model.kind === 'begin' && model.net && <StartNetWarning store={model.net} variant={variantOfChoice(choice)} />}
        {model.kind === 'link' && model.net && model.variant && <StartNetWarning store={model.net} variant={model.variant} />}
        <div className="ml-start__actions" {...(model.kind === 'begin' && choice.mode === 'two' ? { 'data-two': '' } : {})}>{actions}</div>
        {tail ?? <DialogLanguageRow />}
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

// Only the dialogs of a link and of a broken link use this; they never read the size from it (the game of a link has its own size, 4 rows without t=).
const DEFAULT_CHOICE_FALLBACK: StartChoice = { jump: 'adjacent', camps: 'closed', rows: 4, ranking: 'first', mode: 'two', humanSeat: 0, engineSeat: 2 };

/** The short name of each foreign-corner rule (the labels of the radio group). */
const CAMPS_LABEL = { closed: 'start_camps_closed', 'pass-through': 'start_camps_pass', open: 'start_camps_open' } as const;

interface Option<T> {
  value: T;
  label: string;
  hint?: string;
  choice: string;
}

/** What an option of a rule shows when an engine cannot play it: `warn` (a selected net engine would move at random) or `unavailable` (no engine of this build plays it). */
type OptionState = 'warn' | 'unavailable';

/** A radio group that looks like a row of buttons (`seg`) or a list (`list`). Native radios: arrow keys, one tab stop, and the group is announced. */
function Radios<T extends string | number>(props: {
  legend: string;
  name: string;
  value: T;
  options: readonly Option<T>[];
  layout: 'seg' | 'list';
  onChange: (value: T) => void;
  /** A rule of the panel: the default option carries the tag "default", and the legend says "changed" while the value differs from it. */
  defaultValue?: T;
  /** The state of an option an engine cannot play; the reason line below the group is `reasonId`. */
  stateOf?: (value: T) => OptionState | undefined;
  reason?: string;
  reasonId?: string;
}): React.JSX.Element {
  const { t } = useT();
  const selectedHint = props.options.find((o) => o.value === props.value)?.hint;
  const changed = props.defaultValue !== undefined && props.value !== props.defaultValue;
  return (
    <fieldset className="ml-fieldset ml-start__group">
      <legend className="ml-kicker">
        {props.legend}
        {changed && <span className="ml-start__changed">{t('rules_changed_tag')}</span>}
      </legend>
      <div className={props.layout === 'seg' ? 'ml-start__seg' : 'ml-start__list'}>
        {props.options.map((o) => {
          const state = props.stateOf?.(o.value);
          const unavailable = state === 'unavailable';
          return (
            <label
              key={o.value}
              className="ml-start__opt"
              {...(o.value === props.value ? { 'data-checked': '' } : {})}
              {...(state === 'unavailable' ? { 'data-unavailable': '' } : {})}
              {...(state === 'warn' ? { 'data-engine-warn': '' } : {})}
            >
              <input
                type="radio"
                name={props.name}
                value={o.value}
                checked={o.value === props.value}
                data-choice={o.choice}
                // aria-disabled, not disabled: a disabled radio leaves the arrow-key sequence, and the reason would stay unreachable.
                {...(unavailable ? { 'aria-disabled': true } : {})}
                {...(state && props.reasonId ? { 'aria-describedby': props.reasonId } : {})}
                onChange={() => {
                  if (!unavailable) props.onChange(o.value);
                }}
              />
              <span className="ml-start__label">
                {state === 'warn' && <Icon name="alert" />}
                {o.label}
              </span>
              {props.defaultValue !== undefined && o.value === props.defaultValue && <span className="ml-start__default">{t('rules_default_tag')}</span>}
            </label>
          );
        })}
      </div>
      {selectedHint && <p className="ml-start__desc">{selectedHint}</p>}
      {props.reason && (
        <p className="ml-start__reason" id={props.reasonId} data-rule-reason="">
          {props.reason}
        </p>
      )}
    </fieldset>
  );
}

function BeginChoices({ choice, onChange, net }: { choice: StartChoice; onChange: (c: StartChoice) => void; net: NetSelectionStore | undefined }): React.JSX.Element {
  const { t } = useT();
  const seats = (name: string): Option<Player>[] =>
    ([0, 1, 2] as const).map((seat) => ({ value: seat, label: t(`start_seat_${seat + 1}` as 'start_seat_1'), choice: `${name}-${seat + 1}` }));
  return (
    <div className="ml-start__choices">
      <Radios<BoardRows>
        legend={t('start_rows_legend')}
        name="ml-rows"
        value={choice.rows}
        layout="seg"
        options={[
          { value: 4, label: t('start_rows_4'), hint: t('start_rows_4_hint'), choice: 'rows-4' },
          { value: 5, label: t('start_rows_5'), hint: t('start_rows_5_hint'), choice: 'rows-5' },
        ]}
        onChange={(rows) => onChange({ ...choice, rows })}
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
      {net ? <NetRulesPanel choice={choice} onChange={onChange} store={net} /> : <RulesPanel choice={choice} onChange={onChange} stateOf={engineStateOf} />}
    </div>
  );
}

/** The state an option of a rule has: `unavailable` when no engine registered in this build declares that it plays the variant. Without any engine (tests, boot) every option is available. */
type StateOf = (choice: StartChoice, rule: RuleDef, value: string) => OptionState | undefined;

const engineStateOf: StateOf = (choice, rule, value) => {
  const engines = listDefinitions();
  if (engines.length === 0) return undefined;
  const variant = variantWithRule(choice, rule, value);
  return engines.some((e) => !e.supportsVariant || e.supportsVariant(variant)) ? undefined : 'unavailable';
};

/**
 * The panel with the selection of the net engines read: an option is marked `warn` when the selected net engine or model warns about the
 * variant with that value, but not about the variant with the default of that rule (the option is the cause). The same function as
 * the warning below the panel (evaluateSelection), once per option.
 */
function NetRulesPanel({ choice, onChange, store }: { choice: StartChoice; onChange: (c: StartChoice) => void; store: NetSelectionStore }): React.JSX.Element {
  const { selection, availability, lastModel, models } = useNetSelection(store, variantOfChoice(choice));
  const warns = (rule: RuleDef, value: string): boolean =>
    evaluateSelection(selection, variantWithRule(choice, rule, value), availability, models, lastModel).warnings.length > 0;
  const stateOf: StateOf = (c, rule, value) => {
    const unavailable = engineStateOf(c, rule, value);
    if (unavailable) return unavailable;
    return value !== rule.default && warns(rule, value) && !warns(rule, rule.default) ? 'warn' : undefined;
  };
  return <RulesPanel choice={choice} onChange={onChange} stateOf={stateOf} />;
}

/** The text of a rule's current value for the summary line. */
const valueText = (rule: RuleDef, choice: StartChoice, t: (k: TextKey) => string): string =>
  t(rule.options.find((o) => o.value === choice[rule.field])!.label as TextKey);

/**
 * The collapsed panel of the rules (docs/web-ui.md, "Rules panel"): the editable rules from the table in start-rules.ts, then the rules of
 * the engine that no browser engine takes a value for, as text. The summary names the rules that differ from the default.
 */
function RulesPanel({ choice, onChange, stateOf }: { choice: StartChoice; onChange: (c: StartChoice) => void; stateOf: StateOf }): React.JSX.Element {
  const { t } = useT();
  const id = useId();
  const nowId = `${id}-now`;
  const changed = changedRules(choice);
  const [resetDone, setResetDone] = useState(false);
  const summary =
    changed.length === 0
      ? t('rules_summary_default')
      : changed.map((r) => t('rules_summary_item', { rule: t(r.legend as TextKey), value: valueText(r, choice, t) })).join(' · ');
  const three = choice.mode !== 'two';
  const editable = (family: RuleDef['family']): React.JSX.Element[] =>
    RULES.filter((r) => r.family === family && ruleApplies(r, choice)).map((r) => {
      const reasonId = `${id}-${r.id}-reason`;
      const selectedState = stateOf(choice, r, choice[r.field]);
      const noEngine = r.options.find((o) => stateOf(choice, r, o.value) === 'unavailable');
      const reason =
        noEngine && (selectedState !== 'warn')
          ? t('rules_reason_no_engine', { value: t(noEngine.label as TextKey) })
          : selectedState === 'warn'
            ? t('rules_reason_selected_engine')
            : undefined;
      return (
        <Radios<string>
          key={r.id}
          legend={t(r.legend as TextKey)}
          name={`ml-${r.id}`}
          value={choice[r.field]}
          defaultValue={r.default}
          layout="seg"
          options={r.options.map((o) => ({ value: o.value, label: t(o.label as TextKey), hint: t(o.hint as TextKey), choice: o.choice }))}
          onChange={(value) => onChange({ ...choice, [r.field]: value } as StartChoice)}
          stateOf={(value) => stateOf(choice, r, value)}
          {...(reason ? { reason, reasonId } : {})}
        />
      );
    });
  const fixed = (rows: { key: string; legend: string; value: string; hint?: string }[]): React.JSX.Element => (
    <dl className="ml-rules__fixed" aria-label={t('rules_fixed_label')}>
      {rows.map((r) => (
        <div key={r.key} data-fixed-rule={r.key}>
          <dt>{r.legend}</dt>
          <dd>
            <span>{r.value}</span>
            {r.hint && <small>{r.hint}</small>}
          </dd>
        </div>
      ))}
    </dl>
  );
  const movesId = `${id}-moves`;
  const endId = `${id}-end`;
  return (
    <details
      className="ml-rules"
      data-rules-panel=""
      onToggle={(e) => {
        // Opened by the person: bring the panel to the top of the sheet. The initial render is closed and sends no event.
        const el = e.currentTarget;
        if (el.open && typeof el.scrollIntoView === 'function') {
          const reduced = typeof matchMedia === 'function' && matchMedia('(prefers-reduced-motion: reduce)').matches;
          el.scrollIntoView({ block: 'start', behavior: reduced ? 'auto' : 'smooth' });
        }
      }}
    >
      <summary aria-describedby={nowId} data-rules-summary="">
        <span className="ml-rules__chev" aria-hidden="true" />
        <span className="ml-rules__title">{t('rules_title')}</span>
        {changed.length > 0 && (
          <span className="ml-rules__count" data-rules-count="">
            {t(changed.length === 1 ? 'rules_changed_count_one' : 'rules_changed_count_other', { n: changed.length })}
          </span>
        )}
        <span className="ml-rules__now" id={nowId} data-rules-now="" {...(changed.length > 0 ? { 'data-changed': '' } : {})}>
          {summary}
        </span>
      </summary>
      <div className="ml-rules__body">
        <section className="ml-rules__family" aria-labelledby={movesId}>
          <h3 id={movesId}>{t('rules_family_moves')}</h3>
          {editable('moves')}
          {fixed([
            { key: 'leave', legend: t('rules_leave_legend'), value: t('rules_leave_forbidden'), hint: t('rules_leave_forbidden_hint') },
            { key: 'suicide', legend: t('rules_suicide_legend'), value: t('rules_suicide_illegal'), hint: t('rules_suicide_illegal_hint') },
          ])}
        </section>
        <section className="ml-rules__family" aria-labelledby={endId}>
          <h3 id={endId}>{t('rules_family_end')}</h3>
          {editable('end')}
          {fixed([
            ...(three ? [] : [{ key: 'ranking', legend: t('rules_ranking_legend'), value: t('rules_ranking_first'), hint: t('rules_reason_three_only') }]),
            { key: 'limit', legend: t('rules_limit_legend'), value: t('rules_limit_value', { n: choiceMoveLimit(choice) }), hint: t('rules_limit_hint') },
            { key: 'draw', legend: t('rules_draw_legend'), value: t('rules_draw_distance'), hint: t('rules_draw_distance_hint') },
            { key: 'repetition', legend: t('rules_repetition_legend'), value: t('rules_repetition_none'), hint: t('rules_repetition_none_hint') },
            { key: 'blockade', legend: t('rules_blockade_legend'), value: t('rules_blockade_fills'), hint: t('rules_blockade_fills_hint') },
          ])}
        </section>
        <div className="ml-rules__foot">
          <button
            type="button"
            className="ml-btn ml-btn--quiet"
            data-choice="rules-reset"
            aria-disabled={changed.length === 0}
            onClick={() => {
              if (changed.length === 0) return;
              onChange(resetRules(choice));
              setResetDone(true);
            }}
          >
            {t('rules_reset')}
          </button>
          <p className="ml-rules__done" role="status" aria-live="polite">
            {resetDone && changed.length === 0 ? t('rules_reset_done') : ''}
          </p>
        </div>
      </div>
    </details>
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
