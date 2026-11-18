// The app: owns the game, the engine session, the screen and the flow before a game; the screens only draw.
//
// Flow: a page without a game link asks "Do you want to begin?"; a page opened with a game link (or a link pasted into
// the address bar) asks how to continue. The engine loads in the background while the dialog is open. Once a game runs,
// every full turn is one history entry (game-nav.ts), and Back and Forward load those positions.
import { useEffect, useMemo, useRef, useState, useSyncExternalStore } from 'react';
import { EngineSession } from './session';
import { loadManifest } from './adapters';
import { SwitchableEngine } from './switchable-engine';
import { GameUrlSync, browserNavigation, isOwnEntry } from './game-nav';
import type { NavEvent, NavigationPort, UrlSnapshot } from './game-nav';
import { formatGameFragment, parseHash } from './game-url';
import type { GameLink } from './game-url';
import { GameController } from '../game/game';
import { sideToMove } from '../game/board-state';
import type { Side } from '../game/board-state';
import { humanSideOf, humanVsEngine, engineVsEngine, isDemo, levelOf, withLevel } from '../game/setup';
import type { GameSetup } from '../game/setup';
import type { EngineId } from '../engines/engine';
import type { EnginesManifest } from '../engines/registry';
import { Footer, Header } from '../ui/Chrome';
import type { Screen } from '../ui/Chrome';
import { DEFAULT_LEVEL, NODE_LEVELS } from '../ui/Panels';
import { isPresetLevel, nodesOfLevel } from '../skill/level';
import { SkillTracker } from '../skill/tracker';
import { Sprite } from '../ui/primitives';
import { AboutScreen, BenchScreen } from '../ui/Screens';
import type { BenchRunner } from '../ui/Screens';
import { StartDialog } from '../ui/StartDialog';
import type { StartDialogModel } from '../ui/StartDialog';
import { PlayScreen } from './PlayScreen';
import type { PlayModel } from './PlayScreen';

export interface AppProps {
  /** Tests replace the manifest loader; the default registers the adapters and fetches engines.json. */
  manifestLoader?: () => Promise<EnginesManifest>;
  /** The benchmark (WP10). Without it the screen says that the benchmark is not part of this build. */
  benchRunner?: BenchRunner | null;
  /** Tests pass a fake history; the default is the browser's. */
  navigation?: NavigationPort;
}

/** What stands before the game. */
type Flow =
  | { kind: 'begin' }
  | { kind: 'link'; link: GameLink }
  | { kind: 'broken'; reason: 'moves' | 'link' }
  | { kind: 'playing' };

interface Plan {
  humanSide: Side;
  /** Absent for a new game. */
  moves?: readonly number[];
}

function screenFromHash(hash: string): Screen {
  const parsed = parseHash(hash);
  return parsed.kind === 'screen' ? parsed.screen : 'play';
}

function flowFromHash(hash: string): Flow {
  const parsed = parseHash(hash);
  if (parsed.kind === 'game') return { kind: 'link', link: parsed.link };
  if (parsed.kind === 'broken') return { kind: 'broken', reason: 'link' };
  return { kind: 'begin' };
}

/** Resolves when no engine search runs; the controller clears `thinking` before it frees itself. */
function whenIdle(game: GameController): Promise<void> {
  if (!game.state.thinking) return Promise.resolve();
  return new Promise((resolve) => {
    const off = game.subscribe((s) => {
      if (!s.thinking) {
        off();
        resolve();
      }
    });
  });
}

