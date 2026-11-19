// The cards of the play screen: status bar, controls, engine card, figures of the last engine move, move list,
// loading card, result card, error card. Class names are those of design/mockup.html.
import type { ChooseResult, EngineInfo, Progress } from '../engines/engine';
import { Icon, Swatch, Tag } from './primitives';
import type { SideStyle } from './primitives';
import { notation } from './board-model';
import type { MoveDescription } from './board-model';
import { useT } from './texts';
import type { TextKey } from './texts';

export const NODE_LEVELS = [1000, 5000, 20000, 100000, 500000] as const;
export const DEFAULT_LEVEL = 2;

export function levelKey(level: number): TextKey {
  if (level >= NODE_LEVELS.length) return 'level_custom'; // a custom node budget (skill/level.ts)
  return `level_${level + 1}` as TextKey;
}

export function StatusBar(props: {
  state: 'ready' | 'thinking';
  style: SideStyle;
  text: string;
  ply: number;
  limit: number;
}): React.JSX.Element {
  const { t } = useT();
  return (
    <div className="ml-status" data-state={props.state} role="status">
      <span className="ml-status__who">
        <Swatch style={props.style} />
        <span>{props.text}</span>
      </span>
      <span className="ml-status__ply">
        {t('ply')} {props.ply}/{props.limit}
      </span>
      {props.state === 'thinking' && <span className="ml-status__bar" aria-hidden="true" />}
    </div>
  );
}

export interface ControlsProps {
  disabled: boolean;
  canUndo: boolean;
  demoRunning: boolean;
  onNewGame: () => void;
  onUndo: () => void;
  onSwap: () => void;
  onDemo: () => void;
  /** The swap button is offered (default true): not with two human seats (hot-seat). */
  canSwap?: boolean;
  /** 'secondary': only swap sides and the demo; undo and new game stand in the bottom bar of a small screen. */
  part?: 'all' | 'secondary';
}

export function Controls(props: ControlsProps): React.JSX.Element {
  const { t } = useT();
  return (
    <div className="ml-controls">
      {props.part !== 'secondary' && (
        <>
          <button type="button" className="ml-btn ml-btn--primary" disabled={props.disabled} onClick={props.onNewGame}>
            <Icon name="plus" />
            <span>{t('new_game')}</span>
          </button>
          <button type="button" className="ml-btn" disabled={props.disabled || !props.canUndo} onClick={props.onUndo}>
            <Icon name="undo" />
            <span>{t('undo')}</span>
          </button>
        </>
      )}
      {props.canSwap !== false && (
        <button type="button" className="ml-btn" disabled={props.disabled || props.demoRunning} onClick={props.onSwap}>
          <Icon name="swap" />
          <span>{t('swap_sides')}</span>
        </button>
      )}
      <button type="button" className="ml-btn" disabled={props.disabled && !props.demoRunning} onClick={props.onDemo}>
        <Icon name="play" />
        <span>{props.demoRunning ? t('demo_stop') : t('demo')}</span>
      </button>
    </div>
  );
}

const RUNTIME_TAG: Record<EngineInfo['runtime'], string> = { wasm: 'Wasm', 'wasm-gc': 'Wasm GC', js: 'JS', server: 'JVM' };
export const runtimeTag = (runtime: EngineInfo['runtime']): string => RUNTIME_TAG[runtime];

export function EngineCard(props: {
  info: EngineInfo | null;
  level: number;
  onChange: () => void;
}): React.JSX.Element {
  const { t, n } = useT();
  return (
    <section className="ml-card ml-engine" aria-label={t('engine_heading')}>
      <div className="ml-engine__head">
        <div>
          <p className="ml-kicker">{t('engine_heading')}</p>
          <h2 className="ml-card__title">{props.info ? t(props.info.labelKey as TextKey) : '—'}</h2>
        </div>
        {props.info && (
          <span className="ml-engine__tags">
            <Tag>{props.info.language}</Tag>
            <Tag>{runtimeTag(props.info.runtime)}</Tag>
          </span>
        )}
      </div>
      <p className="ml-engine__level">
        <span>{t(levelKey(props.level))}</span>
        <strong>{n(NODE_LEVELS[props.level] ?? props.level)}</strong>
        <span>{t('nodes_per_move')}</span>
      </p>
      <button type="button" className="ml-btn" aria-haspopup="dialog" onClick={props.onChange}>
        <Icon name="tune" />
        <span>{t('engine_change')}</span>
      </button>
    </section>
  );
}

export function Telemetry(props: { result: ChooseResult | null; stale: boolean }): React.JSX.Element | null {
  const { t, n } = useT();
  const r = props.result;
  if (!r) return null;
  return (
    <dl className="ml-card ml-telemetry" aria-label={t('last_engine_move')} {...(props.stale ? { 'data-stale': 'true' } : {})}>
      <div className="ml-telemetry__cell">
        <dt className="ml-kicker">{t('nodes')}</dt>
        <dd className="ml-telemetry__value">{n(r.nodes)}</dd>
      </div>
      <div className="ml-telemetry__cell">
        <dt className="ml-kicker">{t('time')}</dt>
        <dd className="ml-telemetry__value">
          {n(Math.round(r.ms))} <small>ms</small>
        </dd>
      </div>
      <div className="ml-telemetry__cell">
        <dt className="ml-kicker">{t('depth')}</dt>
        <dd className="ml-telemetry__value">{r.raceMove ? '–' : n(r.depth)}</dd>
      </div>
      <div className="ml-telemetry__cell">
        <dt className="ml-kicker">{t('source')}</dt>
        <dd className="ml-telemetry__value">
          <Tag solid={r.raceMove}>{r.raceMove ? t('src_race') : t('src_search')}</Tag>
        </dd>
      </div>
    </dl>
  );
}

