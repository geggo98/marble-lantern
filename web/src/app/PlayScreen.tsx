// The play screen: status bar, board, notices (loading, result, error), controls, engine card, figures, move list.
import { useMemo, useRef, useState, useSyncExternalStore } from 'react';
import type { GameController } from '../game/game';
import { goalCells, initialOwners, playState } from '../game/board-state';
import type { Side } from '../game/board-state';
import { isDefault, moveLimit, variantKey } from '../game/variant';
import type { Player } from '../game/variant';
import { isNetEngine } from '../engines/engine';
import type { EngineId } from '../engines/engine';
import type { Sims } from '../engines/warnings';
import { Board } from '../ui/Board';
import { AdvantageBar } from '../ui/AdvantageBar';
import type { AdvantageState } from '../ui/AdvantageBar';
import { AdvantageBar3 } from '../ui/AdvantageBar3';
import { GuideSettings } from '../ui/GuideSettings';
import { TrailSetting } from '../ui/TrailSetting';
import { loadTrailSeconds, saveTrailSeconds } from '../ui/trail-fade';
import { useGuides } from '../guides/useGuides';
import { useAdvantage3 } from '../guides/useAdvantage3';
import type { Evaluate3Fn } from '../guides/useAdvantage3';
import { loadGuideLevel, saveGuideLevel } from '../guides/preference';
import type { AnalyseFn } from '../guides/analyser';
import { estimateDistance } from '../result/distance';
import type { DistanceEstimate } from '../result/distance';
import { useDistanceEstimate } from '../result/useDistance';
import { CopyLink, REPO_URL, ThemeSwitch } from '../ui/Chrome';
import { LanguageToggle } from '../ui/LanguageToggle';
import { ActionBar } from '../ui/ActionBar';
import { Menu } from '../ui/Menu';
import { useWide } from '../ui/useMedia';
import { approximateDistance, cellName, describeHistory } from '../ui/board-model';
import { EngineSheet } from '../ui/EngineSheet';
import { NetNotice, netNoticeKind } from '../ui/net-warnings';
import { DEFAULT_SIMS } from '../engines/warnings';
import {
  AlertCard, Controls, EngineCard, LoadingCard, MoveList, ResultCard, SeatsCard, StatusBar, Telemetry,
} from '../ui/Panels';
import type { AlertAction } from '../ui/Panels';
import type { SideStyle } from '../ui/primitives';
import { seatNameKey, seatViews } from '../ui/seat-view';
import { useT } from '../ui/texts';
import type { TextKey } from '../ui/texts';
import { SkillCard } from '../ui/SkillCard';
import type { SkillTracker } from '../skill/tracker';
import type { EngineSession } from './session';
import type { SessionError } from './session';

import type { NetSelectionStore } from './net-selection';
import { InstallEntry } from '../ui/PwaUi';
import type { PwaPort } from '../pwa/pwa';

export interface PlayModel {
  game: GameController;
  session: EngineSession;
  /** The selection of the net engines (model, simulations) with its state hook useNetSelection; the model picker of the next UI step binds to it. */
  netSelection?: NetSelectionStore;
  /** The engine's analyse method, for the move guides. */
  analyse: AnalyseFn;
  /** The static evaluation per seat (three players), for the advantage indicator; engines without it reject with code unsupported. */
  evaluate3?: Evaluate3Fn;
  /**
   * The engine whose scores `analyse` and `evaluate3` return: the engine in use, or for a net engine the classical engine that
   * analyses for it (docs/web-ui.md, "Analysis for the net engines"). The guide caches key on it. Absent: the engine in use.
   */
  analysisId?: EngineId | null;
  humanSide: Side;
  demo: boolean;
  level: number;
  /** Skill estimate: the tracker, and the preset level that "Back to preset level" returns to. */
  tracker: SkillTracker;
  presetLevel: number;
  /** Copy link: only while a human game runs. */
  canShare: boolean;
  getUrl: () => string;
  onNewGame: () => void;
  onSwap: () => void;
  onDemo: () => void;
  onUndo: () => void;
  /** "Play on for 2nd place": the game goes on under full ranking (GameController.continuePlay). */
  onPlayOn?: () => void;
  onApply: (engine: EngineId, level: number, net?: { modelId?: string | null; sims: Sims }) => void;
  /** Loads the model again after a failed load (session.applyNet). */
  onRetryNet?: () => void;
  onRestart: () => void;
  onSelect: (engine: EngineId) => void;
  /** The installable app: the install entry in the side column and the menu. Absent in the online mode. */
  pwa?: PwaPort;
}

