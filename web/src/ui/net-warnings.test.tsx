// @vitest-environment jsdom
// The messages of the net warnings: one per code, with the action where there is one, the dedupe of fallback-random, the notice of the
// play screen, the engine card of a net engine and the start dialog.
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import type { Root } from 'react-dom/client';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { NetSelectionStore } from '../app/net-selection';
import type { EngineInfo } from '../engines/engine';
import type { EngineWarning } from '../engines/warnings';
import { StartDialog } from './StartDialog';
import { EngineCard, Telemetry } from './Panels';
import { NetNotice, NetWarnings, describeWarnings, variantText } from './net-warnings';
import { translate } from './texts';

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

let root: Root | null = null;
afterEach(() => {
  act(() => root?.unmount());
  root = null;
  document.body.innerHTML = '';
});
async function render(node: React.JSX.Element): Promise<void> {
  const host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
  await act(async () => {
    root!.render(node);
  });
}
const q = (sel: string): HTMLElement | null => document.body.querySelector<HTMLElement>(sel);
const V2 = { jump: 'adjacent', players: 2 } as const;

describe('variantText', () => {
  it('names the players with a catalog text, never a bare number after the jump rule (L6)', () => {
    expect(variantText({ jump: 'adjacent', players: 3 }, translate)).toBe(`${translate('start_jump_adjacent')} · ${translate('net.variant.players', { players: '3' })}`);
    expect(variantText(undefined, translate)).toBe('');
  });
});

describe('describeWarnings', () => {
  const t = translate;
  it('maps each code to a text and an action', () => {
    const w: EngineWarning[] = [
      { code: 'no-model', variant: V2 },
      { code: 'model-variant-mismatch', modelId: 'small-shared-all', variant: V2 },
      { code: 'backend-unavailable', backend: 'webgpu', fallback: 'wasm', reason: 'r1' },
      { code: 'backend-unavailable', backend: 'webgpu', fallback: 'random', reason: 'r2' },
      { code: 'model-load-failed', modelId: 'small-shared-all', reason: 'r3' },
    ];
    const d = w.map((x) => describeWarnings([x], t)[0]!);
    expect(d.map((x) => x.action)).toEqual(['choose-model', 'choose-other', undefined, undefined, 'retry']);
    expect(new Set(d.map((x) => x.text)).size).toBe(5); // five different texts: the two backend texts differ by fallback
    expect(d[2]!.text).toBe(t('net.warn.backend-cpu', { model: '', variant: '', reason: 'r1' }));
    expect(d[3]!.text).toBe(t('net.warn.backend-random', { model: '', variant: '', reason: 'r2' }));
    expect(d[2]!.text).toContain('r1');
    expect(d[3]!.text).toContain('r2');
    expect(d[4]!.text).toContain('r3');
  });

  it('names the model and the variant in the mismatch text', () => {
    const [d] = describeWarnings([{ code: 'model-variant-mismatch', modelId: 'small-shared-all', variant: V2 }], t);
    expect(d!.text).toContain(t('model.small-shared-all.label'));
    expect(d!.text).toContain('2');
  });

  it('shows fallback-random alone, but not next to its cause', () => {
    expect(describeWarnings([{ code: 'fallback-random' }], t).map((x) => x.code)).toEqual(['fallback-random']);
    expect(describeWarnings([{ code: 'no-model' }, { code: 'fallback-random' }], t).map((x) => x.code)).toEqual(['no-model']);
    expect(describeWarnings([], t)).toEqual([]);
  });
});

describe('NetWarnings', () => {
  it('renders an icon and a text for every item (never colour alone) and calls the handler of the button', async () => {
    const onChoose = vi.fn();
    await render(<NetWarnings warnings={[{ code: 'no-model' }, { code: 'fallback-random' }]} onAction={{ 'choose-model': onChoose }} />);
    const item = q('[data-net-warning="no-model"]')!;
    expect(item.querySelector('svg')).not.toBeNull();
    expect(item.querySelector('.ml-netwarn__text')!.textContent!.length).toBeGreaterThan(0);
    await act(async () => item.querySelector<HTMLElement>('[data-warning-action]')!.click());
    expect(onChoose).toHaveBeenCalledOnce();
  });

  it('shows no button for an action without handler, and retry for a failed load', async () => {
    const onRetry = vi.fn();
    await render(<NetWarnings warnings={[{ code: 'no-model' }, { code: 'model-load-failed', reason: 'x' }]} onAction={{ retry: onRetry }} />);
    expect(q('[data-net-warning="no-model"] button')).toBeNull();
    await act(async () => q('[data-warning-action="retry"]')!.click());
    expect(onRetry).toHaveBeenCalledOnce();
  });

  it('renders nothing for an empty list', async () => {
    await render(<NetWarnings warnings={[]} />);
    expect(q('[data-net-warning-list]')).toBeNull();
  });
});

