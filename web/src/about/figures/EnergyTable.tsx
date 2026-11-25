// Fig. 4.4: the table of the eight rows (the text equivalent of the plot, with the buttons that select a row) and the detail panel of the selected row.
// The table is a real table with a caption, column headers, row headers and one body per group. Its first cell holds a button with aria-pressed.
// The detail panel holds one variant per row in one grid cell, so that its height is that of the tallest and no selection moves the layout.
import type { FigText } from './shared';
import type { EnergyFmt } from './energy-fmt';
import { frontStatus, groupRows, matchOf, parentRun, rowCode } from './energy-model';
import type { Basis, EnergyData, EnergyRow, View } from './energy-model';

export interface TableProps {
  data: EnergyData;
  basis: Basis;
  selected: string;
  onSelect: (id: string) => void;
  fig: FigText;
  f: EnergyFmt;
  /** The id that `aria-details` of the plot points to. */
  id: string;
}

/** The front column in words for both bases, so that the table does not change with the switch: the status at equal simulations and at equal time. */
export function frontWords(row: EnergyRow, data: EnergyData, fig: FigText): string {
  if (row.group !== 'run') return '';
  const sims = frontStatus(row, 'sims', data);
  const time = frontStatus(row, 'time', data);
  if (sims === 'front' && time === 'front') return fig.t('fig.energy.front.yes');
  if (sims === 'front' && time === 'unknown') return fig.t('fig.energy.front.unknown');
  if (sims === 'front' && time === 'dominated') return fig.t('fig.energy.front.simsOnly');
  // a row that is dominated at equal simulations has no sentence in the catalog; the committed data has none
  return '–';
}

function StrengthText({ row, data, f, fig }: { row: EnergyRow; data: EnergyData; f: EnergyFmt; fig: FigText }): React.JSX.Element {
  if (row.strength) return <>{f.elo(row.strength.elo, row.strength.ci)}</>;
  const parent = parentRun(row, data);
  if (parent) return <>{fig.t('fig.energy.strength.part', { code: rowCode(parent) })}</>;
  return <>{fig.t('fig.energy.strength.none')}</>;
}

export function EnergyTable({ data, basis, selected, onSelect, fig, f, id }: TableProps): React.JSX.Element {
  const { t } = fig;
  const factor = (row: EnergyRow): { time: string; energy: string } | undefined => {
    if (row.group !== 'steps' || row.trainer !== 'cpu') return undefined;
    const x = data.factors.find((g) => g.net === row.net);
    return x ? { time: t('fig.energy.factor.time', { x: fig.fmt.fixed(x.time, 0) }), energy: t('fig.energy.factor.energy', { x: fig.fmt.fixed(x.energy, 0) }) } : undefined;
  };
  const group = (key: 'run' | 'steps'): React.JSX.Element => (
    <tbody key={key} data-group={key}>
      <tr>
        <th scope="rowgroup" colSpan={7} className="ml-fig-energy__group">
          {t(`fig.energy.table.group.${key}`)}
        </th>
      </tr>
      {groupRows(data, key).map((row) => {
        const fx = factor(row);
        const status = row.group === 'run' ? frontStatus(row, basis, data) : undefined;
        const front = frontWords(row, data, fig);
        const unchecked = row.checked === false;
        return (
          <tr key={row.id} data-row={row.id} data-front={status} data-selected={row.id === selected ? '' : undefined}>
            <th scope="row" className="ml-fig-energy__net">
              <button type="button" className="ml-fig-energy__pick" aria-pressed={row.id === selected} onClick={() => onSelect(row.id)}>
                <span className="ml-fig-energy__code">{f.code(row)}</span>
                <span> · {f.name(row)}</span>
              </button>
              {row.shipped && <span className="ml-tag ml-fig-energy__tag">{t('fig.energy.tag.shipped')}</span>}
            </th>
            <td className="ml-fig-energy__trainer" data-label={t('fig.energy.col.trainer')}>
              {f.trainer(row)}
            </td>
            <td className="ml-fig-energy__size" data-label={t('fig.energy.col.size')}>
              {f.size(row.net, data)}
            </td>
            <td className="ml-fig-energy__time" data-label={t('fig.energy.col.time')}>
              <span>{f.hours(row)}</span>
              {row.status === 'estimate' && <span className="ml-visually-hidden"> {f.estimateSr(row)}</span>}
              {fx && <span className="ml-fig-energy__factor-line">{fx.time}</span>}
            </td>
            <td className="ml-fig-energy__energy" data-label={t('fig.energy.col.energy')}>
              <span>
                {f.kwh(row)} {f.range(row)}
              </span>
              {unchecked && <sup className="ml-fig-energy__fn" aria-hidden="true"> †</sup>}
              {row.status === 'estimate' && <span className="ml-visually-hidden"> {f.estimateSr(row)}</span>}
              {fx && <span className="ml-fig-energy__factor-line">{fx.energy}</span>}
            </td>
            <td className="ml-fig-energy__strength" data-label={t('fig.energy.col.strength')}>
              <StrengthText row={row} data={data} f={f} fig={fig} />
            </td>
            <td className="ml-fig-energy__front-cell" data-label={t('fig.energy.col.front')}>
              {row.group === 'run' ? front : <span className="ml-visually-hidden">{t('fig.energy.front.na')}</span>}
            </td>
          </tr>
        );
      })}
    </tbody>
  );
  return (
    <div className="ml-fig-energy__tablewrap">
      <table className="ml-table ml-fig-energy__table" id={id} data-part="table">
        <caption>{t('fig.energy.table.caption')}</caption>
        <thead>
          <tr>
            <th scope="col">{t('fig.energy.col.net')}</th>
            <th scope="col">{t('fig.energy.col.trainer')}</th>
            <th scope="col">{t('fig.energy.col.size')}</th>
            <th scope="col">{t('fig.energy.col.time')}</th>
            <th scope="col">{t('fig.energy.col.energy')}</th>
            <th scope="col">{t('fig.energy.col.strength')}</th>
            <th scope="col">{t('fig.energy.col.front')}</th>
          </tr>
        </thead>
        {group('run')}
        {group('steps')}
      </table>
      <p className="ml-fig-energy__note">
        <span aria-hidden="true">† </span>
        {t('fig.energy.note.unchecked')}
      </p>
    </div>
  );
}

