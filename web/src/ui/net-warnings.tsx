// Warnings of the net engines as messages with an action (interface contract IC4, engines/warnings.ts): one text per code, never
// colour alone (icon + text). The same list serves the engine sheet (live, from evaluateSelection), the start dialog and the engine
// card (from the session). The wording lives in the i18n catalogs (keys net.warn.*).
import { listModels, modelsFor } from '../engines/net/model-manifest';
import type { EngineWarning, ReasonCode } from '../engines/warnings';
import { campsOf, rowsOf } from '../game/variant';
import type { Variant } from '../game/variant';
import { Icon } from './primitives';
import { useT } from './texts';
import type { TextKey, Translate } from './texts';

export type WarningAction = 'choose-model' | 'choose-other' | 'retry';

export interface DescribedWarning {
  code: EngineWarning['code'];
  text: string;
  action?: WarningAction;
  /** 'info': the net still plays (the CPU replaces the GPU); 'alert': random moves or a failed load. */
  tone: 'info' | 'alert';
  /** The technical cause in English, for the collapsed "Details" only. */
  detail?: string;
}

/**
 * The jump rule and the number of players of a variant as short data text, the foreign-corner rule when it is not `closed`, and the
 * house size when it is 5 rows. 4 rows is the unmarked default, like `closed`: a notice about a model names 5 rows, so that it never
 * reads as a statement about the 4-row game.
 */
export function variantText(variant: Variant | undefined, t: Translate): string {
  if (!variant) return '';
  const base = `${t(variant.jump === 'long' ? 'start_jump_long' : 'start_jump_adjacent')} · ${t('net.variant.players', { players: String(variant.players) })}`;
  const camps = campsOf(variant);
  const withCamps = camps === 'closed' ? base : `${base} · ${t('link_camps', { rule: t(camps === 'open' ? 'start_camps_open' : 'start_camps_pass') })}`;
  return rowsOf(variant) === 5 ? `${withCamps} · ${t('net.variant.rows5')}` : withCamps;
}

/** The localized text of a reason code (catalog keys net.reason.<code>); empty for no code. */
export function reasonText(code: ReasonCode | undefined, t: Translate, model = '', variant = ''): string {
  return code ? t(`net.reason.${code}` as TextKey, { model, variant }) : '';
}

/** The technical English text behind a message, folded away: a native Details, so it works without script and by keyboard. */
export function ReasonDetails({ detail }: { detail: string | undefined }): React.JSX.Element | null {
  const { t } = useT();
  if (!detail) return null;
  return (
    <details className="ml-netwarn__details" data-net-details>
      <summary>{t('net.details.summary')}</summary>
      <code lang="en" translate="no">{detail}</code>
    </details>
  );
}

export function modelName(id: string | undefined, t: Translate): string {
  if (!id) return '';
  const info = listModels().find((m) => m.id === id);
  return info ? t(info.labelKey as TextKey) : id;
}

/**
 * The messages of a list of warnings. A fallback-random warning is the consequence of another warning of the list, and the texts of
 * those say it already: it shows up on its own only when it is the only one.
 */
export function describeWarnings(warnings: readonly EngineWarning[], t: Translate): DescribedWarning[] {
  const causes = warnings.filter((w) => w.code !== 'fallback-random');
  const shown = causes.length > 0 ? causes : warnings;
  return shown.map((w): DescribedWarning => {
    const model = modelName(w.modelId, t);
    const variant = variantText(w.variant, t);
    const values = { model, variant, reason: reasonText(w.reason, t, model, variant) };
    const detail = w.detail ? { detail: w.detail } : {};
    return { ...describeOne(w, values, t), tone: w.code === 'backend-unavailable' && w.fallback === 'wasm' ? 'info' : 'alert', ...detail };
  });
}

