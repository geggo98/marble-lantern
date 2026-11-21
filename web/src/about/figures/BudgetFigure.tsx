// V7: node budget and completed search depth for the mid-game position, for the browser engine and for the same evaluation without
// enhancements. A log axis for the nodes, a slider over the five strength levels of the app. With a snapshot of the reader's game (live
// switch "My game") the browser-engine series is computed in the browser for that position; the series without enhancements has no live form.
import { useRef, useState } from 'react';
import { Figure, Legend, LegendItem, LiveSwitch, Manipulate, PLOT_BREAKPOINT_PX, Plot, PlotCursor, PlotDots, PlotLine, Slider, useMeasuredWidth } from '../kit';
import { Markup } from '../kit/markup';
import raw from '../data/ab-budget-mid.json';
import { FigFoot, figLabel, useFigText } from './common';
import type { Catalog } from './common';
import { liveBlockedReason } from '../live/classic';
import { useLive } from '../live/context';
import { runBudget } from '../live/budget';
import type { BudgetResult, BudgetRow } from '../live/budget';
import { liveStatusText, liveValue } from '../live/status';
import { useLiveRun } from '../live/useLiveRun';

type Row = BudgetRow;

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

  const { input } = useLive();
  const run = useLiveRun<BudgetResult, readonly BudgetRow[]>('budget', async (engines, signal, report) => {
    const snapshot = input!.snapshot; // start() does nothing without a snapshot
    const engine = await engines.classical(report.progress);
    report.step(0, LEVELS.length);
    return runBudget(engine, snapshot.history, snapshot.variant, LEVELS, signal, (rows) => report.step(rows.length, LEVELS.length, rows));
  });
  const mine = liveValue(run.state) === 'mine';
  const reason = liveBlockedReason(input, 'classical');
  const ply = input?.snapshot.ply ?? 0;
  const race = mine && run.state.kind === 'done' && run.state.value.race;
  const liveRows: readonly Row[] = !mine ? [] : run.state.kind === 'done' ? run.state.value.rows : run.state.kind === 'running' ? (run.state.partial ?? []) : [];

  const browserRows: readonly Row[] = mine ? liveRows : BROWSER;
  const plainRows: readonly Row[] = mine ? [] : PLAIN;
  const i = level - 1;
  const browser = browserRows[i];
  const plain = plainRows[i];
  const maxDepth = Math.max(...BROWSER.map((r) => r.depth), ...PLAIN.map((r) => r.depth), ...liveRows.map((r) => r.depth));
  const yTop = Math.ceil((maxDepth + 1) / 2) * 2 + 1;
  const dots = (rows: readonly Row[], marked: boolean) =>
    rows.map((r, k) => ({ x: r.nodes, y: r.depth, ...(marked && k === i ? { valueLabel: n(r.depth), best: true } : {}) }));
  const levelNodes = LEVELS[i]!;
  const description = mine
    ? f.plain('fig.budget.live.desc', { ply, levels: LEVELS.map((l) => n(l)).join(', ') })
    : f.plain('fig.budget.desc');

  return (
    <Figure
      id="fig-budget"
      labelWord={figLabel(kit, number[0], number[1])}
      title={t('fig.budget.title')}
      status={{ text: liveStatusText(kit, run.state) }}
      panel={
        <Manipulate>
          <LiveSwitch
            label={kit('kit.live.label')}
            exampleLabel={kit('kit.live.example')}
            mineLabel={kit('kit.live.mine', { ply: input ? ply : '–' })}
            value={mine ? 'mine' : 'example'}
            onChange={(v) => (v === 'mine' ? run.start() : run.reset())}
            reason={reason === null ? null : kit(`kit.live.reason.${reason}`)}
            busy={run.state.kind === 'starting' || run.state.kind === 'running'}
            stopLabel={kit('kit.live.stop')}
            onStop={run.stop}
            failed={run.state.kind === 'failed'}
            retryLabel={kit('kit.live.retry')}
            onRetry={run.start}
          />
          <Slider
            label={t('fig.budget.slider')}
            min={1}
            max={LEVELS.length}
            value={level}
            onChange={setLevel}
            valueText={browser ? t('fig.budget.slider.valuetext', { nodes: n(browser.nodes), depth: browser.depth }) : n(levelNodes)}
            out={t(`fig.budget.level.${level}`)}
          />
        </Manipulate>
      }
      caption={<Markup text={mine ? t('fig.budget.live.desc', { ply, levels: LEVELS.map((l) => n(l)).join(', ') }) : t('fig.budget.caption')} />}
      foot={
        <FigFoot
          kit={kit}
          paths={['search/src/main/java/sternhalma/search/Strategies.java', 'web/src/ui/Panels.tsx']}
          data="ab-budget-mid.json"
          origin={mine ? { kind: 'mine', ply } : { kind: 'example', ply: raw.meta.ply }}
        />
      }
    >
      <div ref={holder}>
        <Plot
          title={t('fig.budget.title')}
          desc={description}
          x={{ scale: 'log', domain: BUDGET_DOMAIN, ticks: LEVELS, label: t('fig.budget.axis.x'), minor: false }}
          y={{ scale: 'linear', domain: [0, yTop], ticks: Array.from({ length: Math.floor(yTop / 2) + 1 }, (_, k) => k * 2), label: t('fig.budget.axis.y') }}
        >
          {plainRows.length > 0 && <PlotLine series={2} points={plainRows.map((r) => ({ x: r.nodes, y: r.depth }))} />}
          {browserRows.length > 0 && <PlotLine series={1} points={browserRows.map((r) => ({ x: r.nodes, y: r.depth }))} />}
          {plainRows.length > 0 && <PlotDots series={2} dots={dots(plainRows, false)} />}
          {browserRows.length > 0 && <PlotDots series={1} dots={dots(browserRows, true)} />}
          {browser && <PlotCursor x={browser.nodes} y={browser.depth} />}
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
        {!mine && (
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
        )}
        <LegendItem mark={<line className="ml-fig-plot__cursor" x1={16} x2={16} y1={0} y2={16} />}>{kit('kit.legend.cursor')}</LegendItem>
      </Legend>
      {mine && <p className="ml-fig-budget__note" data-live-note="plain">{t('fig.budget.live.plainHidden')}</p>}
      {race && (
        <p className="ml-fig-budget__note" data-live-note="race">
          {t('fig.budget.live.race')}
        </p>
      )}
      <p className="ml-fig-budget__readout" aria-live="polite">
        {browser && <span>{t('fig.budget.readout', { nodes: n(browser.nodes), depth: n(browser.depth), move: browser.move.text })}</span>}
        {plain && <span>{t('fig.budget.readout.plain', { depth: n(plain.depth), move: plain.move.text })}</span>}
      </p>
    </Figure>
  );
}
