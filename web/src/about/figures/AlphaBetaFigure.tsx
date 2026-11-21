// V5: the alpha-beta search of the start position at depth 2, call by call. Icicle tree (one column per root move), readouts of the
// active call, a mini board with the line, and the switch between the pruned search and the full tree.
import { useRef, useState } from 'react';
import {
  Figure,
  IcicleTree,
  Legend,
  LegendItem,
  Manipulate,
  MiniBoard,
  PLOT_BREAKPOINT_PX,
  Readout,
  Segmented,
  Select,
  Stepper,
  stepperLabels,
  useMeasuredWidth,
  useStepper,
} from '../kit';
import { Markup } from '../kit/markup';
import raw from '../data/ab-tree-start.json';
import { FigFoot, figLabel, useFigText } from './common';
import type { Catalog } from './common';
import { columnsAt, counter, moveText, stepOfRootMove, stepView, columnOf } from './ab-model';
import type { AbData } from './ab-model';

const DATA = raw as unknown as AbData;

type Mode = 'minimax' | 'alphabeta';

export interface AlphaBetaFigureProps {
  catalog: Catalog;
  number: readonly [number, number];
}

const cellsOf = (owner: number): number[] => DATA.cells.flatMap((c, i) => (c === owner ? [i] : []));

export function AlphaBetaFigure({ catalog, number }: AlphaBetaFigureProps): React.JSX.Element {
  const f = useFigText(catalog);
  const { t, kit, n } = f;
  const [mode, setMode] = useState<Mode>('alphabeta');
  const stepper = useStepper(DATA.visits.length);
  const minimax = mode === 'minimax';
  const step = minimax ? 0 : stepper.state.step;
  const view = stepView(DATA, step);
  const count = counter(DATA, step, minimax);
  const columns = columnsAt(DATA, step, minimax);
  const holder = useRef<HTMLDivElement>(null);
  const width = useMeasuredWidth(holder, '.ml-fig');
  const compact = width > 0 && width < PLOT_BREAKPOINT_PX;

  const activeColumn = view ? columnOf(DATA, view.visit) : -1;
  const narrationAt = (at: number): string => {
    const v = stepView(DATA, at);
    return v ? v.narration.map((part) => f.plain(`fig.ab.narration.${part.key}`, part.values)).join(' ') : f.plain('fig.ab.narration.idle');
  };
  const stepLine = (at: number): { step?: string; text: string } => ({
    ...(at > 0 ? { step: f.kit('kit.stepper.step', { k: at, n: DATA.visits.length }) } : {}),
    text: narrationAt(at),
  });
  const minimaxText = (visited: number, total: number): string => `${t('fig.ab.counter.label')}: ${t('fig.ab.counter', { visited: n(visited), total: n(total) })}`;
  // every narration the figure can show, so that its status area keeps the height of the longest one
  const all = [...Array.from({ length: DATA.visits.length + 1 }, (_, at) => stepLine(at)), { text: minimaxText(count.total, count.total) }];
  const status = minimax ? { text: minimaxText(count.visited, count.total), all } : { ...stepLine(step), all };

  const rootMoveOptions = [
    { value: '', label: t('fig.ab.select.rootmove') },
    ...DATA.rootMoves.map((m, i) => ({ value: String(i), label: `${m.kind === 'jump' ? '»' : '›'} ${moveText(m.from, m.to)}` })),
  ];

  const arrows = view ? [{ from: view.rootMove.from, to: view.rootMove.to }, ...(view.reply ? [{ from: view.reply.from, to: view.reply.to, dashed: true }] : [])] : [];
  const board = (
    <div className="ml-fig-ab__board">
      <MiniBoard title={t('fig.ab.board.title')} desc={f.plain('fig.ab.board.desc')} pieces={{ a: cellsOf(0), b: cellsOf(1) }} arrows={arrows} />
      <p className="ml-fig-ab__orientation">{kit('kit.board.orientation', { top: f.ui('side_a'), bottom: f.ui('side_b') })}</p>
    </div>
  );

  const mark = (cls: string): React.JSX.Element => <rect className={`ml-fig-ice__cell ml-fig-ice__cell--${cls}`} x={8} y={3} width={16} height={10} />;
  const glyph = (g: string): React.JSX.Element => (
    <text x={16} y={8} textAnchor="middle" dominantBaseline="central" fontSize={16} fontWeight={700} fill="currentColor">
      {g}
    </text>
  );

  const info = (
    <div className="ml-fig-ab__info">
      <Readout
        cells={[
          { label: t('fig.ab.readout.alpha'), value: minimax || !view ? '–' : view.alpha },
          { label: t('fig.ab.readout.beta'), value: minimax || !view ? '–' : view.beta },
          { label: t('fig.ab.readout.score'), value: minimax || !view ? '–' : view.score },
          { label: t('fig.ab.counter.label'), value: t('fig.ab.counter', { visited: n(count.visited), total: n(count.total) }) },
        ]}
      />
      {/* always there, hidden while no cut-off happens: the note must not change the height of the figure from step to step */}
      <p className={view?.cut ? 'ml-fig-ab__cut' : 'ml-fig-ab__cut ml-fig-ab__cut--off'} aria-hidden={view?.cut ? undefined : true}>
        {t('fig.ab.cutoff')}
      </p>
    </div>
  );

  const tree = (
    <div className="ml-fig-ab__tree">
      <IcicleTree
        title={t('fig.ab.title')}
        desc={f.plain('fig.ab.desc')}
        columns={columns}
        layout={compact ? 'compact' : 'wide'}
        {...(compact ? {} : { rootText: f.plain('fig.ab.root') })}
      />
      <Legend>
        <LegendItem mark={mark('visited')}>{kit('kit.legend.visited')}</LegendItem>
        <LegendItem mark={mark('active')}>{kit('kit.legend.activenode')}</LegendItem>
        <LegendItem mark={mark('skipped')}>{kit('kit.legend.skipped')}</LegendItem>
        <LegendItem mark={mark('todo')}>{kit('kit.legend.todo')}</LegendItem>
        <LegendItem mark={glyph('»')}>{kit('kit.legend.jump')}</LegendItem>
        <LegendItem mark={glyph('›')}>{kit('kit.legend.step')}</LegendItem>
      </Legend>
    </div>
  );

  return (
    <Figure
      id="fig-ab"
      labelWord={figLabel(kit, number[0], number[1])}
      title={t('fig.ab.title')}
      status={status}
      panel={
        <Manipulate>
          <Segmented
            label={kit('kit.mode.label')}
            value={mode}
            onChange={setMode}
            options={[
              { value: 'minimax', label: t('fig.ab.mode.minimax') },
              { value: 'alphabeta', label: t('fig.ab.mode.alphabeta') },
            ]}
          />
          {!minimax && <Stepper state={stepper.state} dispatch={stepper.dispatch} labels={stepperLabels(f.kitT, t('fig.ab.stepper'))} />}
          {!minimax && (
            <Select
              label={t('fig.ab.select.rootmove')}
              value={activeColumn < 0 ? '' : String(activeColumn)}
              options={rootMoveOptions}
              onChange={(value) => {
                if (value !== '') stepper.dispatch({ type: 'set', step: stepOfRootMove(DATA, Number(value)) });
              }}
            />
          )}
        </Manipulate>
      }
      caption={<Markup text={t('fig.ab.caption')} />}
      foot={<FigFoot kit={kit} paths={['search/src/main/java/sternhalma/search/AlphaBeta.java', 'search/src/main/java/sternhalma/search/Evaluators.java']} data="ab-tree-start.json" />}
    >
      <div ref={holder} className="ml-fig-ab">
        {info}
        {tree}
        {board}
      </div>
    </Figure>
  );
}

