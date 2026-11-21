// V13: the learning-rate schedule (Fig. 4.1). The curve is computed in TypeScript with the formula of LearningRateSchedule.java (lr.ts);
// the presets come from lr-presets.json. The topic "tuning" reuses this component. Layout notes: web/design/about/about-notes.md, "V13".
import { useState } from 'react';
import { Checkbox, Figure, Legend, LegendItem, Manipulate, Plot, PlotCursor, PlotLine, Presets, Readout, Slider } from '../kit';
import { Markup } from '../kit/markup';
import { trainingCatalog } from './catalogs';
import { axisExponent, curve, presetSchedules, rateAt, rateDisplay, sameSchedule, scientific } from './lr';
import { sciPlain } from '../kit/sci';
import type { PresetId, Schedule } from './lr';
import { FootChips, useCompact, useFigText } from './shared';

export const EXP_MIN = -7;
export const EXP_MAX = -2;

/** The nearest slider position for a rate: the exponent of ten, rounded to the slider's step of 0.001. */
export const exponentOf = (rate: number): number => Math.round(Math.log10(rate) * 1000) / 1000;

export function LrFigure(): React.JSX.Element {
  const fig = useFigText(trainingCatalog);
  const { ref, compact } = useCompact();
  const presets = presetSchedules();
  const [s, setS] = useState<Schedule>(presets.sweep1);
  const [mark, setMark] = useState(Math.round(presets.sweep1.steps / 2));
  const [log, setLog] = useState(false);

  const pressed = (Object.keys(presets) as PresetId[]).find((id) => sameSchedule(presets[id], s)) ?? null;
  const last = s.steps - 1;
  const at = Math.min(mark, last);
  const rate = rateAt(s, at);
  const points = curve(s);
  const rates = points.map((p) => p.y);
  const maxRate = Math.max(...rates);
  const minRate = Math.min(...rates.filter((r) => r > 0));
  const spoken = (v: number): string => {
    const { mantissa, exponent } = scientific(v);
    return fig.plain('fig.lr.slider.valuetext', { mantissa, exponent });
  };
  const set = (patch: Partial<Schedule>): void => {
    const next = { ...s, ...patch };
    // Keep the run longer than its warm-up by two steps, as the Java record requires.
    if (next.steps <= next.warmup + 1) next.steps = next.warmup + 2;
    setS(next);
  };

  const rateText = (v: number): string => rateDisplay(v, fig.language);
  // A rate is one word: Firefox and Chromium would break after the superscript minus (10⁻ | ⁴) in a narrow cell.
  const rateNode = (v: number): React.JSX.Element => <span className="ml-fig-nowrap">{rateText(v)}</span>;
  // The log axis labels each power of ten (10⁻⁵); the linear axis writes the power that all its ticks share once, next to its label, and
  // plain mantissas at the ticks (0, 1, 2 …), so that a tick label stays as narrow as before at 320 px.
  const top = niceTop(maxRate);
  const shared = axisExponent(top);
  const yAxis = log
    ? ({ scale: 'log', domain: [10 ** Math.floor(Math.log10(minRate)), 10 ** Math.ceil(Math.log10(maxRate))], format: rateText } as const)
    : ({ scale: 'linear', domain: [0, top], format: (v: number) => fig.fmt.n(Number((v / 10 ** shared).toPrecision(6))) } as const);
  const yLabel = log ? fig.plain('fig.lr.axis.y') : `${fig.plain('fig.lr.axis.y')} (× ${sciPlain({ mantissa: 1, exponent: shared }, fig.language)})`;

  return (
    <Figure
      id="fig-lr"
      labelWord={`${fig.kit('kit.fig.label')} 4.1`}
      title={fig.plain('fig.lr.title')}
      panel={
        <Manipulate>
          <Presets
            label={fig.kit('kit.presets.label')}
            pressed={pressed === null ? null : pressed}
            onPick={(id) => {
              const p = presets[id as PresetId];
              setS(p);
              setMark(Math.round(p.steps / 2));
            }}
            presets={[
              { id: 'gen0', label: fig.t('fig.lr.preset.gen0') },
              { id: 'constant', label: fig.t('fig.lr.preset.constant') },
              { id: 'sweep1', label: fig.t('fig.lr.preset.sweep1') },
            ]}
          />
          <Slider
            label={fig.t('fig.lr.slider.base')}
            min={EXP_MIN}
            max={EXP_MAX}
            step={0.001}
            value={exponentOf(s.base)}
            onChange={(e) => set({ base: 10 ** e })}
            out={rateNode(s.base)}
            valueText={spoken(s.base)}
          />
          <Slider
            label={fig.t('fig.lr.slider.final')}
            min={EXP_MIN}
            max={EXP_MAX}
            step={0.001}
            value={exponentOf(s.final)}
            onChange={(e) => set({ final: 10 ** e })}
            out={rateNode(s.final)}
            valueText={spoken(s.final)}
          />
          <Slider label={fig.t('fig.lr.slider.warmup')} min={1} max={1000} value={s.warmup} onChange={(v) => set({ warmup: v })} out={fig.fmt.n(s.warmup)} />
          <Slider label={fig.t('fig.lr.slider.steps')} min={500} max={20000} step={100} value={s.steps} onChange={(v) => set({ steps: v })} out={fig.fmt.n(s.steps)} />
          <Slider label={fig.t('fig.lr.slider.step')} min={0} max={last} value={at} onChange={setMark} out={fig.fmt.n(at)} />
          <Checkbox label={fig.t('fig.lr.log')} checked={log} onChange={setLog} />
        </Manipulate>
      }
      status={{ text: fig.plain('fig.lr.readout', { step: fig.fmt.n(at), rate: rateText(rate) }) }}
      caption={<Markup text={fig.t('fig.lr.caption')} />}
      foot={<FootChips fig={fig} sources={['nn/src/main/java/sternhalma/nn/LearningRateSchedule.java']} data={['lr-presets.json']} />}
    >
      <div ref={ref}>
        <Plot
          title={fig.plain('fig.lr.title')}
          desc={fig.plain('fig.lr.desc')}
          layout={compact ? 'compact' : 'wide'}
          height={compact ? 160 : 280}
          x={{ scale: 'linear', domain: [0, last], label: fig.plain('fig.lr.axis.x'), format: (v) => fig.fmt.n(v) }}
          y={{ ...yAxis, label: yLabel }}
        >
          <PlotLine points={points} />
          <PlotCursor x={at} y={rate} />
        </Plot>
        <Legend>
          <LegendItem mark={<path d="M2 8H30" className="ml-fig-plot__line ml-fig-plot__line--1" />}>{fig.t('fig.lr.series')}</LegendItem>
          <LegendItem mark={<path d="M16 0V16" className="ml-fig-plot__cursor" />}>{fig.kit('kit.legend.cursor')}</LegendItem>
        </Legend>
        <Readout cells={[{ label: fig.t('fig.lr.slider.step'), value: fig.fmt.n(at) }, { label: fig.t('fig.lr.axis.y'), value: rateNode(rate) }]} />
      </div>
    </Figure>
  );
}

/** A round upper bound for a linear axis: 1, 2 or 5 times a power of ten above 1.05 times the value. */
export function niceTop(value: number): number {
  const v = value * 1.05;
  const pow = 10 ** Math.floor(Math.log10(v));
  const f = v / pow;
  return (f <= 1 ? 1 : f <= 2 ? 2 : f <= 5 ? 5 : 10) * pow;
}
