// The online adapter: the Engine interface on top of the Hilla endpoint GameEndpoint (app/.../GameEndpoint.java).
// It knows nothing about Hilla: the generated client is injected as a ServerApi (see load.ts), so tests run without it.
import type { AnalysisResult, ChooseResult, Engine, EngineInfo, EngineOutcome, GameSetup, LoadReport, Progress } from '../engine';
import { replay, outcomeOf } from '../../game/board-state';
import { DEFAULT_VARIANT, isDefault } from '../../game/variant';
import type { Variant } from '../../game/variant';

/** What the server lists in engines(): ServerEngine.java. */
export interface ServerEngineEntry {
  id: string;
  labelKey: string;
  backend: 'jvm' | 'jvm-vector' | 'tornadovm' | string;
}

/** The generated Hilla client of GameEndpoint, reduced to what this adapter calls. `init.signal` aborts the request. */
export interface ServerApi {
  engines(init?: { signal?: AbortSignal }): Promise<ServerEngineEntry[] | undefined>;
  legalMoves(history: number[], init?: { signal?: AbortSignal }): Promise<number[] | undefined>;
  choose(engineId: string, history: number[], nodes: number, init?: { signal?: AbortSignal }): Promise<ServerChoice | undefined>;
}

/** ServerChoice.java; the fields of ChooseResult. */
export type ServerChoice = ChooseResult;

/** An error with a code the UI maps to a text (session.ts, toSessionError). */
export class ServerError extends Error {
  readonly code: string;
  constructor(code: string, message: string) {
    super(message);
    this.name = 'ServerError';
    this.code = code;
  }
}

/** Hilla throws ConnectionFailureError (no answer), EndpointResponseError (HTTP error) and EndpointError (the server's message). */
export function toServerError(e: unknown): Error {
  if (e instanceof DOMException && e.name === 'AbortError') return e;
  if (e instanceof Error && e.name === 'AbortError') return e;
  const name = e instanceof Error ? e.name : '';
  const message = e instanceof Error ? e.message : String(e);
  if (name === 'ConnectionFailureError' || e instanceof TypeError) return new ServerError('server-unreachable', message);
  if (message.includes('illegal-history')) return new ServerError('illegal-history', message);
  return new ServerError('internal', message);
}

/** The server plays the default game only (adjacent jumps, two players): every other variant is `unsupported`. */
function requireDefault(variant: Variant | undefined): void {
  if (variant !== undefined && !isDefault(variant)) throw new ServerError('unsupported', `the server engines play adjacent jumps with two players only, not ${variant.jump} with ${variant.players}`);
}

export function isAbort(e: unknown): boolean {
  return e instanceof Error && e.name === 'AbortError';
}

export class ServerEngineAdapter implements Engine {
  readonly info: EngineInfo;
  private readonly serverId: string;
  private readonly api: ServerApi;
  private nodes: [number, number] = [20_000, 20_000];

  constructor(entry: ServerEngineEntry, api: ServerApi) {
    this.serverId = entry.id;
    this.api = api;
    this.info = {
      id: `server:${entry.id}`,
      labelKey: entry.labelKey,
      runtime: 'server',
      language: 'Java',
      version: entry.backend,
      binaryBytes: 0,
      binaryGzipBytes: 0,
    };
  }

  /** No download and no table: one round trip that proves the server answers. */
  async init(onProgress?: (p: Progress) => void): Promise<LoadReport> {
    const t0 = performance.now();
    try {
      const list = await this.api.engines();
      if (!list?.some((e) => e.id === this.serverId)) {
        throw new ServerError('internal', `the server does not offer the engine ${this.serverId}`);
      }
    } catch (e) {
      throw toServerError(e);
    }
    const totalMs = performance.now() - t0;
    onProgress?.({ phase: 'fetch', loaded: 1, total: 1 });
    return { fetchMs: totalMs, inflateMs: 0, verifyMs: 0, instantiateMs: 0, tableInstallMs: 0, totalMs, tableVerifiedBy: 'engine' };
  }

  async newGame(setup: GameSetup): Promise<void> {
    requireDefault(setup.variant);
    this.nodes = [setup.nodes[0] ?? 20_000, setup.nodes[1] ?? setup.nodes[0] ?? 20_000];
  }

  async legalMoves(history: readonly number[], variant?: Variant): Promise<number[]> {
    requireDefault(variant);
    try {
      return (await this.api.legalMoves([...history])) ?? [];
    } catch (e) {
      throw toServerError(e);
    }
  }

  async choose(history: readonly number[], signal?: AbortSignal, variant?: Variant): Promise<ChooseResult> {
    requireDefault(variant);
    // The side to move is fixed by the parity of the history: side 0 starts.
    const nodes = this.nodes[history.length % 2] ?? this.nodes[0];
    try {
      const result = await this.api.choose(this.serverId, [...history], nodes, signal ? { signal } : undefined);
      if (!result) throw new ServerError('internal', 'the server returned no move');
      return result;
    } catch (e) {
      throw toServerError(e);
    }
  }

  /**
   * Not offered by the server endpoint yet: GameEndpoint has no analysis method. It rejects with code "unsupported" so that a
   * caller can hide move guides and skill estimates for this engine.
   */
  async analyse(_history: readonly number[], _moves: readonly number[] | null, _nodes: number, _signal?: AbortSignal, _variant?: Variant): Promise<AnalysisResult> {
    throw new ServerError('unsupported', 'the server engines have no analysis endpoint');
  }

  /** The endpoint has no outcome method: the board rules decide a win and the move limit, the legal moves decide the rest. */
  async outcome(history: readonly number[], variant: Variant = DEFAULT_VARIANT): Promise<EngineOutcome> {
    requireDefault(variant);
    let owner;
    try {
      owner = replay(history);
    } catch (e) {
      throw new ServerError('illegal-history', String(e));
    }
    const ended = outcomeOf(owner, history);
    if (ended?.kind === 'win') return { kind: 'win', winner: ended.winner };
    if (ended) return { kind: 'draw' };
    return (await this.legalMoves(history)).length === 0 ? { kind: 'draw' } : { kind: 'ongoing' };
  }

  /** A request in flight ends with its signal; there is no worker to stop. */
  dispose(): void {}
}
