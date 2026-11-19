// The two groups of the engine sheet that only the net engines use: the model (None first, then the models that may play the variant
// of the game) and the strength in simulations per move. Native radios: arrow keys, one tab stop per group, the group is announced.
import type { ModelInfo } from '../engines/net/model-manifest';
import { strengthFacts } from '../engines/net/model-strength';
import { SIMS_PRESETS } from '../engines/warnings';
import type { Sims } from '../engines/warnings';
import type { Variant } from '../game/variant';
import { variantText } from './net-warnings';
import { useT } from './texts';
import type { TextKey } from './texts';
import './net.css';

/**
 * Time per move on the CPU backend at 50, 200 and 800 simulations, measured with the C++ WebAssembly forward pass on a fast desktop
 * (docs/web-ui.md, "Net engines"). A phone is slower; WebGPU only helps from 800 simulations.
 */
export const SIMS_MS: Readonly<Record<Sims, number>> = { 50: 15, 200: 58, 800: 241 };
/** Elo gain over 50 simulations (about the strongest classical web level), measured in the same series. */
export const SIMS_ELO: Readonly<Record<Sims, number>> = { 50: 0, 200: 255, 800: 390 };
const SIMS_LABEL: Readonly<Record<Sims, TextKey>> = { 50: 'net.sims.fast', 200: 'net.sims.strong', 800: 'net.sims.vstrong' };

export const MODEL_GROUP = 'model';
export const SIMS_GROUP = 'sims';

export function ModelGroup(props: {
  variant: Variant;
  /** Every model of models.json. */
  all: readonly ModelInfo[];
  /** The models that may play the variant (what is offered). */
  models: readonly ModelInfo[];
  modelId: string | null;
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
        const facts = strengthFacts(m);
        return radio(
          m.id,
          t(m.labelKey as TextKey),
          <>
            <span className="ml-choice__desc">{t(m.descriptionKey as TextKey)}</span>
            <span className="ml-choice__meta ml-data">{t('net.model.size', { kb: n(Math.round(m.packedBytes / 1000)) })}</span>
            {facts.length === 0 ? (
              <span className="ml-choice__desc">{t('net.model.strength.none')}</span>
            ) : (
              facts.map((f) => (
                <span key={f.jump} className="ml-choice__desc" data-strength-fact={f.jump}>
                  {t('net.model.strength', {
                    elo: `${f.elo > 0 ? '+' : ''}${n(f.elo)}`,
                    ci: f.ci95 === undefined ? '' : `±${n(f.ci95)}`,
                    opp: n(f.opponent),
                    jump: t(f.jump === 'long' ? 'start_jump_long' : 'start_jump_adjacent'),
                    sims: n(f.sims),
                  })}
                </span>
              ))
            )}
          </>,
        );
      })}
      {hidden && (
        <p className="ml-hint" data-net-hidden-note>
          {t('net.model.hidden', { variant: variantText(props.variant, t) })}
        </p>
      )}
    </fieldset>
  );
}

export function SimsGroup(props: { sims: Sims; onChange: (sims: Sims) => void }): React.JSX.Element {
  const { t, n } = useT();
  return (
    <fieldset className="ml-fieldset ml-netgroup" data-net-group={SIMS_GROUP}>
      <legend className="ml-kicker">{t('net.group.sims')}</legend>
      {SIMS_PRESETS.map((s) => (
        <label key={s} className="ml-choice" data-sims={s} {...(props.sims === s ? { 'data-checked': '' } : {})}>
          <input type="radio" name="ml-net-sims" value={s} checked={props.sims === s} onChange={() => props.onChange(s)} />
          <span className="ml-choice__main">
            <span className="ml-choice__name">{t(SIMS_LABEL[s])}</span>
            <span className="ml-choice__meta ml-data">
              {t('net.sims.count', { sims: n(s) })} · ≈ {t('net.sims.time', { ms: n(SIMS_MS[s]) })}
            </span>
            <span className="ml-choice__desc">{s === 50 ? t('net.sims.elo.base') : t('net.sims.elo', { sims: n(s), elo: `+${n(SIMS_ELO[s])}` })}</span>
          </span>
        </label>
      ))}
      <p className="ml-hint">{t('net.sims.note')}</p>
    </fieldset>
  );
}