export interface DetailProps {
  data: EnergyData;
  view: View;
  selected: string;
  fig: FigText;
  f: EnergyFmt;
  /** Prefix for the heading ids. */
  uid: string;
}

export function EnergyDetail({ data, view, selected, fig, f, uid }: DetailProps): React.JSX.Element {
  const { t } = fig;
  return (
    <section className="ml-fig-energy__detail" aria-labelledby={`${uid}-${selected}`} data-part="detail">
      <div className="ml-fig-energy__stack">
        {data.rows.map((row) => {
          const on = row.id === selected;
          const days = f.days(row);
          const parent = parentRun(row, data);
          return (
            <div key={row.id} className="ml-fig-energy__variant" data-row={row.id} {...(on ? {} : { 'data-off': '', 'aria-hidden': 'true' as const })}>
              <h4 className="ml-fig-energy__detail-title" id={`${uid}-${row.id}`}>
                {f.code(row)} · {f.name(row)}
              </h4>
              <p>{t(`fig.energy.detail.scope.${row.group}`)}</p>
              <dl className="ml-fig-energy__dl">
                <div>
                  <dt>{t('fig.energy.detail.time')}</dt>
                  <dd>
                    {f.hours(row)}
                    {days && ` (${days})`}
                  </dd>
                </div>
                <div>
                  <dt>{t('fig.energy.detail.energy')}</dt>
                  <dd>
                    {f.kwh(row)} {f.range(row)}
                  </dd>
                </div>
                {row.aboveIdleKwh !== undefined && (
                  <div>
                    <dt>{t('fig.energy.detail.idle')}</dt>
                    <dd>{f.aboveIdle(row)}</dd>
                  </div>
                )}
                <div>
                  <dt>{t('fig.energy.detail.power')}</dt>
                  <dd>{f.power(row)}</dd>
                </div>
                <div>
                  <dt>{t('fig.energy.detail.net')}</dt>
                  <dd>
                    {t(`fig.energy.net.${row.net}`)}, {f.size(row.net, data)}
                  </dd>
                </div>
                <div>
                  <dt>{t('fig.energy.detail.strength')}</dt>
                  <dd>
                    <StrengthText row={row} data={data} f={f} fig={fig} />
                  </dd>
                  {(['sims', 'time'] as const).map((basis) => {
                    const m = matchOf(row, basis, data);
                    return m ? <dd key={basis}>{t(`fig.energy.detail.match.${basis}`, { v: f.elo(m.elo, m.ci) })}</dd> : null;
                  })}
                </div>
                {row.group === 'run' && (
                  <div>
                    <dt>{t('fig.energy.col.front')}</dt>
                    <dd>{frontWords(row, data, fig)}</dd>
                  </div>
                )}
              </dl>
              {parent && (
                <p className="ml-fig-energy__hidden" {...(view === 'both' ? { 'data-off': '', 'aria-hidden': 'true' as const } : {})}>
                  {t('fig.energy.detail.hidden', { code: rowCode(parent) })}
                </p>
              )}
            </div>
          );
        })}
      </div>
    </section>
  );
}
