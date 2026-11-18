// The play screen: status bar, board, notices (loading, result, error), controls, engine card, figures, move list.
import { useMemo, useRef, useState, useSyncExternalStore } from 'react';
import { GOAL_CELLS } from '../board/cells';
import type { GameController } from '../game/game';
import { MOVE_LIMIT, initialOwners } from '../game/board-state';
import type { Side } from '../game/board-state';
import type { EngineId } from '../engines/engine';
import { Board } from '../ui/Board';
import { AdvantageBar } from '../ui/AdvantageBar';
import { GuideSettings } from '../ui/GuideSettings';
import { useGuides } from '../guides/useGuides';
import { loadGuidesPreference, saveGuidesPreference } from '../guides/preference';
import type { AnalyseFn } from '../guides/analyser';
import { CopyLink, REPO_URL, ThemeSwitch } from '../ui/Chrome';
import { ActionBar } from '../ui/ActionBar';
import { Menu } from '../ui/Menu';
import { useWide } from '../ui/useMedia';
import { approximateDistance, cellName, describeHistory } from '../ui/board-model';
import { EngineSheet } from '../ui/EngineSheet';
import {
  AlertCard, Controls, EngineCard, LoadingCard, MoveList, ResultCard, StatusBar, Telemetry,
} from '../ui/Panels';
import type { AlertAction } from '../ui/Panels';
import type { SideStyle } from '../ui/primitives';
import { useT } from '../ui/texts';
import type { TextKey } from '../ui/texts';
import { SkillCard } from '../ui/SkillCard';
import type { SkillTracker } from '../skill/tracker';
import type { EngineSession } from './session';
import type { SessionError } from './session';

export interface PlayModel {
  game: GameController;
  session: EngineSession;
  /** The engine's analyse method, for the move guides. */
  analyse: AnalyseFn;
  humanSide: Side;
  demo: boolean;
  level: number;
  /** Skill estimate: the tracker, and the preset level that "Reset to level" returns to. */
  tracker: SkillTracker;
  presetLevel: number;
  /** Copy link: only while a human game runs. */
  canShare: boolean;
  getUrl: () => string;
  onNewGame: () => void;
  onSwap: () => void;
  onDemo: () => void;
  onUndo: () => void;
  onApply: (engine: EngineId, level: number) => void;
  onRestart: () => void;
  onSelect: (engine: EngineId) => void;
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
  const { t } = useT();
  const { game, session, humanSide, demo } = model;
  const state = useSyncExternalStore(game.subscribe.bind(game), () => game.state);
  const s = useSyncExternalStore(session.subscribe, session.getState);
  const [sheet, setSheet] = useState(false);
  const wide = useWide();
  const [menu, setMenu] = useState(false);
  const menuButton = useRef<HTMLButtonElement | null>(null);

  const ready = s.phase === 'ready';
  const aSide: Side = demo ? 1 : humanSide;
  const rotated = aSide === 0;
  const styleOf = (side: number): SideStyle => (side === aSide ? 'a' : 'b');
  const humanTurn = ready && !demo && !state.outcome && !state.thinking && state.sideToMove === humanSide;
  const [guidesOn, setGuidesOn] = useState(loadGuidesPreference);
  const guides = useGuides({
    analyse: model.analyse, scope: s.current?.info.id ?? null, enabled: guidesOn && !demo, humanTurn, history: state.history,
  });
  const boardGuides = useMemo(() => (guides.view ? { pieces: guides.view.pieces, moves: guides.view.moves } : null), [guides.view]);
  const sideLabel = (side: Side): string =>
    demo ? t(styleOf(side) === 'a' ? 'cell_side_a' : 'cell_side_b') : t(side === humanSide ? 'cell_own' : 'cell_other');
  const who = (side: Side): string => (demo ? t(styleOf(side) === 'a' ? 'side_a' : 'side_b') : t(side === humanSide ? 'side_you' : 'side_engine'));

  const moves = useMemo(() => describeHistory(state.history, initialOwners()), [state.history]);
  const last = moves[moves.length - 1] ?? null;

  // status bar
  let statusText = t('status_loading');
  let statusStyle: SideStyle = styleOf(state.sideToMove);
  if (ready) {
    if (state.outcome) statusText = t('status_over');
    else if (state.thinking) statusText = t('engine_thinking');
    else statusText = !demo && state.sideToMove === humanSide ? t('turn_you') : t('turn_engine');
  }
  if (state.outcome?.kind === 'win') statusStyle = styleOf(state.outcome.winner);