/** E1 to E4 of the design: the error code decides title, text and actions. */
function describeError(
  error: SessionError,
  model: PlayModel,
  t: ReturnType<typeof useT>['t'],
): { title: string; body: string; actions: AlertAction[] } {
  const retry: AlertAction = { label: t('retry'), primary: true, onClick: model.onRestart };
  const cpp: AlertAction = { label: t('use_cpp'), primary: true, onClick: () => model.onSelect('cpp-wasm') };
  switch (error.code) {
    case 'table-checksum':
    case 'table-truncated':
    case 'table-trailing':
    case 'table-magic':
      return { title: t('err_checksum_title'), body: t('err_checksum_body'), actions: [retry] };
    case 'wasm-gc-unsupported':
      return {
        title: t('err_wasmgc_title'),
        body: t('err_wasmgc_body'),
        actions: [cpp, { label: t('use_java_js'), onClick: () => model.onSelect('java-js') }],
      };
    case 'server-unreachable':
      return { title: t('err_server_title'), body: t('err_server_body'), actions: [retry, { ...cpp, primary: false }] };
    case 'no-engine':
      return { title: t('err_no_engine_title'), body: t('err_no_engine_body'), actions: [] };
    default:
      return {
        title: t('err_crash_title'),
        body: t('err_crash_body'),
        actions: [{ label: t('restart_engine'), primary: true, onClick: model.onRestart }],
      };
  }
}