export function MoveList(props: {
  moves: readonly MoveDescription[];
  styleOf: (side: number) => SideStyle;
  defaultOpen: boolean;
}): React.JSX.Element {
  const { t } = useT();
  const rows: MoveDescription[][] = [];
  props.moves.forEach((m, i) => {
    if (i % 2 === 0) rows.push([m]);
    else rows[rows.length - 1]!.push(m);
  });
  const lastIndex = props.moves.length - 1;
  return (
    <details className="ml-card ml-moves" {...(props.defaultOpen ? { open: true } : {})}>
      <summary className="ml-moves__summary">
        <span>{t('moves_heading')}</span>
        <Tag>{props.moves.length}</Tag>
      </summary>
      <ol className="ml-moves__list" tabIndex={0}>
        {rows.map((row, r) => (
          <li key={r} className="ml-moves__row">
            <span className="ml-moves__no">{r + 1}.</span>
            {row.map((m, c) => (
              <span key={c} className="ml-moves__move" {...(r * 2 + c === lastIndex ? { 'aria-current': 'true' as const } : {})}>
                <Swatch style={props.styleOf(m.side)} />
                {notation(m).split(/([›»])/).map((part, i) =>
                  part === '»' ? (
                    <span key={i} className="ml-moves__jump">»</span>
                  ) : (
                    part
                  ),
                )}
              </span>
            ))}
            {row.length === 1 && <span />}
          </li>
        ))}
      </ol>
    </details>
  );
}

const PHASES: Progress['phase'][] = ['fetch', 'inflate', 'verify', 'instantiate', 'table'];

export function mb(bytes: number, n: (v: number) => string): string {
  return `${n(Math.round(bytes / 10_000) / 100)} MB`;
}

export function LoadingCard(props: { engineLabel: string; progress: Progress | null }): React.JSX.Element {
  const { t, n } = useT();
  const current = props.progress ? PHASES.indexOf(props.progress.phase) : -1;
  const fraction =
    props.progress && props.progress.total > 0 ? Math.min(1, props.progress.loaded / props.progress.total) : 0;
  const percent = Math.round(((Math.max(current, 0) + fraction) / PHASES.length) * 100);
  return (
    <section className="ml-card ml-loading" aria-busy="true">
      <div>
        <p className="ml-kicker">{props.engineLabel}</p>
        <h2 className="ml-card__title">{t('loading_title')}</h2>
      </div>
      <div
        className="ml-progress"
        role="progressbar"
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuenow={percent}
        aria-label={t('loading_title')}
      >
        <div className="ml-progress__fill" style={{ width: `${percent}%` }} />
      </div>
      <ol className="ml-phases">
        {PHASES.map((phase, i) => {
          const state = i < current ? 'done' : i === current ? 'active' : 'pending';
          const detail =
            state === 'active' && props.progress && phase === 'fetch' && props.progress.total > 0
              ? `${mb(props.progress.loaded, n)} / ${mb(props.progress.total, n)}`
              : '';
          return (
            <li key={phase} className="ml-phase" data-state={state} {...(state === 'active' ? { 'aria-current': 'step' as const } : {})}>
              {state === 'done' ? <Icon name="check" /> : state === 'active' ? <span className="ml-spinner" /> : <span className="ml-phase__dot" />}
              <span>{t(`phase_${phase}` as TextKey)}</span>
              <span className="ml-data">{detail}</span>
            </li>
          );
        })}
      </ol>
    </section>
  );
}

export function ResultCard(props: {
  winner: SideStyle | null;
  headline: string;
  plies: number;
  distances?: { style: SideStyle; label: string; value: number; ahead: boolean }[];
  note?: string;
}): React.JSX.Element {
  const { t, n } = useT();
  return (
    <section className="ml-card ml-result" role="status">
      <h2 className="ml-result__headline">
        {props.winner && <Swatch style={props.winner} />}
        <span>{props.headline}</span>
      </h2>
      <p className="ml-result__meta">
        {props.note ?? t('result_after')} <span className="ml-data">{n(props.plies)}</span> {t('plies')}
      </p>
      {props.distances && (
        <div className="ml-score" role="list" aria-label={t('distance_heading')}>
          {props.distances.map((d) => (
            <div key={d.label} className="ml-score__row" role="listitem" {...(d.ahead ? { 'data-ahead': 'true' } : {})}>
              <Swatch style={d.style} />
              <span>
                {d.label}
                {d.ahead ? ` · ${t('ahead')}` : ''}
              </span>
              <span className="ml-score__value">{n(d.value)}</span>
            </div>
          ))}
        </div>
      )}
    </section>
  );
}

export interface AlertAction {
  label: string;
  primary?: boolean;
  onClick: () => void;
}

export function AlertCard(props: { title: string; body: string; code: string; actions: AlertAction[] }): React.JSX.Element {
  return (
    <section className="ml-alert" role="alert">
      <Icon name="alert" />
      <div className="ml-alert__body">
        <h2 className="ml-alert__title">{props.title}</h2>
        <p>{props.body}</p>
        <p className="ml-alert__code">{props.code}</p>
        <div className="ml-alert__actions">
          {props.actions.map((a) => (
            <button key={a.label} type="button" className={a.primary ? 'ml-btn ml-btn--primary' : 'ml-btn'} onClick={a.onClick}>
              {a.label}
            </button>
          ))}
        </div>
      </div>
    </section>
  );
}