function describeOne(w: EngineWarning, values: { model: string; variant: string; reason: string }, t: Translate): Pick<DescribedWarning, 'code' | 'text' | 'action'> {
  switch (w.code) {
    case 'no-model':
      // No net exists for 5 rows: the list behind "Choose a model" would be empty, so the text says why and offers no button.
      if (w.variant && rowsOf(w.variant) === 5 && modelsFor(w.variant).length === 0) return { code: w.code, text: t('net.warn.no-model-rows', values) };
      return { code: w.code, text: t('net.warn.no-model', values), action: 'choose-model' };
    case 'model-variant-mismatch':
      return { code: w.code, text: t('net.warn.model-variant-mismatch', values), action: 'choose-other' };
    case 'backend-unavailable':
      return { code: w.code, text: t(w.fallback === 'wasm' ? 'net.warn.backend-cpu' : 'net.warn.backend-random', values) };
    case 'model-load-failed':
      return { code: w.code, text: t('net.warn.model-load-failed', values), action: 'retry' };
    default:
      return { code: w.code, text: t('net.warn.fallback-random', values) };
  }
}

export interface NetWarningsProps {
  warnings: readonly EngineWarning[];
  /** Called for the buttons; a code whose handler is missing shows no button. */
  onAction?: Partial<Record<WarningAction, () => void>>;
  /** Mark the list as a polite live region (the sheet); the play screen has its own. */
  live?: boolean;
}

export function NetWarnings({ warnings, onAction, live = false }: NetWarningsProps): React.JSX.Element | null {
  const { t } = useT();
  const items = describeWarnings(warnings, t);
  // A live region must exist before its text changes, so the wrapper stays when the list is empty.
  if (items.length === 0) return live ? <div aria-live="polite" data-net-warning-live /> : null;
  const list = (
    <ul className="ml-netwarn" data-net-warning-list>
      {items.map((w) => {
        const handler = w.action ? onAction?.[w.action] : undefined;
        return (
          <li key={w.code} className="ml-netwarn__item" data-net-warning={w.code} data-tone={w.tone}>
            <Icon name={w.tone} />
            <span className="ml-netwarn__text">{w.text}</span>
            <ReasonDetails detail={w.detail} />
            {w.action && handler && (
              <button type="button" className="ml-btn" data-warning-action={w.action} onClick={handler}>
                {t(w.action === 'choose-model' ? 'net.action.choose-model' : w.action === 'choose-other' ? 'net.action.choose-other' : 'net.action.retry')}
              </button>
            )}
          </li>
        );
      })}
    </ul>
  );
  return live ? <div aria-live="polite" data-net-warning-live>{list}</div> : list;
}

/** Which compact notice the play screen needs: random moves, the CPU instead of the GPU, or none (only net engines have one). */
export function netNoticeKind(netEngine: boolean, warnings: readonly EngineWarning[]): 'random' | 'cpu' | null {
  if (!netEngine) return null;
  if (warnings.some((w) => w.code === 'fallback-random')) return 'random';
  return warnings.some((w) => w.code === 'backend-unavailable') ? 'cpu' : null;
}

/**
 * The compact notice of the play screen: in the DOM only while a net engine is selected AND the engine plays random moves or fell
 * back to the CPU net. Without a warning there is no element, no live region and no grid row, so a classical game, and a net game that
 * works, look exactly as before the net UI. The warnings change at newGame and when the settings are applied, so the board moves then,
 * not during a game (an engine that loses its backend in the middle of a game is the one exception). A button opens the engine sheet.
 *
 * Text budget for the catalog (net.notice.random, net.notice.cpu, net.action.settings): at most 40 characters in German, one line at
 * 320 px; the button label one short word.
 */
export function NetNotice(props: { netEngine: boolean; warnings: readonly EngineWarning[]; onOpen: () => void }): React.JSX.Element | null {
  const { t } = useT();
  const kind = netNoticeKind(props.netEngine, props.warnings);
  if (!kind) return null;
  return (
    <div className="ml-play__netnote" role="status" aria-live="polite" aria-label={t('net.notice.label')} data-net-notice={kind}>
      <div className="ml-netnote" data-tone={kind === 'random' ? 'alert' : 'info'}>
        <Icon name={kind === 'random' ? 'alert' : 'info'} />
        <span className="ml-netnote__text">{t(kind === 'random' ? 'net.notice.random' : 'net.notice.cpu')}</span>
        <button type="button" className="ml-btn" data-net-notice-open onClick={props.onOpen}>
          {t('net.action.settings')}
        </button>
      </div>
    </div>
  );
}
