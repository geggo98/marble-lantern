// The app: owns the game, the engine session, the screen and the flow before a game; the screens only draw.
//
// Flow: a page without a game link asks "Do you want to begin?"; a page opened with a game link (or a link pasted into
// the address bar) asks how to continue. The engine loads in the background while the dialog is open. Once a game runs,
// every full turn is one history entry (game-nav.ts), and Back and Forward load those positions.
import { useEffect, useMemo, useRef, useState, useSyncExternalStore } from 'react';
import { EngineSession } from './session';
import { loadManifest } from './adapters';
import { SwitchableEngine } from './switchable-engine';
import { DEFAULT_SELECTION, NetSelectionStore } from './net-selection';
import { GameUrlSync, browserNavigation, isOwnEntry } from './game-nav';
import type { NavEvent, NavigationPort, UrlSnapshot } from './game-nav';
import { formatGameFragment, parseHash, variantOfLink } from './game-url';
import type { GameLink } from './game-url';
import { GameController } from '../game/game';
import { sideToMove } from '../game/board-state';
import type { Side } from '../game/board-state';
import { controllerSeats, humanSeatOf, humanSideOf, humanVsEngine, isDemo, levelOf, variantOf, withAllEngines, withHumanSeat, withLevel } from '../game/setup';
import { rankingOf, variantKey, withRanking } from '../game/variant';
import type { Player } from '../game/variant';
import { loadChoice, saveChoice, setupOfChoice } from './start-choice';
import { isVariantLink, setupFromLink, snapshotOf, snapshotOfLink } from './link-setup';
import type { GameSetup } from '../game/setup';
import { isNetEngine } from '../engines/engine';
import type { EngineId } from '../engines/engine';
import type { Sims } from '../engines/warnings';
import type { EnginesManifest } from '../engines/registry';
import { Footer, Header } from '../ui/Chrome';
import type { Screen } from '../ui/Chrome';
import { DEFAULT_LEVEL, NODE_LEVELS } from '../ui/Panels';
import { isPresetLevel, nodesOfLevel } from '../skill/level';
import { SkillTracker } from '../skill/tracker';
import { browserLedgerStore } from '../skill/ledger';
import type { LedgerStore } from '../skill/ledger';
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
  /** Tests replace the place where the skill measurements are kept; the default is localStorage. */
  ledgerStore?: LedgerStore;
  /** Tests pass a fake history; the default is the browser's. */
  navigation?: NavigationPort;
  /**
   * Development and test hook: when set, the app starts this game as soon as an engine is ready, without the start dialog.
   * A game link in the address (with `&j=long`, or `&p=3&s=<seats>`) takes precedence. "New game" opens the start dialog.
   */
  initialSetup?: GameSetup;
  /** The selection of the net engines (engine, model, simulations). Tests pass a store with a fake probe; the default keeps it in localStorage. */
  netSelection?: NetSelectionStore;
}

/** What stands before the game. */
type Flow =
  | { kind: 'begin' }
  | { kind: 'link'; link: GameLink }
  | { kind: 'broken'; reason: 'moves' | 'link' }
  | { kind: 'playing' };

interface Plan {
  setup: GameSetup;
  /** Absent for a new game. */
  moves?: readonly number[];
}

function screenFromHash(hash: string): Screen {
  const parsed = parseHash(hash);
  return parsed.kind === 'screen' ? parsed.screen : 'play';
}

/** The controller's options for a setup: who plays each seat, and the variant. */
const controllerOptions = (setup: GameSetup) => ({ seats: controllerSeats(setup), variant: variantOf(setup) });

const variantKeyOf = (setup: GameSetup): string => variantKey(variantOf(setup));

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

/** A link for three players names its seats, so it needs no dialog: the app continues it at once. */
const continuesAtOnce = (link: GameLink): boolean => (link.variant?.players ?? 2) === 3;

