// V7: node budget and completed search depth for the mid-game position, for the browser engine and for the same evaluation without
// enhancements. A log axis for the nodes, a slider over the five strength levels of the app.
import { useRef, useState } from 'react';
import { Figure, Legend, LegendItem, Manipulate, PLOT_BREAKPOINT_PX, Plot, PlotCursor, PlotDots, PlotLine, Slider, useMeasuredWidth } from '../kit';
import { Markup } from '../kit/markup';
import raw from '../data/ab-budget-mid.json';
import { FigFoot, figLabel, useFigText } from './common';
import type { Catalog } from './common';

interface Row {
  nodes: number;
  depth: number;
  score: number;
  move: { from: number; to: number; text: string };
}

const BROWSER = raw.browser as Row[];
const PLAIN = raw.plain as Row[];
const LEVELS = raw.meta.levels;

/** The x axis spans the five budgets with a margin of a third of a decade on both sides. */
export const BUDGET_DOMAIN: readonly [number, number] = [Math.min(...LEVELS) * 0.7, Math.max(...LEVELS) * 1.4];

export interface BudgetFigureProps {
  catalog: Catalog;
  number: readonly [number, number];
}

export function BudgetFigure({ catalog, number }: BudgetFigureProps): React.JSX.Element {
  const f = useFigText(catalog);
  const { t, kit, n } = f;
  const [level, setLevel] = useState(3);
  const holder = useRef<HTMLDivElement>(null);
  const width = useMeasuredWidth(holder, '.ml-fig');
  const compact = width > 0 && width < PLOT_BREAKPOINT_PX;
  const i = level - 1;
  const browser = BROWSER[i]!;
  const plain = PLAIN[i]!;
  const maxDepth = Math.max(...BROWSER.map((r) => r.depth), ...PLAIN.map((r) => r.depth));
  const yTop = Math.ceil((maxDepth + 1) / 2) * 2 + 1;
  const dots = (rows: Row[], mark: boolean) =>
    rows.map((r, k) => ({ x: r.nodes, y: r.depth, ...(mark && k === i ? { valueLabel: n(r.depth), best: true } : {}) }));

  return (
    <Figure
      id="fig-budget"
      labelWord={figLabel(kit, number[0], number[1])}
      title={t('fig.budget.title')}
      panel={
        <Manipulate>
          <Slider
            label={t('fig.budget.slider')}
            min={1}
            max={LEVELS.length}
            value={level}
            onChange={setLevel}
            valueText={t('fig.budget.slider.valuetext', { nodes: n(browser.nodes), depth: browser.depth })}
            out={t(`fig.budget.level.${level}`)}
          />
        </Manipulate>
      }
      caption={<Markup text={t('fig.budget.caption')} />}
      foot={<FigFoot kit={kit} paths={['search/src/main/java/sternhalma/search/Strategies.java', 'web/src/ui/Panels.tsx']} data="ab-budget-mid.json" />}
    >
      <div ref={holder}>
        <Plot
          title={t('fig.budget.title')}
          desc={f.plain('fig.budget.desc')}
          x={{ scale: 'log', domain: BUDGET_DOMAIN, ticks: LEVELS, label: t('fig.budget.axis.x'), minor: false }}
          y={{ scale: 'linear', domain: [0, yTop], ticks: Array.from({ length: Math.floor(yTop / 2) + 1 }, (_, k) => k * 2), label: t('fig.budget.axis.y') }}
        >
          <PlotLine series={2} points={PLAIN.map((r) => ({ x: r.nodes, y: r.depth }))} />
          <PlotLine series={1} points={BROWSER.map((r) => ({ x: r.nodes, y: r.depth }))} />
          <PlotDots series={2} dots={dots(PLAIN, false)} />
          <PlotDots series={1} dots={dots(BROWSER, true)} />
          <PlotCursor x={browser.nodes} y={browser.depth} />
        </Plot>
      </div>
      <Legend>
        <LegendItem
          mark={
            <>
              <line className="ml-fig-plot__line ml-fig-plot__line--1" x1={1} x2={31} y1={8} y2={8} />
              <circle className="ml-fig-plot__pt--1" cx={16} cy={8} r={4.5} />
            </>
          }
        >
          {compact ? t('fig.budget.series.browser.short') : t('fig.budget.series.browser')}
        </LegendItem>
        <LegendItem
          mark={
            <>
              <line className="ml-fig-plot__line ml-fig-plot__line--2" x1={1} x2={31} y1={8} y2={8} />
              <rect className="ml-fig-plot__pt--2" x={12} y={4} width={8} height={8} />
            </>
          }
        >
          {t('fig.budget.series.plain')}
        </LegendItem>
        <LegendItem mark={<line className="ml-fig-plot__cursor" x1={16} x2={16} y1={0} y2={16} />}>{kit('kit.legend.cursor')}</LegendItem>
      </Legend>
      <p className="ml-fig-budget__readout" aria-live="polite">
        <span>{t('fig.budget.readout', { nodes: n(browser.nodes), depth: n(browser.depth), move: browser.move.text })}</span>
        <span>{t('fig.budget.readout.plain', { depth: n(plain.depth), move: plain.move.text })}</span>
      </p>
    </Figure>
  );
}