export function App({ manifestLoader, benchRunner = null, navigation }: AppProps): React.JSX.Element {
  const nav = useMemo(() => navigation ?? browserNavigation(), [navigation]);
  const [screen, setScreen] = useState<Screen>(() => screenFromHash(nav.hash));
  const [flow, setFlowState] = useState<Flow>(() => flowFromHash(nav.hash));
  const [setup, setSetupState] = useState<GameSetup>(() => humanVsEngine(1, DEFAULT_LEVEL));
  const flowRef = useRef(flow);
  const setupRef = useRef(setup);
  /** The side the human played last; stays while a demo runs, so that "new game" returns to it. */
  const lastHuman = useRef<Side>(1);
  const levelRef = useRef(levelOf(setup, DEFAULT_LEVEL));
  /** The last preset level: where "Reset to level" of the skill card goes back to. */
  const presetLevel = useRef(DEFAULT_LEVEL);
  const started = useRef(false);
  const booted = useRef(false);
  const plan = useRef<Plan | null>(null);

  const setFlow = (next: Flow): void => {
    flowRef.current = next;
    setFlowState(next);
  };
  const applySetup = (next: GameSetup): void => {
    setupRef.current = next;
    levelRef.current = levelOf(next, DEFAULT_LEVEL);
    if (isPresetLevel(levelRef.current)) presetLevel.current = levelRef.current;
    const human = humanSideOf(next);
    if (human !== null) lastHuman.current = human;
    setSetupState(next);
  };

  const parts = useMemo(() => {
    const engine = new SwitchableEngine();
    const nodes = NODE_LEVELS[DEFAULT_LEVEL];
    engine.setNodes(nodes);
    const game = new GameController({ engine, mode: 'human-vs-engine', humanSide: 1, nodes: [nodes, nodes] });
    const baseUrl = import.meta.env.BASE_URL;
    const session = new EngineSession({
      engine,
      baseUrl,
      loadManifest: manifestLoader ?? (() => loadManifest(baseUrl)),
      nodes: () => nodesOfLevel(levelRef.current),
    });
    const sync = new GameUrlSync(nav);
    // skill estimate (docs/web-ui.md, "Skill estimate"): measures the human's moves in the background
    const tracker = new SkillTracker({
      engine,
      game,
      humanSide: () => humanSideOf(setupRef.current),
      isReady: () => session.getState().phase === 'ready',
    });
    return { engine, game, session, sync, tracker };
    // eslint-disable-next-line react-hooks/exhaustive-deps -- one instance for the life of the page
  }, []);
  const { engine, game, session, sync, tracker } = parts;
  const sessionState = useSyncExternalStore(session.subscribe, session.getState);

  const snapshot = (): UrlSnapshot => ({ moves: game.state.history, humanSide: lastHuman.current, level: levelRef.current });

  // A page opened with a game link: the URL shows that game already.
  const adoptedLink = useRef(false);
  if (!adoptedLink.current) {
    adoptedLink.current = true;
    const parsed = parseHash(nav.hash);
    if (parsed.kind === 'game') {
      sync.adopt({ moves: parsed.link.moves, humanSide: parsed.link.humanSide ?? 1, level: parsed.link.level ?? DEFAULT_LEVEL });
    }
  }

  /** Loads the position of a history entry that this app wrote (Back, Forward). No dialog: it is the user's own game. */
  const travel = (link: GameLink): void => {
    const humanSide = link.humanSide ?? lastHuman.current;
    const level = link.level ?? levelRef.current;
    const current = game.state.history;
    const same = current.length === link.moves.length && link.moves.every((m, i) => m === current[i]);
    sync.adopt({ moves: link.moves, humanSide, level });
    if (same && humanSide === lastHuman.current && level === levelRef.current && !isDemo(setupRef.current)) return;
    applySetup(humanVsEngine(humanSide, level));
    engine.setNodes(nodesOfLevel(level));
    void game.loadGame(link.moves, { humanSide }).catch(() => setFlow({ kind: 'broken', reason: 'moves' }));
  };

  const onNav = (event: NavEvent): void => {
    const parsed = parseHash(event.hash);
    if (parsed.kind === 'screen') {
      setScreen(parsed.screen);
      if (parsed.screen === 'play' && flowRef.current.kind === 'playing' && !isDemo(setupRef.current)) {
        nav.replace(formatGameFragment(snapshot())); // the way back from another screen: the address shows the game again
      }
      return;
    }
    setScreen('play');
    if (parsed.kind === 'broken') {
      setFlow({ kind: 'broken', reason: 'link' });
      return;
    }
    if (flowRef.current.kind === 'playing' && isOwnEntry(event.state)) {
      travel(parsed.link); // Back or Forward through the entries of this page
      return;
    }
    // a pasted link, or the first state of a page that waits for its dialog
    sync.adopt({ moves: parsed.link.moves, humanSide: parsed.link.humanSide ?? 1, level: parsed.link.level ?? DEFAULT_LEVEL });
    setFlow({ kind: 'link', link: parsed.link });
  };
  const onNavRef = useRef(onNav);
  onNavRef.current = onNav;

  useEffect(() => nav.subscribe((event) => onNavRef.current(event)), [nav]);

  useEffect(() => {
    const onHash = (): void => setScreen(screenFromHash(nav.hash));
    window.addEventListener('hashchange', onHash);
    return () => window.removeEventListener('hashchange', onHash);
  }, [nav]);

  // The skill tracker follows the game; an engine that is loading, failing or replaced drops its running analysis.
  useEffect(() => {
    const detach = tracker.attach();
    let engineId = session.getState().current?.info.id;
    const off = session.subscribe(() => {
      const s = session.getState();
      if (s.phase !== 'ready') tracker.abort();
      if (s.current?.info.id !== engineId) {
        engineId = s.current?.info.id;
        tracker.reset(); // another engine may support analysis
      }
      tracker.pump();
    });
    return () => {
      detach();
      off();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // One history entry per turn: write the position to the URL whenever the history of the game changes.
  useEffect(() => {
    let seen = game.state.history;
    return game.subscribe((state) => {
      if (state.history === seen) return; // a patch (thinking, figures), not a move
      seen = state.history;
      if (flowRef.current.kind !== 'playing' || isDemo(setupRef.current)) return;
      sync.observe(snapshot());
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const runPlan = async (p: Plan): Promise<void> => {
    const startedBefore = started.current;
    started.current = true;
    if (p.moves) {
      try {
        await game.loadGame(p.moves, { humanSide: p.humanSide });
      } catch {
        started.current = startedBefore;
        setFlow({ kind: 'broken', reason: 'moves' });
      }
      return;
    }
    await game.newGame({ mode: 'human-vs-engine', humanSide: p.humanSide });
  };

  /** After an engine is ready: run the plan the dialog made, or let the engine continue a game that waits for it. */
  const afterReady = async (): Promise<void> => {
    if (plan.current) {
      const next = plan.current;
      plan.current = null;
      await runPlan(next);
      return;
    }
    if (!started.current) return; // the dialog is still open
    await whenIdle(game);
    if (game.state.outcome || !game.isEngineTurn()) return;
    await game.stepEngine();
  };

  useEffect(() => {
    if (booted.current) return;
    booted.current = true;
    void session.start().then(async (ok) => { if (ok) await afterReady(); });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  /** The dialog is answered: start a new game, or continue the game of a link, on the chosen side. */
  const startGame = (humanSide: Side, moves?: readonly number[], level = levelRef.current): void => {
    applySetup(humanVsEngine(humanSide, level));
    engine.setNodes(nodesOfLevel(level));
    setFlow({ kind: 'playing' });
    if (moves) {
      // The entry that holds the link becomes an entry of this game, so that Back can return to it without a dialog.
      const landing = { moves, humanSide, level };
      sync.adopt(landing);
      nav.replace(formatGameFragment(landing));
    }
    plan.current = moves ? { humanSide, moves } : { humanSide };
    if (session.getState().phase === 'ready') void afterReady();
  };

  /** Escape or "keep my game": the dialog closes and the address shows the running game again. */
  const keepGame = (): void => {
    setFlow({ kind: 'playing' });
    const now = snapshot();
    sync.adopt(now);
    nav.replace(formatGameFragment(now));
  };

  // While a link waits in the dialog, ask the engine whether its moves are legal as soon as the engine is ready.
  useEffect(() => {
    if (flow.kind !== 'link' || sessionState.phase !== 'ready') return;
    let alive = true;
    engine.legalMoves(flow.link.moves).catch((e: unknown) => {
      if (alive && (e as { code?: unknown } | null)?.code === 'illegal-history') setFlow({ kind: 'broken', reason: 'moves' });
    });
    return () => {
      alive = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [flow, sessionState.phase]);

  const humanSide = humanSideOf(setup) ?? lastHuman.current;
  const demo = isDemo(setup);
  const level = levelOf(setup, DEFAULT_LEVEL);
  const playing = flow.kind === 'playing';

  const model: PlayModel = {
    game,
    session,
    humanSide,
    demo,
    level,
    tracker,
    presetLevel: presetLevel.current,
    canShare: playing && !demo,
    getUrl: () => nav.href,
    onNewGame: () => {
      tracker.abort();
      applySetup(humanVsEngine(lastHuman.current, levelRef.current));
      void game.newGame({ mode: 'human-vs-engine', humanSide: lastHuman.current });
    },
    onSwap: () => {
      tracker.abort();
      const next: Side = lastHuman.current === 1 ? 0 : 1;
      applySetup(humanVsEngine(next, levelRef.current));
      void game.newGame({ mode: 'human-vs-engine', humanSide: next });
    },
    onDemo: () => {
      if (demo && (game.state.thinking || game.isEngineTurn())) {
        game.cancel();
        return;
      }
      applySetup(engineVsEngine(levelRef.current));
      void (async () => {
        await game.newGame({ mode: 'engine-vs-engine' });
        await game.runEngineVsEngine();
      })();
    },
    onUndo: () => {
      tracker.abort();
      void game.undo();
    },
    onApply: (id: EngineId, nextLevel: number) => {
      void (async () => {
        game.cancel();
        tracker.abort();
        await whenIdle(game);
        applySetup(withLevel(setupRef.current, nextLevel));
        engine.setNodes(nodesOfLevel(nextLevel));
        if (playing && !isDemo(setupRef.current)) sync.observe(snapshot()); // the strength is part of the address
        if (id !== session.getState().current?.info.id || session.getState().phase !== 'ready') {
          if (await session.select(id)) await afterReady();
        } else {
          await engine.newGame({ nodes: [nodesOfLevel(nextLevel), nodesOfLevel(nextLevel)] });
          await afterReady();
        }
        tracker.pump();
      })();
    },
    onRestart: () => {
      void session.restart().then(async (ok) => { if (ok) await afterReady(); });
    },
    onSelect: (id: EngineId) => {
      void session.select(id).then(async (ok) => { if (ok) await afterReady(); });
    },
  };

  let dialogModel: StartDialogModel | null = null;
  if (flow.kind === 'begin') {
    dialogModel = { kind: 'begin', onChoose: (humanBegins) => startGame(humanBegins ? 0 : 1) };
  } else if (flow.kind === 'link') {
    const { link } = flow;
    dialogModel = {
      kind: 'link',
      ply: link.moves.length,
      sideToMove: sideToMove(link.moves.length),
      preferred: link.humanSide,
      onContinueAs: (side) => startGame(side, link.moves, link.level),
      onNew: () => {
        // the linked game stays reachable with Back: its entry becomes an entry of this page
        const landing = { moves: link.moves, humanSide: link.humanSide ?? lastHuman.current, level: link.level ?? levelRef.current };
        sync.adopt(landing);
        nav.replace(formatGameFragment(landing));
        setFlow({ kind: 'begin' });
      },
    };
  } else if (flow.kind === 'broken') {
    dialogModel = { kind: 'broken', reason: flow.reason, onNew: () => setFlow({ kind: 'begin' }) };
  }
  const showDialog = screen === 'play' && dialogModel !== null;

  const online = __ONLINE__;
  return (
    <>
      <div className="ml-app" {...(showDialog ? { inert: true } : {})}>
        <Sprite />
        <Header screen={screen} online={online} />
        {screen === 'play' && <PlayScreen model={model} />}
        {screen === 'bench' && <BenchScreen runner={benchRunner} />}
        {screen === 'about' && <AboutScreen />}
        <Footer />
      </div>
      {showDialog && dialogModel && <StartDialog model={dialogModel} onCancel={started.current ? keepGame : undefined} />}
    </>
  );
}
