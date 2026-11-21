// Fig. 2.1b, the root-scores board: the score of every legal move of a position from the move-guide analysis (4,000 nodes per move).
// Each movable piece is a disc, larger the better its best move; arrows show the three best moves; the table below lists every move and is
// the accessible form of the board. The example is the position of eval-mid.json; with a snapshot of the reader's game (live switch) the
// scores are computed in the browser.
import { Figure, HeatBoard, LiveSwitch, Manipulate } from '../kit';
import type { Arrow, HeatCell, HeatLevel } from '../kit';
import { Markup } from '../kit/markup';
import { cellPoint } from '../../ui/board-model';
import { BAND_ORDER } from '../../guides/bands';
import type { Band } from '../../guides/bands';
import raw from '../data/root-scores-mid.json';
import { FigFoot, figLabel, useFigText } from './common';
import type { Catalog } from './common';
import { HeatLegend } from './HeatLegend';
import { liveBlockedReason } from '../live/classic';
import { useLive } from '../live/context';
import { pieceBands, runRootScores } from '../live/root-scores';
import type { RootScoresData } from '../live/root-scores';
import { liveStatusText, liveValue } from '../live/status';
import { useLiveRun } from '../live/useLiveRun';
import './root-scores.css';

const EXAMPLE = raw as unknown as RootScoresData;

/** The disc level of a band: five bands, five sizes, the best band the largest disc. */
export const BAND_LEVEL: Readonly<Record<Band, HeatLevel>> = { 'much-better': 5, better: 4, equal: 3, worse: 2, 'much-worse': 1 };

/** The three best moves, as the board's arrows. */
export const topArrows = (data: RootScoresData): Arrow[] => data.moves.slice(0, 3).map((m) => ({ from: m.from, to: m.to }));

/** One heat cell per movable piece. The pieces without a move, and the opponent's, are marbles drawn by the caller. */
export function heatCells(data: RootScoresData): (HeatCell | null)[] {
  const bands = pieceBands(data.moves);
  return data.cells.map((_, cell) => {
    const band = bands.get(cell);
    return band === undefined ? null : { level: BAND_LEVEL[band] };
  });
}

export interface RootScoresFigureProps {
  catalog: Catalog;
  number: readonly [number, number];
}

export function RootScoresFigure({ catalog, number }: RootScoresFigureProps): React.JSX.Element {
  const f = useFigText(catalog);
  const { t, kit, n } = f;
  const { input } = useLive();
  const run = useLiveRun<RootScoresData>('root-scores', async (engines, signal, report) => {
    const snapshot = input!.snapshot; // start() does nothing without a snapshot
    const engine = await engines.classical(report.progress);
    report.step(0, 1);
    return runRootScores(engine, snapshot.history, snapshot.variant, signal);
  });
  const mine = liveValue(run.state) === 'mine';
  const reason = liveBlockedReason(input, 'classical');
  const ply = input?.snapshot.ply ?? 0;
  // In My-game mode nothing of the example stays on the stage: until the analysis is done the board shows only its sockets.
  const data: RootScoresData | null = !mine ? EXAMPLE : run.state.kind === 'done' ? run.state.value : null;
  const bandText = (band: Band): string => f.ui(`guide_move_${band.replace('-', '_')}`);

  const bands = data ? pieceBands(data.moves) : new Map<number, Band>();
  const marbles = data
    ? data.cells.flatMap((owner, cell) => (owner < 0 || bands.has(cell) ? [] : [{ cell, seat: owner === 0 ? 'a' : 'b' }]))
    : [];
  const description = mine ? f.plain('fig.roots.live.desc', { ply }) : f.plain('fig.roots.desc');

  return (
    <Figure
      id="fig-roots"
      labelWord={`${figLabel(kit, number[0], number[1])}b`}
      title={t('fig.roots.title')}
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
        </Manipulate>
      }
      caption={<Markup text={description} />}
      foot={
        <FigFoot
          kit={kit}
          paths={['web/src/guides/bands.ts', 'web/src/guides/model.ts']}
          data="root-scores-mid.json"
          origin={mine ? { kind: 'mine', ply } : { kind: 'example', ply: EXAMPLE.meta.ply }}
        />
      }
    >
      <div className="ml-fig-roots" data-roots={data ? 'ready' : 'waiting'}>
        <div className="ml-fig-roots__board">
          <HeatBoard
            title={t('fig.roots.title')}
            desc={description}
            cells={data ? heatCells(data) : []}
            arrows={data ? topArrows(data) : []}
          >
            <g aria-hidden="true">
              {marbles.map((m) => {
                const p = cellPoint(m.cell, false);
                return <use key={m.cell} href={`#ml-piece-${m.seat}`} x={p.x} y={p.y} data-seat={m.seat} data-cell={m.cell} />;
              })}
            </g>
          </HeatBoard>
          <p className="ml-fig-ab__orientation">{kit('kit.board.orientation', { top: f.ui('side_a'), bottom: f.ui('side_b') })}</p>
          <HeatLegend label={kit('kit.legend.heat')} low={bandText(BAND_ORDER[4]!)} high={bandText(BAND_ORDER[0]!)} />
        </div>
        {data && (
          <div className="ml-fig-roots__scroll" tabIndex={0} role="region" aria-label={t('fig.roots.title')}>
            <table className="ml-fig-table">
              <thead>
                <tr>
                  <th scope="col">{t('fig.roots.table.move')}</th>
                  <th scope="col" className="ml-fig-table__num">
                    {t('fig.roots.table.score')}
                  </th>
                  <th scope="col">{t('fig.roots.table.band')}</th>
                </tr>
              </thead>
              <tbody>
                {data.moves.map((m, rank) => (
                  <tr key={`${m.from}-${m.to}`} data-rank={rank < 3 ? rank + 1 : undefined} data-band={m.band}>
                    <th scope="row">{m.text}</th>
                    <td className="ml-fig-table__num">{n(m.score)}</td>
                    <td>{bandText(m.band)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </Figure>
  );
}