export function PlayScreen({ model }: { model: PlayModel }): React.JSX.Element {
  const { t, n } = useT();
  const { game, session, humanSide, demo } = model;
  const state = useSyncExternalStore(game.subscribe.bind(game), () => game.state);
  const s = useSyncExternalStore(session.subscribe, session.getState);
  const [sheetFocus, setSheetFocus] = useState<'engines' | 'model' | null>(null); // null: the sheet is closed
  const sheet = sheetFocus !== null;
  const setSheet = (open: boolean): void => setSheetFocus(open ? 'engines' : null);
  const wide = useWide();
  const [menu, setMenu] = useState(false);
  const [trailSeconds, setTrailSeconds] = useState(() => loadTrailSeconds());
  const [closedResult, setClosedResult] = useState<string | null>(null);
  const menuButton = useRef<HTMLButtonElement | null>(null);

  const ready = s.phase === 'ready';
  const variant = state.variant;
  const three = variant.players === 3;
  // marble, name and cues of every seat (ui/seat-view.ts); two players keep the classic amber and blue
  const views = useMemo(() => seatViews(state.seats, demo), [state.seats, demo]);
  const styleOf = (side: number): SideStyle => views[side]?.style ?? 'b';
  const aSide: Player = (views.find((v) => v.style === 'a')?.seat ?? 1) as Player;
  const rotated = !three && aSide === 0; // the person at the top corner sees the board turned; three players never turn it
  // the skill estimate is for two players only; the move guides and the advantage indicator work for both player counts
  const skillable = variant.players === 2;
  // `legal` is empty for a moment after the engine's reply, before the engine has listed the moves; a tap in that moment would be lost, so the board
  // (aria-disabled, taps, the analysis of the strip) waits for the list.
  const humanTurn = ready && !demo && !state.outcome && !state.thinking && state.legal.length > 0 && state.seats[state.sideToMove] === 'human';
  const [guideLevel, setGuideLevel] = useState(loadGuideLevel);
  const guidesOn = guideLevel === 'eval'; // the marks of the evaluation (bands, piece badges, legend); the levels "targets" and "none" draw none
  // the cache scope names the engine that computes the scores, which for a net engine is its classical analysis engine
  const guideScope = s.current ? (model.analysisId ?? s.current.info.id) + (isDefault(variant) ? '' : `@${variantKey(variant)}`) : null;
  // a net engine plays and a classical engine analyses: the guide card names it
  const analysisLabelKey =
    s.current && isNetEngine(s.current.info.id) && model.analysisId && model.analysisId !== s.current.info.id
      ? s.choices.find((c) => c.info.id === model.analysisId)?.info.labelKey
      : undefined;
  // Two players: the advantage strip needs the analysis of the human's turn at every guide level, so it runs at every level (one analysis
  // per human turn, docs/web-ui.md, "Advantage strip"). Three players: the strip takes a cheap static evaluation instead (below), and the
  // analysis runs only for the marks.
  const guides = useGuides({
    analyse: model.analyse,
    scope: guideScope,
    enabled: !demo && (!three || guidesOn),
    humanTurn,
    history: state.history,
  });
  const evaluate3 = model.evaluate3;
  const advantage3 = useAdvantage3({
    evaluate3: evaluate3 ?? (() => Promise.reject(new Error('no static evaluation'))),
    scope: guideScope,
    enabled: !demo && three && evaluate3 !== undefined,
    history: state.history,
  });
  const boardGuides = useMemo(() => (guidesOn && guides.view ? { pieces: guides.view.pieces, moves: guides.view.moves } : null), [guidesOn, guides.view]);
  // the advantage strip: always on the play screen (docs/web-ui.md, "Advantage strip"); this is what it shows
  const analysisGone = guides.status === 'unavailable' || guides.status === 'failed';
  const advState: AdvantageState = demo ? 'unavailable' : three ? (evaluate3 === undefined || advantage3.failed ? 'unavailable' : 'value') : guides.advantage ? 'value' : analysisGone ? 'unavailable' : 'pending';
  const seatName = (side: number): string => {
    const v = views[side];
    return v ? t(seatNameKey(v), { n: v.number }) : '';
  };
  const sideLabel = (side: number): string => {
    const v = views[side];
    if (three && v) {
      if (demo) return t(`cell_side_${v.style}` as 'cell_side_a');
      return t(v.you ? 'cell_seat_you' : v.kind === 'engine' ? 'cell_seat_engine' : 'cell_seat', { n: v.number });
    }
    return demo ? t(styleOf(side) === 'a' ? 'cell_side_a' : 'cell_side_b') : t(side === humanSide ? 'cell_own' : 'cell_other');
  };
  const who = (side: number): string => {
    if (three) return demo ? t(`side_${styleOf(side)}` as 'side_a') : seatName(side);
    return demo ? t(styleOf(side) === 'a' ? 'side_a' : 'side_b') : t(side === humanSide ? 'side_you' : 'side_engine');
  };

  const rankText = (index: number): string => t(`rank_${index + 1}` as 'rank_1');
  const turn = useMemo(() => playState(state.history, variant), [state.history, variant]);
  // full ranking: the rank of each seat that is out, and the move after which it finished
  const rankOfSeat = new Map<number, string>((state.ranking ?? state.finished).map((seat, i) => [seat, rankText(i)] as const));
  const rankAfterMove = new Map<number, string>(turn.finishedAt.map((length, i) => [length - 1, rankText(i)] as const));
  const mainRef = useRef<HTMLElement | null>(null);
  /** "Play on": the result card (and its button) goes away; the focus moves to the page so that it does not fall back to the body. */
  const playOn = (): void => {
    model.onPlayOn?.();
    mainRef.current?.focus({ preventScroll: true });
  };

  const moves = useMemo(() => describeHistory(state.history, initialOwners(variant)), [state.history, variant]);
  const last = moves[moves.length - 1] ?? null;

  // status bar: whose turn, in the marble of that seat; an engine seat that is searching pulses
  let statusText = t('status_loading');
  let statusStyle: SideStyle = styleOf(state.sideToMove);
  if (ready) {
    const mover = state.sideToMove;
    if (state.outcome) statusText = t('status_over');
    else if (three && state.finished.length > 0) {
      // full ranking: a seat is out, the game is for the lower ranks
      statusText = views[mover]?.you && !state.thinking ? t('turn_you_second') : t(state.thinking ? 'thinking_seat_second' : 'turn_seat_second', { player: who(mover) });
    } else if (three) statusText = views[mover]?.you && !state.thinking ? t('turn_you') : t(state.thinking ? 'thinking_seat' : 'turn_seat', { player: who(mover) });
    else if (state.thinking) statusText = t('engine_thinking');
    else statusText = !demo && state.seats[mover] === 'human' ? t('turn_you') : t('turn_engine');
  }
  if (state.outcome?.kind === 'win') statusStyle = styleOf(state.outcome.winner);
  if (state.outcome?.kind === 'ranking') statusStyle = styleOf(state.outcome.ranking[0]);

  // notice slot: one place for loading, result and errors; a result on a small screen can be closed (it lies over the board)
  const sessionError = s.phase === 'failed' ? s.error : null;
  const gameError = state.error ? { code: (state.error as { code?: string }).code ?? 'internal', message: state.error.message } : null;
  const error = sessionError ?? gameError;
  const resultKey = state.outcome ? `${state.outcome.kind}@${state.history.length}` : null;
  // the distance of a win (docs/web-ui.md, "Distance of the result"): estimated while the result card is shown, aborted when it goes
  const distanceKey = state.outcome?.kind === 'win' && !error && closedResult !== resultKey ? resultKey : null;
  const distance = useDistanceEstimate(distanceKey, (signal) =>
    state.outcome?.kind === 'win'
      ? estimateDistance({ history: state.history, variant, winner: state.outcome.winner, analyse: model.analyse, signal })
      : Promise.resolve(null),
  );
  const distanceLine = (): { state: 'estimating' } | { state: 'done'; text: string } | undefined => {
    if (distance.status === 'estimating') return { state: 'estimating' };
    if (distance.status !== 'done') return undefined;
    const e: DistanceEstimate = distance.estimate;
    const count = t(e.capped ? 'result_distance_more' : 'result_distance_about', { n: n(e.plies) });
    return { state: 'done', text: t(e.kind === 'solo' ? 'result_distance_solo' : 'result_distance_second', { count }) };
  };
  let notice: React.JSX.Element | null = null;
  if (error) {
    const d = describeError(error, model, t);
    notice = <AlertCard title={d.title} body={d.body} code={error.code} actions={d.actions} />;
  } else if (s.phase === 'loading' || s.phase === 'discovering') {
    const label = s.current ? t(s.current.info.labelKey as TextKey) : t('status_loading');
    notice = <LoadingCard engineLabel={label} progress={s.progress} />;
  } else if (state.outcome && closedResult !== resultKey) {
    const o = state.outcome;
    const onClose = !wide ? () => setClosedResult(resultKey) : undefined;
    if (o.kind === 'win') {
      const winnerView = views[o.winner];
      const headline = demo
        ? t('result_win_side', { side: who(o.winner) })
        : three
          ? winnerView?.you ? t('result_win_you') : t('result_win_seat', { player: seatName(o.winner) })
          : t(o.winner === humanSide ? 'result_win_you' : 'result_win_engine');
      notice = (
        <ResultCard
          winner={styleOf(o.winner)}
          headline={headline}
          plies={state.history.length}
          distance={distanceLine()}
          onClose={onClose}
          onPlayOn={three && state.canPlayOn && model.onPlayOn ? playOn : undefined}
        />
      );
    } else if (o.kind === 'ranking') {
      const goals = goalCells(variant);
      const ranks = o.ranking.map((seat, i) => ({
        style: styleOf(seat),
        label: who(seat),
        rank: rankText(i),
        // a seat that still played when the move limit or another ending stopped the game: its distance to the goal (with a complete ranking the last seat has no distance to show)
        ...(o.reason !== 'complete' && i >= state.finished.length ? { value: approximateDistance(state.owner, seat, goals) } : {}),
      }));
      const winner = o.ranking[0];
      const headline =
        o.reason === 'complete'
          ? demo
            ? t('result_win_side', { side: who(winner) })
            : views[winner]?.you
              ? t('result_win_you')
              : t('result_win_seat', { player: seatName(winner) })
          : o.reason === 'move-limit'
            ? t('result_limit')
            : t('result_nomoves');
      notice = (
        <ResultCard
          winner={o.reason === 'complete' ? styleOf(winner) : null}
          headline={headline}
          {...(o.reason === 'move-limit' ? { note: t('result_limit_note') } : {})}
          plies={o.reason === 'move-limit' ? moveLimit(variant) : state.history.length}
          ranking={ranks}
          onClose={onClose}
        />
      );
    } else if (o.kind === 'move-limit') {
      const goals = goalCells(variant);
      const order = three ? state.seats.map((_, i) => i) : [aSide, aSide === 0 ? 1 : 0];
      const values = new Map(order.map((seat) => [seat, approximateDistance(state.owner, seat, goals)]));
      const best = Math.min(...values.values());
      const leaders = [...values.values()].filter((v) => v === best).length;
      const rows = order
        .map((seat) => {
          const value = values.get(seat)!;
          const others = order.filter((x) => x !== seat).map((x) => values.get(x)!);
          return { style: styleOf(seat), label: who(seat), value, ahead: three ? value === best && leaders === 1 : value < others[0]! };
        })
        .sort((x, y) => (three ? x.value - y.value : 0));
      notice = (
        <ResultCard
          winner={null}
          headline={t('result_limit')}
          note={t('result_limit_note')}
          plies={moveLimit(variant)}
          distances={rows}
          leadText={three ? t('closest') : undefined}
          onClose={onClose}
        />
      );
    } else {
      notice = <ResultCard winner={null} headline={t('result_nomoves')} plies={state.history.length} onClose={onClose} />;
    }
  }

  // net engines: what plays the moves now, from the warnings of the session (the engine decides, not the selection)
  const netEngine = !!s.current && isNetEngine(s.current.info.id);
  const netBackend: 'wasm' | 'webgpu' | 'random' | null = !netEngine
    ? null
    : s.warnings.some((w) => w.code === 'fallback-random')
      ? 'random'
      : s.warnings.some((w) => w.code === 'backend-unavailable') || s.current?.info.id === 'net-wasm'
        ? 'wasm'
        : 'webgpu';

  const noticeKind = netNoticeKind(netEngine, s.warnings);

  const halo =
    state.outcome?.kind === 'win'
      ? (goalCells(variant)[state.outcome.winner] ?? []).filter((c) => state.owner[c] === (state.outcome as { winner: Player }).winner)
      : [];

  const controls = (part: 'all' | 'secondary'): React.JSX.Element => (
    <Controls
      part={part}
      canSwap={state.canSwap}
      disabled={!ready}
      canUndo={state.history.length > 0}
      demoRunning={model.demo && (state.thinking || (!state.outcome && ready && game.isEngineTurn()))}
      onNewGame={model.onNewGame}
      onUndo={model.onUndo}
      onSwap={model.onSwap}
      onDemo={model.onDemo}
    />
  );
  // One source of content, two presentations: the side column of a wide screen and the menu of a small one.
  const cards = (
    <>
      {three && <SeatsCard seats={views.map((v) => ({ style: v.style, name: seatName(v.seat), onMove: !state.outcome && state.sideToMove === v.seat, ...(rankOfSeat.has(v.seat) ? { rank: rankOfSeat.get(v.seat)! } : {}) }))} hotSeat={state.seats.filter((k) => k === 'human').length === 2} />}
      <GuideSettings
        three={three}
        level={guideLevel}
        {...(analysisLabelKey ? { analysisLabelKey } : {})}
        status={guidesOn ? guides.status : 'off'}
        onChange={(level) => {
          setGuideLevel(level);
          saveGuideLevel(level);
        }}
      />
      <TrailSetting
        seconds={trailSeconds}
        onChange={(n) => {
          setTrailSeconds(n);
          saveTrailSeconds(n);
        }}
      />
      <CopyLink getUrl={model.getUrl} disabled={!model.canShare} />
      <InstallEntry {...(model.pwa ? { pwa: model.pwa } : {})} />
      <EngineCard
        info={s.current?.info ?? null}
        level={model.level}
        onChange={() => setSheet(true)}
        warnings={s.warnings}
        {...(netBackend ? { net: { sims: model.netSelection?.selection.sims ?? DEFAULT_SIMS, backend: netBackend } } : {})}
        onWarningAction={{ 'choose-model': () => setSheetFocus('model'), 'choose-other': () => setSheetFocus('model'), ...(model.onRetryNet ? { retry: model.onRetryNet } : {}) }}
      />
      {!demo && skillable && s.current && (
        <SkillCard
          tracker={model.tracker}
          level={model.level}
          onMatch={(level) => model.onApply(s.current!.info.id, level)}
          onReset={() => model.onApply(s.current!.info.id, model.presetLevel)}
        />
      )}
      <Telemetry result={state.lastEngine} stale={state.thinking} />
      <MoveList moves={moves} styleOf={styleOf} defaultOpen={wide} perRow={variant.players} ranks={rankAfterMove} />
    </>
  );

  return (
    <main ref={mainRef} tabIndex={-1} className={noticeKind ? 'ml-play ml-play--net' : 'ml-play'}>
      <div className="ml-play__stage">
        <StatusBar
          state={state.thinking ? 'thinking' : 'ready'}
          style={statusStyle}
          text={statusText}
          ply={state.history.length}
          limit={moveLimit(variant)}
        />
        <NetNotice netEngine={netEngine} warnings={s.warnings} onOpen={() => setSheet(true)} />
        <div className="ml-play__boardwrap">
          <Board
            owner={state.owner}
            legal={state.legal}
            interactive={humanTurn}
            inert={!humanTurn}
            guides={boardGuides}
            targetMarks={guideLevel !== 'none'}
            aSide={aSide as Side}
            rotated={rotated}
            plies={state.history.length}
            last={last}
            trailSeconds={trailSeconds}
            halo={halo}
            sideLabel={sideLabel}
            seatStyle={styleOf}
            goals={goalCells(variant).map((cells, seat) => ({ cells, style: styleOf(seat) }))}
            onMove={(move) => {
              guides.noteMove(move);
              void game.play(move).catch(() => undefined);
            }}
          />
          {!wide && notice && <div className="ml-play__notice">{notice}</div>}
        </div>
        {/* the slot is always there and keeps its height in every state: nothing on the screen moves when the value arrives */}
        <div className="ml-play__adv" data-adv-state={advState === 'value' && three && !advantage3.shares ? 'pending' : advState}>
          {three && advState === 'value' ? (
            <AdvantageBar3 shares={advantage3.shares} names={views.map((v) => seatName(v.seat))} styles={views.map((v) => v.style)} stale={advantage3.stale} />
          ) : (
            <AdvantageBar
              state={advState}
              info={guides.advantage}
              stale={guides.advantageStale}
              ends={demo || three ? null : { engine: styleOf(humanSide === 0 ? 1 : 0), you: styleOf(humanSide) }}
            />
          )}
        </div>
        <p className="ml-visually-hidden" role="status" aria-live="polite">
          {last ? t('announce_move', { side: who(last.side), from: cellName(last.from), to: cellName(last.to) }) : ''}
        </p>
      </div>
      {wide ? (
        <div className="ml-play__side">
          {notice}
          {controls('all')}
          {cards}
        </div>
      ) : (
        <ActionBar
          disabled={!ready}
          canUndo={state.history.length > 0}
          menuOpen={menu}
          menuId="ml-menu"
          menuRef={menuButton}
          onUndo={model.onUndo}
          onNewGame={model.onNewGame}
          onMenu={() => setMenu(true)}
        />
      )}
      {!wide && menu && (
        <Menu id="ml-menu" onClose={() => setMenu(false)} inert={sheet} returnFocusTo={menuButton}>
          {controls('secondary')}
          {cards}
          <div className="ml-menu__foot">
            <ThemeSwitch />
            <LanguageToggle inDialog />
            <p className="ml-menu__note">
              {t('footer_note')} · <a href={REPO_URL} rel="noreferrer">{t('source_code')}</a>
            </p>
          </div>
        </Menu>
      )}
      {sheet && (
        <EngineSheet
          choices={s.choices}
          currentId={s.current?.info.id ?? null}
          level={model.level}
          onClose={() => setSheet(false)}
          initialFocus={sheetFocus ?? 'engines'}
          netStore={model.netSelection}
          variant={variant}
          onApply={(engine, level, net) => {
            setSheet(false);
            model.onApply(engine, level, net);
          }}
        />
      )}
    </main>
  );
}
