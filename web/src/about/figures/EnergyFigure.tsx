// Fig. 4.4 (topic training, section energy): what the training of the 5-row networks cost in time, energy and strength. One plot with three views
// (strength against energy, strength against time, energy against time), a switch for how strength is compared (equal simulations or equal thinking
// time), a legend, a detail panel for the selected row and a table of the eight rows. 2D on purpose: energy is time times an almost constant power,
// so a third axis would add depth and no information (design spec of 2026-11-25, section 1). The pure part is energy-model.ts; the marks are in
// EnergyMarks.tsx, the table and the detail panel in EnergyTable.tsx. Data: data/energy.json.
// Stable layout: no state changes the height of any part. The plot has one height in all views, the narration and the detail panel keep the size of
// their longest variant, the reason line is always there, the legend holds the entries of all views, and the table always has eight rows.
import { useId, useLayoutEffect, useState } from 'react';
import { Figure, Manipulate, Plot, Segmented, usePlot } from '../kit';
import { Markup } from '../kit/markup';
import { trainingCatalog } from './catalogs';
import { EnergyMarks, wrapLabel } from './EnergyMarks';
import { EnergyDetail, EnergyTable } from './EnergyTable';
import { makeEnergyFmt } from './energy-fmt';
import { BASES, DEFAULT_SELECTED, ENERGY_DATA, VIEWS, viewSpec } from './energy-model';
import type { Basis, EnergyData, View } from './energy-model';
import { FootChips, useCompact, useFigText } from './shared';
import './energy.css';

export interface EnergyFigureProps {
  /** Rows to draw; the data file by default (a test passes its own). */
  data?: EnergyData;
  /** The state at the start. */
  initial?: { view?: View; basis?: Basis; selected?: string };
}

/** Fig. 4.4: its number is fixed here, as in PooledFigure ("4.3"). */
const FIGURE_NUMBER = '4.4';

/** One legend entry; `off` dims it and hides it from screen readers when the current view does not draw it. */
function Entry({ mark, children, off }: { mark: React.ReactNode; children: React.ReactNode; off: boolean }): React.JSX.Element {
  return (
    <span className="ml-fig-legend__item" {...(off ? { 'data-off': '', 'aria-hidden': 'true' as const } : {})}>
      <svg viewBox="0 0 32 16" aria-hidden="true" focusable="false">
        {mark}
      </svg>
      {children}
    </span>
  );
}

