// The app: owns the game, the engine session and the screen; the screens only draw.
import { useEffect, useMemo, useRef, useState } from 'react';
import { EngineSession } from './session';
import { loadManifest } from './adapters';
import { SwitchableEngine } from './switchable-engine';
import { GameController } from '../game/game';
import type { Side } from '../game/board-state';
import type { EngineId } from '../engines/engine';
import type { EnginesManifest } from '../engines/registry';
import { Footer, Header } from '../ui/Chrome';
import type { Screen } from '../ui/Chrome';
import { DEFAULT_LEVEL, NODE_LEVELS } from '../ui/Panels';
import { Sprite } from '../ui/primitives';
import { AboutScreen, BenchScreen } from '../ui/Screens';
import type { BenchRunner } from '../ui/Screens';
import { PlayScreen } from './PlayScreen';
import type { PlayModel } from './PlayScreen';

export interface AppProps {
  /** Tests replace the manifest loader; the default registers the adapters and fetches engines.json. */
  manifestLoader?: () => Promise<EnginesManifest>;
  /** The benchmark (WP10). Without it the screen says that the benchmark is not part of this build. */
  benchRunner?: BenchRunner | null;
}

function screenFromHash(hash: string): Screen {
  const name = hash.replace(/^#\/?/, '');
  return name === 'bench' || name === 'about' ? name : 'play';
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

export function App({ manifestLoader, benchRunner = null }: AppProps): React.JSX.Element {
  const [screen, setScreen] = useState<Screen>(() => screenFromHash(globalThis.location?.hash ?? ''));
  const [humanSide, setHumanSide] = useState<Side>(1);
  const [demo, setDemo] = useState(false);
  const [level, setLevel] = useState(DEFAULT_LEVEL);
  const humanSideRef = useRef(humanSide);
  humanSideRef.current = humanSide;
  const levelRef = useRef(level);
  levelRef.current = level;
  const started = useRef(false);
  const booted = useRef(false);

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
      nodes: () => NODE_LEVELS[levelRef.current] ?? nodes,
    });
    return { engine, game, session };
    // eslint-disable-next-line react-hooks/exhaustive-deps -- one instance for the life of the page
  }, []);
  const { engine, game, session } = parts;

  useEffect(() => {
    const onHash = (): void => setScreen(screenFromHash(location.hash));
    window.addEventListener('hashchange', onHash);
    return () => window.removeEventListener('hashchange', onHash);
  }, []);

  /** After an engine is ready: start the first game, or let the engine continue a game that waits for it. */
  const afterReady = async (): Promise<void> => {
    if (!started.current) {
      started.current = true;
      await game.newGame({ mode: 'human-vs-engine', humanSide: humanSideRef.current });
      return;
    }
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

  const model: PlayModel = {
    game,
    session,
    humanSide,
    demo,
    level,
    onNewGame: () => {
      setDemo(false);
      void game.newGame({ mode: 'human-vs-engine', humanSide });
    },
    onSwap: () => {
      const next: Side = humanSide === 1 ? 0 : 1;
      setHumanSide(next);
      setDemo(false);
      void game.newGame({ mode: 'human-vs-engine', humanSide: next });
    },
    onDemo: () => {
      if (demo && (game.state.thinking || game.isEngineTurn())) {
        game.cancel();
        return;
      }
      setDemo(true);
      void (async () => {
        await game.newGame({ mode: 'engine-vs-engine' });
        await game.runEngineVsEngine();
      })();
    },
    onUndo: () => void game.undo(),
    onApply: (id: EngineId, nextLevel: number) => {
      void (async () => {
        game.cancel();
        await whenIdle(game);
        setLevel(nextLevel);
        levelRef.current = nextLevel;
        engine.setNodes(NODE_LEVELS[nextLevel] ?? NODE_LEVELS[DEFAULT_LEVEL]);
        if (id !== session.getState().current?.info.id || session.getState().phase !== 'ready') {
          if (await session.select(id)) await afterReady();
        } else {
          await engine.newGame({ nodes: [NODE_LEVELS[nextLevel] ?? 0, NODE_LEVELS[nextLevel] ?? 0] });
          await afterReady();
        }
      })();
    },
    onRestart: () => {
      void session.restart().then(async (ok) => { if (ok) await afterReady(); });
    },
    onSelect: (id: EngineId) => {
      void session.select(id).then(async (ok) => { if (ok) await afterReady(); });
    },
  };

  const online = __ONLINE__;
  return (
    <div className="ml-app">
      <Sprite />
      <Header screen={screen} online={online} />
      {screen === 'play' && <PlayScreen model={model} />}
      {screen === 'bench' && <BenchScreen runner={benchRunner} />}
      {screen === 'about' && <AboutScreen />}
      <Footer />
    </div>
  );
}
