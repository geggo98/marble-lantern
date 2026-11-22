// The two groups of the engine sheet that only the net engines use: the model (None first, then the models that may play the variant
// of the game) and the strength in simulations per move. Native radios: arrow keys, one tab stop per group, the group is announced.
import type { ModelInfo } from '../engines/net/model-manifest';
import { strengthFacts } from '../engines/net/model-strength';
import { SIMS_PRESETS } from '../engines/warnings';
import type { Sims } from '../engines/warnings';
import { rowsOf } from '../game/variant';
import type { Variant } from '../game/variant';
import { variantText } from './net-warnings';
import { useT } from './texts';
import type { TextKey } from './texts';
import './net.css';

/**
 * Typical time per move in ms at 50, 200 and 800 simulations: CPU backend, a fast desktop CPU, measured with the expert model in node.
 * Shown for the CPU backend only, marked as typical; a phone is slower and there is no figure for WebGPU. Strength is not stated per
 * preset: only the per-model figures of the manifest say how strong a model is (model-strength.ts).
 */
export const SIMS_MS: Readonly<Record<Sims, number>> = { 50: 15, 200: 58, 800: 241 };

const signed = (v: number, n: (v: number) => string): string => `${v > 0 ? '+' : ''}${n(v)}`;

export const MODEL_GROUP = 'model';
export const SIMS_GROUP = 'sims';

export function ModelGroup(props: {
  variant: Variant;
  /** Every model of models.json. */
  all: readonly ModelInfo[];
  /** The models that may play the variant (what is offered). */
  models: readonly ModelInfo[];
  modelId: string | null;
  /** The selected simulations per move: of several measurements of one model, the one made at these simulations is shown. */
  sims: Sims;
  onChange: (modelId: string | null) => void;
}): React.JSX.Element {
  const { t, n } = useT();
  const hidden = props.all.length > props.models.length;
  const radio = (id: string | null, label: string, body: React.ReactNode): React.JSX.Element => (
    <label key={id ?? 'none'} className="ml-choice" data-model={id ?? 'none'} {...(props.modelId === id ? { 'data-checked': '' } : {})}>
      <input type="radio" name="ml-net-model" value={id ?? ''} checked={props.modelId === id} onChange={() => props.onChange(id)} />
      <span className="ml-choice__main">
        <span className="ml-choice__name">{label}</span>
        {body}
      </span>
    </label>
  );
  return (
    <fieldset className="ml-fieldset ml-netgroup" data-net-group={MODEL_GROUP}>
      <legend className="ml-kicker">{t('net.group.model')}</legend>
      {radio(null, t('net.model.none'), <span className="ml-choice__desc">{t('net.model.none.hint')}</span>)}
      {props.models.map((m) => {
        const facts = strengthFacts(m, props.variant.players, props.sims, rowsOf(props.variant));
        const players = t('net.variant.players', { players: n(props.variant.players) });
        return radio(
          m.id,
          t(m.labelKey as TextKey),
          <>
            <span className="ml-choice__desc">{t(m.descriptionKey as TextKey)}</span>
            <span className="ml-choice__meta ml-data">{t('net.model.size', { kb: n(Math.round(m.packedBytes / 1000)) })}</span>
            {facts.length === 0 ? (
              <span className="ml-choice__desc" data-strength-none>{t('net.model.strength.none', { players })}</span>
            ) : (
              facts.map((f) => {
                const common = { players, opp: n(f.opponent), jump: t(f.jump === 'long' ? 'start_jump_long' : 'start_jump_adjacent'), sims: n(f.sims) };
                const text =
                  f.score !== undefined
                    ? t('net.model.strength.score', { ...common, score: `${n(Math.round(f.score * 100))} %`, count: n(f.opponents) })
                    : f.rows === 5
                      ? // a 5-row figure is against the classical 5-row player, on its own Elo scale: the text says so
                        f.ci95 === undefined
                        ? t('net.model.strength.rows5.noci', { ...common, elo: signed(f.elo ?? 0, n) })
                        : t('net.model.strength.rows5', { ...common, elo: signed(f.elo ?? 0, n), ci: `±${n(f.ci95)}` })
                      : f.ci95 === undefined
                        ? t('net.model.strength.noci', { ...common, elo: signed(f.elo ?? 0, n) })
                        : t('net.model.strength', { ...common, elo: signed(f.elo ?? 0, n), ci: `±${n(f.ci95)}` });
                return (
                  <span key={f.jump} className="ml-choice__desc" data-strength-fact={f.jump} data-players={f.players} data-rows={f.rows}>
                    {text}
                  </span>
                );
              })
            )}
          </>,
        );
      })}
      <p className="ml-hint" data-net-only-note>{t('net.model.hint')}</p>
      {hidden && (
        <p className="ml-hint" data-net-hidden-note>
          {t('net.model.hidden', { variant: variantText(props.variant, t) })}
        </p>
      )}
    </fieldset>
  );
}

export function SimsGroup(props: { sims: Sims; /** What plays: the typical time per move is shown for the CPU backend only. */ backend: 'wasm' | 'webgpu'; onChange: (sims: Sims) => void }): React.JSX.Element {
  const { t, n } = useT();
  return (
    <fieldset className="ml-fieldset ml-netgroup" data-net-group={SIMS_GROUP}>
      <legend className="ml-kicker">{t('net.group.sims')}</legend>
      {SIMS_PRESETS.map((s) => (
        <label key={s} className="ml-choice" data-sims={s} {...(props.sims === s ? { 'data-checked': '' } : {})}>
          <input type="radio" name="ml-net-sims" value={s} checked={props.sims === s} onChange={() => props.onChange(s)} />
          <span className="ml-choice__main">
            <span className="ml-choice__name">{t('net.sims.option', { sims: n(s) })}</span>
            {props.backend === 'wasm' && (
              <span className="ml-choice__meta ml-data" data-sims-time={s}>
                {t('net.sims.time.typical', { ms: n(SIMS_MS[s]) })}
              </span>
            )}
          </span>
        </label>
      ))}
      <p className="ml-hint">{t('net.sims.note')}</p>
    </fieldset>
  );
}