export function EnergyFigure({ data = ENERGY_DATA, initial }: EnergyFigureProps): React.JSX.Element {
  const fig = useFigText(trainingCatalog);
  const { t, plain } = fig;
  const f = makeEnergyFmt(fig);
  const { ref, compact } = useCompact();
  const uid = useId();
  const [view, setView] = useState<View>(initial?.view ?? 'energy');
  const [basis, setBasis] = useState<Basis>(initial?.basis ?? 'sims');
  const [selected, setSelected] = useState<string>(initial?.selected ?? DEFAULT_SELECTED);

  const tableId = `${uid}-table`;
  const reasonId = `${uid}-reason`;
  const both = view === 'both';
  const spec = viewSpec(view, compact);
  const title = plain('fig.energy.title');

  // hooks of the tests and of the browser specs sit on the <figure>, which the kit renders without extra props
  useLayoutEffect(() => {
    const figure = ref.current?.closest('figure');
    if (!figure) return;
    figure.dataset.view = view;
    figure.dataset.basis = basis;
    figure.dataset.selected = selected;
    ref.current?.querySelector('svg.ml-fig-plot')?.setAttribute('aria-details', tableId);
  });

  const status = (v: View, b: Basis): string => plain(v === 'both' ? 'fig.energy.status.both' : `fig.energy.status.${v}.${b}`);
  const allStatus = [
    ...(['energy', 'time'] as const).flatMap((v) => BASES.map((b) => ({ text: status(v, b) }))),
    { text: status('both', 'sims') },
  ];

  const yLines = wrapLabel(plain(spec.y.key === 'kwh' ? 'fig.energy.axis.energy' : 'fig.energy.axis.elo'), compact ? 34 : 200);
  // the margin holds the longest label (the Elo one) in all views, so that no view moves the frame: two lines at least
  const topMargin = 14 + 15 * Math.max(2, wrapLabel(plain('fig.energy.axis.elo'), compact ? 34 : 200).length);

  const panel = (
    <Manipulate>
      <Segmented<View>
        label={t('fig.energy.view.label')}
        options={VIEWS.map((v) => ({ value: v, label: t(`fig.energy.view.${v}`) }))}
        value={view}
        onChange={setView}
      />
      <Segmented<Basis>
        label={t('fig.energy.basis.label')}
        options={BASES.map((b) => ({ value: b, label: t(`fig.energy.basis.${b}`), disabled: both, describedBy: reasonId }))}
        value={basis}
        onChange={setBasis}
      />
      <p className="ml-fig-energy__reason" id={reasonId} {...(both ? {} : { 'aria-hidden': 'true' as const })} data-off={both ? undefined : ''}>
        {t('fig.energy.basis.off')}
      </p>
    </Manipulate>
  );

  const legend = (
    <div className="ml-fig-legend ml-fig-energy__legend" data-part="legend">
      <Entry off={false} mark={<circle className="ml-fig-energy__mark" data-fill="solid" cx="16" cy="8" r="4.5" />}>
        {t('fig.energy.legend.small')}
      </Entry>
      <Entry off={false} mark={<rect className="ml-fig-energy__mark" data-fill="solid" x="11.5" y="3.5" width="9" height="9" />}>
        {t('fig.energy.legend.large')}
      </Entry>
      <Entry off={!both} mark={<circle className="ml-fig-energy__mark" data-fill="hollow" cx="16" cy="8" r="4.5" />}>
        {t('fig.energy.legend.estimate')}
      </Entry>
      <Entry off={both} mark={<path className="ml-fig-plot__whisker" d="M16 1V15M11 1H21M11 15H21" />}>
        {t('fig.energy.legend.interval')}
      </Entry>
      <Entry off={both || basis !== 'sims'} mark={<polygon className="ml-fig-energy__tri ml-fig-energy__tri--up" points="16,2 22,13 10,13" />}>
        {t('fig.energy.legend.match.sims')}
      </Entry>
      <Entry off={both || basis !== 'time'} mark={<polygon className="ml-fig-energy__tri ml-fig-energy__tri--down" points="16,14 22,3 10,3" />}>
        {t('fig.energy.legend.match.time')}
      </Entry>
      <Entry off={both} mark={<line className="ml-fig-energy__front" x1="1" x2="31" y1="8" y2="8" />}>
        {t('fig.energy.legend.front')}
      </Entry>
      <Entry off={both} mark={<rect className="ml-fig-energy__swatch" x="2" y="2" width="28" height="12" />}>
        {t('fig.energy.legend.region')}
      </Entry>
      <Entry off={both} mark={<><line className="ml-fig-energy__bound" x1="16" x2="16" y1="1" y2="15" /><path className="ml-fig-energy__arrow" d="M16 1L23 4.5L16 8Z" /></>}>
        {t('fig.energy.legend.bound')}
      </Entry>
      <Entry off={!both} mark={<rect className="ml-fig-energy__swatch" x="2" y="2" width="28" height="12" />}>
        {t('fig.energy.legend.band')}
      </Entry>
    </div>
  );

  return (
    <Figure
      id="fig-energy"
      labelWord={`${fig.kit('kit.fig.label')} ${FIGURE_NUMBER}`}
      title={title}
      panel={panel}
      status={{ text: status(view, basis), all: allStatus }}
      caption={<Markup text={t('fig.energy.caption')} />}
      foot={<FootChips fig={fig} sources={['docs/training.md', 'scripts/energy/README.md']} data={['energy.json']} />}
    >
      <div className="ml-fig-energy" ref={ref} data-view={view} data-basis={basis}>
        <div className="ml-fig-energy__plot" data-part="plot">
          <Plot
            layout={compact ? 'compact' : 'wide'}
            height={340}
            margin={{ top: topMargin, ...(compact ? { left: 48 } : {}) }}
            title={title}
            desc={plain(`fig.energy.desc.${view}`)}
            x={{ scale: 'log', domain: spec.x.domain, ticks: spec.x.ticks, format: f.tick, label: t(spec.x.key === 'kwh' ? 'fig.energy.axis.energy' : 'fig.energy.axis.time') }}
            y={{ scale: spec.y.scale, domain: spec.y.domain, ticks: spec.y.ticks, format: f.tick }}
          >
            <YLabel lines={yLines} />
            <EnergyMarks data={data} view={view} basis={basis} selected={selected} onSelect={setSelected} fig={fig} />
          </Plot>
        </div>
        <div className="ml-fig-energy__info">
          {legend}
          <EnergyDetail data={data} basis={basis} view={view} selected={selected} fig={fig} f={f} uid={`${uid}-d`} />
        </div>
        <EnergyTable data={data} basis={basis} selected={selected} onSelect={setSelected} fig={fig} f={f} id={tableId} />
      </div>
    </Figure>
  );
}

/** The label of the y axis, horizontal above the frame (design notes: the y label never stands rotated), in one or two lines. */
function YLabel({ lines }: { lines: readonly string[] }): React.JSX.Element {
  const { area } = usePlot();
  return (
    <>
      {lines.map((line, i) => (
        <text key={i} className="ml-fig-plot__axislabel" x={area.x0} y={area.y0 - 10 - (lines.length - 1 - i) * 15} textAnchor="start" data-part="ylabel">
          {line}
        </text>
      ))}
    </>
  );
}
