// V11: the self-play loop of loop run 3 (Fig. 4.2): a cycle of six stages and the gate score of each of the 14 generations.
// Layout notes: web/design/about/about-notes.md, "V11". Data: loop3.json.
import { useId, useRef, useState } from 'react';
import { Chip, Checkbox, Figure, FigureDetail, Legend, LegendItem, Manipulate, Plot, PlotCursor, PlotDots, PlotRef, Readout, Slider } from '../kit';
import { Markup } from '../kit/markup';
import { sourceUrl } from '../kit/SourceChip';
import { trainingCatalog } from './catalogs';
import { STAGES, STAGE_SOURCE, gates, ladder, oldRuleExtras } from './loop';
import type { StageId } from './loop';
import { FootChips, useCompact, useFigText } from './shared';

export function LoopFigure(): React.JSX.Element {
  const fig = useFigText(trainingCatalog);
  const { ref, compact } = useCompact();
  const list = gates();
  const extras = oldRuleExtras(list);
  const rungs = ladder();
  const [gen, setGen] = useState(list.length);
  const [oldRule, setOldRule] = useState(false);
  const [open, setOpen] = useState<StageId | null>(null);
  const buttons = useRef<Partial<Record<StageId, HTMLButtonElement | null>>>({});
  const detailId = useId();

  const g = list[gen - 1] ?? (list[0] as (typeof list)[number]);
  const decision = fig.t(g.promoted ? 'fig.loop.promoted' : 'fig.loop.rejected');
  const score = fig.fmt.fixed(g.score, 3);
  const returnEdge = (g.promoted ? 'data' : 'control') as 'data' | 'control';

  const close = (): void => {
    const was = open;
    setOpen(null);
    queueMicrotask(() => was && buttons.current[was]?.focus());
  };

  const promoted = list.filter((x) => x.promoted);
  const rejected = list.filter((x) => !x.promoted);
  const hollowPlain = rejected.filter((x) => !oldRule || !extras.includes(x.gen));
  const ticks = list.filter((x) => x.gen % 2 === 0).map((x) => x.gen);
  const xDomain: [number, number] = [0.5, list.length + 0.5];

  return (
    <Figure
      id="fig-loop"
      labelWord={`${fig.kit('kit.fig.label')} 4.2`}
      title={fig.plain('fig.loop.title')}
      panel={
        <Manipulate>
          <Slider
            label={fig.t('fig.loop.slider.generation')}
            min={1}
            max={list.length}
            value={gen}
            onChange={setGen}
            out={`${gen} / ${list.length}`}
            valueText={fig.plain('fig.loop.slider.valuetext', { gen, score, decision })}
          />
          <div className="ml-fig-ctl ml-fig-ctl--wide">
            <span />
            <div className="ml-fig-ctl__input">
              <Checkbox label={<Markup text={fig.t('fig.loop.oldrule.toggle')} />} checked={oldRule} onChange={setOldRule} />
            </div>
          </div>
        </Manipulate>
      }
      status={{ text: `${fig.plain('fig.loop.slider.valuetext', { gen, score, decision })}` }}
      detail={
        open && (
          <FigureDetail
            id={detailId}
            title={fig.t(`fig.loop.stage.${open}.label`)}
            closeLabel={fig.kit('kit.detail.close')}
            onClose={close}
            chips={
              <Chip prefix={fig.kit('kit.chip.src')} href={sourceUrl(STAGE_SOURCE[open])}>
                {STAGE_SOURCE[open]}
              </Chip>
            }
          >
            <p>
              <Markup text={fig.t(`fig.loop.stage.${open}.detail`)} />
            </p>
          </FigureDetail>
        )
      }
      caption={<Markup text={fig.t('fig.loop.caption')} />}
      foot={<FootChips fig={fig} sources={[...new Set(Object.values(STAGE_SOURCE))]} data={['loop3.json']} />}
    >
      <div ref={ref} className="ml-fig-loop">
        <div className="ml-fig-loop__cycle">
          <ol className="ml-fig-cycle" aria-label={fig.t('fig.loop.cycle.label')} data-return={returnEdge}>
            {STAGES.map((id, i) => (
              <li key={id} className="ml-fig-cycle__item" data-pos={i + 1}>
                <button
                  type="button"
                  ref={(el) => {
                    buttons.current[id] = el;
                  }}
                  className="ml-fig-cycle__stage"
                  aria-expanded={open === id}
                  aria-controls={detailId}
                  data-state={id === 'decision' || id === 'gate' ? 'active' : undefined}
                  onClick={() => setOpen(open === id ? null : id)}
                >
                  <span className="ml-fig-cycle__num" aria-hidden="true">{i + 1}</span>
                  <span>{fig.t(`fig.loop.stage.${id}.label`)}</span>
                  {id === 'gate' && <span className="ml-fig-cycle__sub">{fig.t('fig.loop.gate.score', { score })}</span>}
                  {id === 'decision' && <span className="ml-fig-cycle__sub">{decision}</span>}
                </button>
              </li>
            ))}
          </ol>
          <Readout
            cells={[
              { label: fig.t('fig.loop.slider.generation'), value: gen },
              { label: fig.plain('fig.loop.gate.score', { score: '' }).trim(), value: score },
            ]}
          />
          <p className="ml-fig-note"><Markup text={fig.t('fig.loop.gate.rule')} /></p>
          {oldRule && (
            <p className="ml-fig-note ml-fig-note--strong">
              <Markup text={fig.t('fig.loop.oldrule.note', { gens: extras.join(', ') })} />
            </p>
          )}
        </div>
        <div className="ml-fig-loop__plots">
          <div>
          <h4 className="ml-fig-subtitle">{fig.t('fig.loop.timeline.title')}</h4>
          <Plot
            title={fig.plain('fig.loop.timeline.title')}
            desc={fig.plain('fig.loop.timeline.desc')}
            layout="compact"
            height={220}
            x={{ scale: 'linear', domain: xDomain, ticks, label: fig.plain('fig.loop.timeline.axis.x'), format: (v) => fig.fmt.n(v) }}
            y={{ scale: 'linear', domain: [0.44, 0.64], ticks: [0.44, 0.48, 0.52, 0.56, 0.6, 0.64], label: fig.plain('fig.loop.timeline.axis.y'), format: (v) => fig.fmt.fixed(v, 2) }}
          >
            <PlotRef y={0.5} />
            <PlotCursor x={gen} y={g.score} />
            <PlotDots dots={promoted.map((x) => ({ x: x.gen, y: x.score }))} series={1} />
            <PlotDots dots={hollowPlain.map((x) => ({ x: x.gen, y: x.score }))} series={2} />
            {oldRule && <PlotDots dots={rejected.filter((x) => extras.includes(x.gen)).map((x) => ({ x: x.gen, y: x.score }))} series={3} />}
          </Plot>
          <Legend>
            <LegendItem mark={<circle cx={16} cy={8} r={4.5} className="ml-fig-plot__pt--1" />}>{fig.t('fig.loop.promoted')}</LegendItem>
            <LegendItem mark={<rect x={12} y={4} width={8} height={8} className="ml-fig-plot__pt--2" />}>{fig.t('fig.loop.rejected')}</LegendItem>
            <LegendItem mark={<path d="M2 8H30" className="ml-fig-plot__ref" />}>{fig.plain('fig.loop.gate.threshold')}</LegendItem>
            {oldRule && <LegendItem mark={<path d="M16 2L22 8L16 14L10 8Z" className="ml-fig-plot__pt--3" />}><Markup text={fig.t('fig.loop.oldrule.toggle')} /></LegendItem>}
          </Legend>
          </div>
          <div>
          <h4 className="ml-fig-subtitle">{fig.t('fig.loop.ladder.title')}</h4>
          <Plot
            title={fig.plain('fig.loop.ladder.title')}
            desc={fig.plain('fig.loop.ladder.desc')}
            layout="compact"
            height={200}
            x={{ scale: 'category', categories: rungs.map((r) => fig.fmt.n(r.gen)), label: fig.plain('fig.loop.timeline.axis.x') }}
            y={{ scale: 'linear', domain: [0, 500], ticks: [0, 100, 200, 300, 400, 500], label: fig.plain('fig.loop.ladder.axis') }}
          >
            <PlotDots
              dots={rungs.map((r, i) => ({ x: i, y: r.elo, lo: r.lo, hi: r.hi, valueLabel: fig.fmt.signed(r.elo), best: r.elo === Math.max(...rungs.map((q) => q.elo)) }))}
            />
          </Plot>
          </div>
        </div>
      </div>
    </Figure>
  );
}