describe('NetNotice (play screen)', () => {
  it('is a polite status region with a name, empty while all is well and for a classical engine', async () => {
    await render(<NetNotice netEngine warnings={[]} onOpen={() => undefined} />);
    const el = q('.ml-play__netnote')!;
    expect(el.getAttribute('role')).toBe('status');
    expect(el.getAttribute('aria-live')).toBe('polite');
    expect(el.getAttribute('aria-label')!.length).toBeGreaterThan(0);
    expect(el.getAttribute('data-net-notice')).toBe('none');
    expect(el.hasAttribute('data-slot')).toBe(true);
    expect(q('.ml-netnote')).toBeNull();
  });

  it('keeps the region but reserves no slot for a classical engine, and ignores its warnings', async () => {
    await render(<NetNotice netEngine={false} warnings={[{ code: 'fallback-random' }]} onOpen={() => undefined} />);
    expect(q('.ml-play__netnote')!.hasAttribute('data-slot')).toBe(false);
    expect(q('.ml-netnote')).toBeNull();
  });

  it.each([
    ['fallback-random', 'random'],
    ['backend-unavailable', 'cpu'],
  ] as const)('%s gives the %s notice with an open button; the same element, so nothing moves', async (code, kind) => {
    const onOpen = vi.fn();
    await render(<NetNotice netEngine warnings={[{ code }]} onOpen={onOpen} />);
    expect(q('.ml-play__netnote')!.getAttribute('data-net-notice')).toBe(kind);
    expect(q('.ml-play__netnote')!.hasAttribute('data-slot')).toBe(true);
    await act(async () => q('[data-net-notice-open]')!.click());
    expect(onOpen).toHaveBeenCalledOnce();
  });

  it('other warnings (no-model alone, model-load-failed) show no notice', async () => {
    await render(<NetNotice netEngine warnings={[{ code: 'no-model' }, { code: 'model-load-failed' }]} onOpen={() => undefined} />);
    expect(q('.ml-netnote')).toBeNull();
  });
});

describe('engine card and telemetry of a net engine', () => {
  const info = (id: string): EngineInfo => ({ id, labelKey: 'engine.netWasm', runtime: 'wasm', language: 'C++', version: 'v', binaryBytes: 1, binaryGzipBytes: 1 }) as EngineInfo;

  it('shows simulations and backend instead of the node line, plus the warnings with their buttons', async () => {
    const onChoose = vi.fn();
    await render(
      <EngineCard info={info('net-wasm')} level={2} onChange={() => undefined} warnings={[{ code: 'no-model' }, { code: 'fallback-random' }]} net={{ sims: 200, backend: 'random' }} onWarningAction={{ 'choose-model': onChoose }} />,
    );
    expect(q('[data-net-level]')!.textContent).toContain('200');
    expect(q('.ml-engine__level:not([data-net-level])')).toBeNull();
    await act(async () => q('[data-warning-action="choose-model"]')!.click());
    expect(onChoose).toHaveBeenCalled();
  });

  it('a net move shows simulations, time and backend, and neither nodes, depth, source nor the score', async () => {
    const r = { move: 1, score: 0.4321, depth: 7, nodes: 12345, ms: 15.4, raceMove: false, rows1Requests: 0, net: { backend: 'wasm', sims: 50, batches: 3 } };
    await render(<Telemetry result={r as never} stale={false} />);
    const text = q('.ml-telemetry')!.textContent!;
    expect(q('.ml-telemetry')!.querySelectorAll('.ml-telemetry__cell')).toHaveLength(3);
    expect(text).toContain('50');
    expect(text).not.toContain('12');
    expect(text).not.toContain('0.43');
    expect(q('.ml-telemetry')!.getAttribute('data-net-backend')).toBe('wasm');
  });

  it('a classical move keeps its four cells', async () => {
    const r = { move: 1, score: 0, depth: 7, nodes: 12345, ms: 15, raceMove: false, rows1Requests: 0 };
    await render(<Telemetry result={r as never} stale={false} />);
    expect(q('.ml-telemetry')!.querySelectorAll('.ml-telemetry__cell')).toHaveLength(4);
  });
});

describe('start dialog', () => {
  const dialog = async (selection: { engine: 'net-wasm' | 'cpp-wasm'; modelId: string | null }, initial = { jump: 'adjacent', mode: 'two', humanSeat: 0, engineSeat: 2 } as const): Promise<void> => {
    const net = new NetSelectionStore({ initial: { ...selection, sims: 200 }, storage: null, wasm: true });
    await render(<StartDialog model={{ kind: 'begin', initial, onStart: () => undefined, net }} />);
  };
  const warns = (): string[] => Array.from(document.body.querySelectorAll('[data-net-warning]')).map((e) => e.getAttribute('data-net-warning')!);

  it('a net engine without model: the warning stands above the buttons, and starting is still possible', async () => {
    await dialog({ engine: 'net-wasm', modelId: null });
    expect(warns()).toEqual(['no-model']);
    const warning = q('[data-net-warning-live]')!;
    const actions = q('.ml-start__actions')!;
    expect(warning.compareDocumentPosition(actions) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect((q('[data-choice="begin-yes"]') as HTMLButtonElement).disabled).toBe(false);
  });

  it('the expert model for a 2-player game: no warning; switching to the three-player mode gives the mismatch', async () => {
    await dialog({ engine: 'net-wasm', modelId: 'small-2p-adjacent-expert' });
    expect(warns()).toEqual([]);
    await act(async () => q('[data-choice="mode-hotseat"]')!.click());
    expect(warns()).toEqual(['model-variant-mismatch']);
  });

  it('the long jump rule also mismatches the expert model', async () => {
    await dialog({ engine: 'net-wasm', modelId: 'small-2p-adjacent-expert' });
    await act(async () => q('[data-choice="jump-long"]')!.click());
    expect(warns()).toEqual(['model-variant-mismatch']);
  });

  it('a classical engine selected: no warning; no store: no warning', async () => {
    await dialog({ engine: 'cpp-wasm', modelId: null });
    expect(warns()).toEqual([]);
    act(() => root?.unmount());
    document.body.innerHTML = '';
    await render(<StartDialog model={{ kind: 'begin', initial: { jump: 'adjacent', mode: 'two', humanSeat: 0, engineSeat: 2 }, onStart: () => undefined }} />);
    expect(warns()).toEqual([]);
  });
});
