// EngineSession.netSource: the net engine of the Info page's My-game mode. Always the CPU engine, null where there is none, and it does not
// depend on the variant of the game that is on.
import { afterEach, describe, expect, it } from 'vitest';
import type { Engine, EngineId, EngineInfo, LoadReport } from '../engines/engine';
import { clearEngines, registerEngine } from '../engines/registry';
import type { EnginesManifest } from '../engines/registry';
import { isDefault } from '../game/variant';
import type { Variant } from '../game/variant';
import { EngineSession } from './session';
import { SwitchableEngine } from './switchable-engine';

const file = { path: 'engines/x.wasm', bytes: 10, gzipBytes: 5, sha256: '0'.repeat(64) };
const entry = { files: [file], binaryBytes: 10, binaryGzipBytes: 5 };

const stub = (info: EngineInfo): Engine => ({
  info,
  async init(): Promise<LoadReport> { return { fetchMs: 0, inflateMs: 0, verifyMs: 0, instantiateMs: 0, tableInstallMs: 0, totalMs: 0, tableVerifiedBy: 'engine' }; },
  async newGame() { /* nothing */ },
  async legalMoves() { return []; },
  async choose() { throw new Error('not used'); },
  async analyse() { throw new Error('not used'); },
  async outcome() { return { kind: 'ongoing' as const }; },
  dispose() { /* nothing */ },
});

function register(ids: readonly EngineId[], netPlaysClassicOnly = false): void {
  for (const id of ids) {
    const net = id.startsWith('net-');
    registerEngine({
      id, labelKey: 'engine.cppWasm', runtime: 'wasm', language: net ? 'Java + C++' : 'C++',
      isAvailable: () => true,
      ...(net && netPlaysClassicOnly ? { supportsVariant: (v: Variant) => isDefault(v) } : {}),
      create: (context) => stub(context.info),
    });
  }
}

const manifestOf = (ids: readonly EngineId[]): EnginesManifest => ({ v: 1, version: 't', engines: Object.fromEntries(ids.map((id) => [id, entry])) });

function session(ids: readonly EngineId[], variant?: () => Variant): EngineSession {
  return new EngineSession({ engine: new SwitchableEngine(), baseUrl: '/', loadManifest: async () => manifestOf(ids), nodes: () => 1000, ...(variant ? { variant } : {}) });
}

afterEach(() => clearEngines());

describe('EngineSession.netSource', () => {
  it('is null before start(); afterwards it names net-wasm, never net-webgpu, and creates an engine that is not initialised yet', async () => {
    const ids = ['cpp-wasm', 'net-wasm', 'net-webgpu'] as const;
    register(ids);
    const s = session(ids);
    expect(s.netSource()).toBeNull();
    await s.start();
    const source = s.netSource();
    expect(source?.id).toBe('net-wasm');
    expect(source?.create().info.id).toBe('net-wasm');
  });

  it('is null when the build does not ship net-wasm, also if it ships net-webgpu', async () => {
    const ids = ['cpp-wasm', 'net-webgpu'] as const;
    register(ids);
    const s = session(ids);
    await s.start();
    expect(s.netSource()).toBeNull();
  });

  it('does not depend on the variant of the game that is on: three players on the board still give the net source', async () => {
    const ids = ['cpp-wasm', 'net-wasm'] as const;
    register(ids, true);
    const s = session(ids, () => ({ jump: 'adjacent', players: 3 }));
    await s.start();
    expect(s.getState().choices.find((c) => c.info.id === 'net-wasm')?.available).toBe(false); // the game's own list excludes it
    expect(s.netSource()?.id).toBe('net-wasm');
  });
});