  // notice slot: one place for loading, result and errors
  const sessionError = s.phase === 'failed' ? s.error : null;
  const gameError = state.error ? { code: (state.error as { code?: string }).code ?? 'internal', message: state.error.message } : null;
  const error = sessionError ?? gameError;
  let notice: React.JSX.Element | null = null;
  if (error) {
    const d = describeError(error, model, t);
    notice = <AlertCard title={d.title} body={d.body} code={error.code} actions={d.actions} />;
  } else if (s.phase === 'loading' || s.phase === 'discovering') {
    const label = s.current ? t(s.current.info.labelKey as TextKey) : t('status_loading');
    notice = <LoadingCard engineLabel={label} progress={s.progress} />;
  } else if (state.outcome) {
    const o = state.outcome;
    if (o.kind === 'win') {
      const headline = demo ? t('result_win_side', { side: who(o.winner) }) : t(o.winner === humanSide ? 'result_win_you' : 'result_win_engine');
      notice = <ResultCard winner={styleOf(o.winner)} headline={headline} plies={state.history.length} />;
    } else if (o.kind === 'move-limit') {
      const rows = ([aSide, aSide === 0 ? 1 : 0] as Side[]).map((side) => {
        const value = approximateDistance(state.owner, side);
        const other = approximateDistance(state.owner, side === 0 ? 1 : 0);
        return { style: styleOf(side), label: who(side), value, ahead: value < other };
      });
      notice = (
        <ResultCard winner={null} headline={t('result_limit')} note={t('result_limit_note')} plies={MOVE_LIMIT} distances={rows} />
      );
    } else {
      notice = <ResultCard winner={null} headline={t('result_nomoves')} plies={state.history.length} />;
    }
  }

  const halo =
    state.outcome?.kind === 'win'
      ? GOAL_CELLS[state.outcome.winner].filter((c) => state.owner[c] === (state.outcome as { winner: Side }).winner)
      : [];

  const controls = (part: 'all' | 'secondary'): React.JSX.Element => (
    <Controls
      part={part}
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
      <GuideSettings
        enabled={guidesOn}
        status={guides.status}
        onChange={(on) => {
          setGuidesOn(on);
          saveGuidesPreference(on);
        }}
      />
      <CopyLink getUrl={model.getUrl} disabled={!model.canShare} />
      <EngineCard info={s.current?.info ?? null} level={model.level} onChange={() => setSheet(true)} />
      {!demo && s.current && (
        <SkillCard
          tracker={model.tracker}
          level={model.level}
          onMatch={(level) => model.onApply(s.current!.info.id, level)}
          onReset={() => model.onApply(s.current!.info.id, model.presetLevel)}
        />
      )}
      <Telemetry result={state.lastEngine} stale={state.thinking} />
      <MoveList moves={moves} styleOf={styleOf} defaultOpen={wide} />
    </>
  );

  return (
    <main className="ml-play">
      <div className="ml-play__stage">
        <StatusBar
          state={state.thinking ? 'thinking' : 'ready'}
          style={statusStyle}
          text={statusText}
          ply={state.history.length}
          limit={MOVE_LIMIT}
        />
        <div className="ml-play__boardwrap">
          <Board
            owner={state.owner}
            legal={state.legal}
            interactive={humanTurn}
            inert={!humanTurn}
            guides={boardGuides}
            aSide={aSide}
            rotated={rotated}
            plies={state.history.length}
            last={last}
            halo={halo}
            sideLabel={sideLabel}
            onMove={(move) => {
              guides.noteMove(move);
              void game.play(move).catch(() => undefined);
            }}
          />
          {!wide && notice && <div className="ml-play__notice">{notice}</div>}
        </div>
        {guidesOn && !demo && (
          // the slot keeps its place on a small screen while the first analysis runs: the board does not jump
          <div className="ml-play__adv">
            {guides.advantage && <AdvantageBar info={guides.advantage} stale={guides.advantageStale} />}
          </div>
        )}
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
          onApply={(engine, level) => {
            setSheet(false);
            model.onApply(engine, level);
          }}
        />
      )}
    </main>
  );
}