export function App({ manifestLoader, benchRunner = null, navigation, ledgerStore, initialSetup, netSelection }: AppProps): React.JSX.Element {
  const nav = useMemo(() => navigation ?? browserNavigation(), [navigation]);
  const [screen, setScreen] = useState<Screen>(() => screenFromHash(nav.hash));
  const [flow, setFlowState] = useState<Flow>(() => {
    const parsed = parseHash(nav.hash);
    if (parsed.kind === 'game' && continuesAtOnce(parsed.link)) return { kind: 'playing' };
    if (initialSetup && parsed.kind !== 'game' && parsed.kind !== 'broken') return { kind: 'playing' };
    return flowFromHash(nav.hash);
  });
  const [setup, setSetupState] = useState<GameSetup>(() => {
    const parsed = parseHash(nav.hash);
    if (parsed.kind === 'game' && isVariantLink(parsed.link)) return setupFromLink(parsed.link, { humanSide: 1, level: DEFAULT_LEVEL });
    return initialSetup ?? humanVsEngine(1, DEFAULT_LEVEL);
  });
  const flowRef = useRef(flow);
  const setupRef = useRef(setup);
  /** The side the human played last; stays while a demo runs, so that "new game" returns to it. */
  const lastHuman = useRef<Side>(humanSideOf(setup) ?? 1);
  const levelRef = useRef(levelOf(setup, DEFAULT_LEVEL));
  /** The last preset level: where "Back to preset level" of the skill card goes back to. */
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
    const netStore = netSelection ?? new NetSelectionStore();
    // The session always starts with a classical engine (never a net engine, defaultChoice): the stored net engine does not play, so it
    // must not warn. Model and simulations stay; the engine in the store follows what is really loaded (the effect below).
    if (isNetEngine(netStore.selection.engine)) netStore.setSelection({ engine: DEFAULT_SELECTION.engine });
    const session = new EngineSession({
      // The model is picked per game: each newGame asks for the model of its variant (resolveModel in engines/net/selection.ts).
      net: () => ({ model: netStore.modelFor, sims: netStore.selection.sims }),
      engine,
      baseUrl,
      loadManifest: manifestLoader ?? (() => loadManifest(baseUrl)),
      nodes: () => nodesOfLevel(levelRef.current),
      variant: () => variantOf(setupRef.current),
    });
    const sync = new GameUrlSync(nav);
    // skill estimate (docs/web-ui.md, "Skill estimate"): measures the human's moves in the background
    const tracker = new SkillTracker({
      engine,
      game,
      humanSide: () => humanSideOf(setupRef.current),
      isReady: () => session.getState().phase === 'ready',
      store: ledgerStore ?? browserLedgerStore(),
    });
    return { engine, game, session, sync, tracker, netStore };
    // eslint-disable-next-line react-hooks/exhaustive-deps -- one instance for the life of the page
  }, []);
  const { engine, game, session, sync, tracker, netStore } = parts;
  const sessionState = useSyncExternalStore(session.subscribe, session.getState);
  // The store names the engine in use, not the one that was wanted last: after a load that ended in another engine (a failed load, a
  // variant that the engine cannot play) the start dialog and the sheet judge what plays.
  const loadedId = sessionState.phase === 'ready' ? sessionState.current?.info.id : undefined;
  useEffect(() => {
    if (loadedId && netStore.selection.engine !== loadedId) netStore.setSelection({ engine: loadedId });
  }, [loadedId, netStore]);

  const snapshot = (): UrlSnapshot => snapshotOf(game.state.history, setupRef.current, lastHuman.current, levelRef.current);

  // A page opened with a game link: the URL shows that game already.
  const adoptedLink = useRef(false);
  if (!adoptedLink.current) {
    adoptedLink.current = true;
    const parsed = parseHash(nav.hash);
    if (parsed.kind === 'game') {
      sync.adopt(snapshotOfLink(parsed.link, { humanSide: 1, level: DEFAULT_LEVEL }));
    }
  }

  /** Loads the position of a history entry that this app wrote (Back, Forward). No dialog: it is the user's own game. */
  const travel = (link: GameLink): void => {
    const fallback = { humanSide: lastHuman.current, level: levelRef.current };
    const next = setupFromLink(link, fallback);
    const level = levelOf(next, levelRef.current);
    const current = game.state.history;
    const same = current.length === link.moves.length && link.moves.every((m, i) => m === current[i]);
    sync.adopt(snapshotOfLink(link, fallback));
    const sameSeats = controllerSeats(next).join() === controllerSeats(setupRef.current).join();
    if (same && sameSeats && level === levelRef.current && variantKeyOf(next) === variantKeyOf(setupRef.current) && !isDemo(setupRef.current)) return;
    applySetup(next);
    engine.setNodes(nodesOfLevel(level));
    void (async () => {
      const switching = session.setVariant(); // the engine in use may not play the variant of that entry
      if (switching && !(await switching)) return;
      await game.loadGame(link.moves, controllerOptions(next));
    })().catch(() => setFlow({ kind: 'broken', reason: 'moves' }));
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
    sync.adopt(snapshotOfLink(parsed.link, { humanSide: 1, level: DEFAULT_LEVEL }));
    if (continuesAtOnce(parsed.link)) {
      startSetup(setupFromLink(parsed.link, { humanSide: lastHuman.current, level: levelRef.current }), parsed.link.moves);
      return;
    }
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
      // The controller decides the ranking rule (Play on, or undo back before the win): the setup follows it.
      if (rankingOf(state.variant) !== rankingOf(variantOf(setupRef.current))) {
        const current = setupRef.current;
        applySetup({ ...current, variant: withRanking(variantOf(current), rankingOf(state.variant)) });
      }
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
        await game.loadGame(p.moves, controllerOptions(p.setup));
      } catch {
        started.current = startedBefore;
        setFlow({ kind: 'broken', reason: 'moves' });
      }
      return;
    }
    await game.newGame(controllerOptions(p.setup));
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
    const parsed = parseHash(nav.hash);
    if (parsed.kind === 'game' && continuesAtOnce(parsed.link)) {
      plan.current = { setup: setupRef.current, moves: parsed.link.moves };
      tracker.startAt(parsed.link.moves.length);
    } else if (initialSetup && parsed.kind !== 'game' && parsed.kind !== 'broken') {
      plan.current = { setup: initialSetup };
    }
    void session.start().then(async (ok) => { if (ok) await afterReady(); });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  /** Starts the game of `next`, new or (with `moves`) continuing a link, once an engine that plays its variant is ready. */
  const startSetup = (next: GameSetup, moves?: readonly number[]): void => {
    if (moves) tracker.startAt(moves.length); // the moves of a link were not necessarily played by this person
    const level = levelOf(next, levelRef.current);
    applySetup(next);
    engine.setNodes(nodesOfLevel(level));
    setFlow({ kind: 'playing' });
    if (moves) {
      // The entry that holds the link becomes an entry of this game, so that Back can return to it without a dialog.
      const landing = snapshotOf(moves, next, lastHuman.current, level);
      sync.adopt(landing);
      nav.replace(formatGameFragment(landing));
    }
    plan.current = moves ? { setup: next, moves } : { setup: next };
    const switching = session.setVariant(); // the engine in use may not play this variant
    if (switching) void switching.then(async (ok) => { if (ok) await afterReady(); });
    else if (session.getState().phase === 'ready') void afterReady();
  };

  /** The dialog is answered: start a new game, or continue the game of a link, on the chosen side. */
  const startGame = (humanSide: Side, moves?: readonly number[], level = levelRef.current, jump = variantOf(setupRef.current).jump): void => {
    startSetup(humanVsEngine(humanSide, level, jump), moves);
  };

  /** Escape, a click beside the dialog or "keep my game": the dialog closes, the running game goes on untouched, the address shows it again. */
  const keepGame = (): void => {
    setFlow({ kind: 'playing' });
    tracker.pump(); // "New game" stopped the analysis of the person's moves; it picks up where it was
    const now = snapshot();
    sync.adopt(now);
    nav.replace(formatGameFragment(now));
  };

  // While a link waits in the dialog, ask the engine whether its moves are legal as soon as the engine is ready.
  useEffect(() => {
    if (flow.kind !== 'link' || sessionState.phase !== 'ready') return;
    let alive = true;
    engine.legalMoves(flow.link.moves, flow.link.variant).catch((e: unknown) => {
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
    netSelection: netStore,
    analyse: (history, moves, nodes, signal) => engine.analyse(history, moves, nodes, signal, variantOf(setupRef.current)),
    evaluate3: (history) => engine.evaluate3(history, variantOf(setupRef.current)),
    analysisId: engine.analysisId,
    humanSide,
    demo,
    level,
    tracker,
    presetLevel: presetLevel.current,
    canShare: playing && !demo,
    getUrl: () => nav.href,
    onNewGame: () => {
      tracker.abort();
      // ask for the game again, like at the start; Escape or "keep my game" returns to the game that runs
      setFlow({ kind: 'begin' });
    },
    onSwap: () => {
      if (!game.state.canSwap) return; // hot-seat: two humans share the board, nothing to swap
      tracker.abort();
      const current = humanSeatOf(setupRef.current);
      const players = variantOf(setupRef.current).players;
      // two players: the other side; three players: the next seat, the board stays
      const next = (current === null ? lastHuman.current : players === 2 ? (current === 1 ? 0 : 1) : (current + 1) % players) as Player;
      applySetup(withHumanSeat(setupRef.current, next, levelRef.current));
      tracker.startAt(game.state.history.length); // the moves so far were not all the person's
      if (playing) sync.observe(snapshot()); // the address shows the new side, same position
      void game.swapSides(next);
    },
    onDemo: () => {
      if (demo && (game.state.thinking || game.isEngineTurn())) {
        game.cancel();
        return;
      }
      const demoSetup = withAllEngines(setupRef.current, levelRef.current);
      applySetup(demoSetup);
      void (async () => {
        await game.newGame(controllerOptions(demoSetup));
        await game.runEngineVsEngine();
      })();
    },
    onPlayOn: () => {
      if (!game.state.canPlayOn) return;
      tracker.abort();
      // the address gets a new entry for the same moves (r=full); the listener above writes it when the controller has switched
      void game.continuePlay().catch(() => undefined);
    },
    onUndo: () => {
      tracker.abort();
      void game.undo();
    },
    onApply: (id: EngineId, nextLevel: number, net?: { modelId?: string | null; sims: Sims }) => {
      void (async () => {
        game.cancel();
        tracker.abort();
        await whenIdle(game);
        applySetup(withLevel(setupRef.current, nextLevel));
        engine.setNodes(nodesOfLevel(nextLevel));
        if (playing && !isDemo(setupRef.current)) sync.observe(snapshot()); // the strength is part of the address
        netStore.setSelection({ engine: id, ...(net ?? {}) });
        if (id !== session.getState().current?.info.id || session.getState().phase !== 'ready') {
          if (await session.select(id)) await afterReady();
        } else if (isNetEngine(id)) {
          // same net engine: new model or simulations, no reload of the engine
          if (await session.applyNet()) await afterReady();
        } else {
          await engine.newGame({ nodes: [nodesOfLevel(nextLevel), nodesOfLevel(nextLevel)], variant: variantOf(setupRef.current) });
          await afterReady();
        }
        tracker.pump();
      })();
    },
    onRetryNet: () => {
      void session.applyNet().then(async (ok) => { if (ok) await afterReady(); });
    },
    onRestart: () => {
      void session.restart().then(async (ok) => { if (ok) await afterReady(); });
    },
    onSelect: (id: EngineId) => {
      netStore.setSelection({ engine: id });
      void session.select(id).then(async (ok) => { if (ok) await afterReady(); });
    },
  };

  let dialogModel: StartDialogModel | null = null;
  if (flow.kind === 'begin') {
    dialogModel = {
      kind: 'begin',
      initial: loadChoice(),
      net: netStore,
      onStart: (choice, humanBegins) => {
        saveChoice(choice);
        startSetup(setupOfChoice(choice, humanBegins, levelRef.current));
      },
    };
  } else if (flow.kind === 'link') {
    const { link } = flow;
    dialogModel = {
      kind: 'link',
      ply: link.moves.length,
      jump: variantOfLink(link).jump,
      variant: variantOfLink(link),
      net: netStore,
      sideToMove: sideToMove(link.moves.length),
      preferred: link.humanSide,
      onContinueAs: (side) => startGame(side, link.moves, link.level, variantOfLink(link).jump),
      onNew: () => {
        // the linked game stays reachable with Back: its entry becomes an entry of this page
        const landing = snapshotOfLink(link, { humanSide: lastHuman.current, level: levelRef.current });
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
      <div className="ml-app" data-screen={screen} {...(showDialog ? { inert: true } : {})}>
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
