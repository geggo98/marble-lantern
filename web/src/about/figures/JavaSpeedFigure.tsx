// Figure J1 of topic 6 (java): how fast one forward pass of the SMALL network runs with each Java technique, on a log axis.
// Dots with leaders (bars would lie on a log axis), a whisker for the TeaVM range, one switch "Java only / Java and C++". The rows come from
// data/java-speed.json; each row below the plot names its source line. Below 640 px the plot has no value labels (they would collide);
// the list under the plot carries every value. Only the three Mac rows come from one run, and the caption says so.
import { useRef, useState } from 'react';
import { Figure, Manipulate, PLOT_BREAKPOINT_PX, Plot, PlotDots, PlotRef, Segmented, useMeasuredWidth } from '../kit';
import type { Dot } from '../kit';
import { Markup } from '../kit/markup';
import { SourceChip } from '../kit/SourceChip';
import { FigFoot, figLabel, useFigText } from './common';
import type { Catalog } from './common';
import { SPEED_DOMAIN, SPEED_ROWS, SPEED_TICKS, groupDivider, valueText, visibleRows } from './java-speed-model';
import type { SpeedFilter, SpeedRow } from './java-speed-model';
import './java-speed.css';

export interface JavaSpeedFigureProps {
  catalog: Catalog;
  number: readonly [number, number];
  /** Rows to draw; the data file by default (a test passes its own). */
  rows?: readonly SpeedRow[];
  /** Initial state of the switch. */
  initial?: SpeedFilter;
}

/** The mark of a row in the list: the same shape as its dot (filled circle for Java, hollow square for C++). */
function Mark({ kind }: { kind: SpeedRow['kind'] }): React.JSX.Element {
  return (
    <svg className="ml-speed__mark" viewBox="0 0 12 12" width="12" height="12" aria-hidden="true" focusable="false">
      {kind === 'java' ? <circle className="ml-fig-plot__pt--1" cx="6" cy="6" r="4.5" /> : <rect className="ml-fig-plot__pt--2" x="2" y="2" width="8" height="8" />}
    </svg>
  );
}

export function JavaSpeedFigure({ catalog, number, rows = SPEED_ROWS, initial = 'java' }: JavaSpeedFigureProps): React.JSX.Element {
  const f = useFigText(catalog);
  const { t, kit, n } = f;
  const [filter, setFilter] = useState<SpeedFilter>(initial);
  const holder = useRef<HTMLDivElement>(null);
  const width = useMeasuredWidth(holder, '.ml-fig');
  const compact = width > 0 && width < PLOT_BREAKPOINT_PX;

  const shown = visibleRows(filter, rows);
  const divider = groupDivider(shown);
  const dot = (kind: SpeedRow['kind']): Dot[] =>
    shown.flatMap((r, i) =>
      r.kind !== kind
        ? []
        : [
            {
              x: i,
              y: r.value,
              ...(r.lo !== undefined && r.hi !== undefined ? { lo: r.lo, hi: r.hi } : {}),
              ...(compact ? {} : { valueLabel: valueText(r, n), labelSide: 'above' as const }),
            },
          ],
    );
  const groups = (['mac-run', 'other'] as const).map((g) => ({ g, rows: shown.filter((r) => r.group === g) })).filter((x) => x.rows.length > 0);

  return (
    <Figure
      id="fig-javaspeed"
      labelWord={figLabel(kit, number[0], number[1])}
      title={t('fig.javaspeed.title')}
      panel={
        <Manipulate>
          <Segmented<SpeedFilter>
            label={t('fig.javaspeed.switch.label')}
            options={[
              { value: 'java', label: t('fig.javaspeed.switch.java') },
              { value: 'all', label: t('fig.javaspeed.switch.all') },
            ]}
            value={filter}
            onChange={setFilter}
          />
        </Manipulate>
      }
      caption={<Markup text={t('fig.javaspeed.caption')} />}
      foot={<FigFoot kit={kit} paths={['docs/tornado.md', 'docs/cloud.md', 'docs/cpp-comparison.md', 'docs/web-ui.md']} data="java-speed.json" />}
    >
      <div ref={holder} data-filter={filter}>
        <Plot
          title={t('fig.javaspeed.title')}
          desc={f.plain('fig.javaspeed.desc')}
          x={{ scale: 'category', categories: shown.map((_, i) => String(i + 1)) }}
          y={{ scale: 'log', domain: SPEED_DOMAIN, ticks: SPEED_TICKS, label: t('fig.javaspeed.axis'), minor: false }}
          margin={{ top: 34 }}
        >
          {divider !== undefined && <PlotRef x={divider} />}
          <PlotDots series={1} leaders dots={dot('java')} />
          <PlotDots series={2} leaders dots={dot('cpp')} />
        </Plot>
        <div className="ml-speed">
          {groups.map(({ g, rows: list }) => (
            <section key={g} className="ml-speed__group" data-group={g} aria-labelledby={`fig-javaspeed-${g}`}>
              <h4 id={`fig-javaspeed-${g}`} className="ml-speed__head">
                {t(g === 'mac-run' ? 'fig.javaspeed.group.mac' : 'fig.javaspeed.group.other')}
              </h4>
              <ol className="ml-speed__list" start={shown.indexOf(list[0]!) + 1}>
                {list.map((r) => (
                  <li key={r.id} data-row={r.id}>
                    <Mark kind={r.kind} />
                    <span className="ml-speed__label">{t(`fig.javaspeed.row.${r.id}`)}</span>{' '}
                    <b className="ml-speed__value" aria-label={t('fig.javaspeed.value', { value: valueText(r, n) })}>
                      {valueText(r, n)}
                    </b>{' '}
                    <SourceChip path={r.source.doc} lines={[r.source.line, r.source.line]} />
                  </li>
                ))}
              </ol>
            </section>
          ))}
        </div>
      </div>
    </Figure>
  );
}
