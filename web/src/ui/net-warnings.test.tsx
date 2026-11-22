// @vitest-environment jsdom
// The messages of the net warnings: one per code, with the action where there is one, the dedupe of fallback-random, the notice of the
// play screen, the engine card of a net engine and the start dialog.
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import type { Root } from 'react-dom/client';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { NetSelectionStore } from '../app/net-selection';
import { listModels } from '../engines/net/model-manifest';
import type { ModelInfo } from '../engines/net/model-manifest';
import type { EngineInfo } from '../engines/engine';
import type { EngineWarning } from '../engines/warnings';
import { StartDialog } from './StartDialog';
import { EngineCard, Telemetry } from './Panels';
import type { Variant } from '../game/variant';
import { NetNotice, NetWarnings, describeWarnings, netNoticeKind, variantText } from './net-warnings';
import de from '../i18n/de.json';
import en from '../i18n/en.json';
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

  it('names 5-row houses (de and en) and leaves 4 rows unmarked, so a notice never reads as one about the 4-row game', () => {
    const four = variantText({ jump: 'adjacent', players: 2 }, translate);
    const five = variantText({ jump: 'adjacent', players: 2, rows: 5 }, translate);
    expect(four).not.toMatch(/row|Reihen/i);
    expect(five).toBe(`${four} · ${translate('net.variant.rows5')}`);
    expect(five).toContain('5-row houses');
    expect(de['net.variant.rows5']).toBe('Häuser mit 5 Reihen'); // the German catalog has both new texts
    expect(de['net.warn.no-model-rows']).toContain('5 Reihen');
    expect(variantText({ jump: 'long', players: 3, rows: 5, camps: 'open' }, translate)).toMatch(/3 players.*5-row houses$/);
  });

  it('a mismatch notice on 5 rows names the size', () => {
    const d = describeWarnings([{ code: 'model-variant-mismatch', modelId: 'small-shared-all', variant: { jump: 'adjacent', players: 2, rows: 5 } }], translate)[0]!;
    expect(d.text).toContain('5-row houses');
  });
});

describe('no-model on 5 rows', () => {
  const FIVE: Variant = { jump: 'long', players: 3, rows: 5 };
  it('a 5-row variant without a net: says so and offers no button; on 4 rows and for the 5-row classic game the button stays', async () => {
    const [classic5] = describeWarnings([{ code: 'no-model', variant: { jump: 'adjacent', players: 2, rows: 5 } }], translate);
    expect(classic5!.action).toBe('choose-model');
    const [five] = describeWarnings([{ code: 'no-model', variant: FIVE }], translate);
    expect(five!.action).toBeUndefined();
    expect(five!.text).toBe(translate('net.warn.no-model-rows', { variant: variantText(FIVE, translate) }));
    expect(five!.text).toContain('No net has been trained for this variant on 5 rows');
    expect(five!.text).not.toContain('No model is selected');
    const [four] = describeWarnings([{ code: 'no-model', variant: V2 }], translate);
    expect(four!.action).toBe('choose-model');
    expect(four!.text).toContain('No model is selected');
    await render(<NetWarnings warnings={[{ code: 'no-model', variant: FIVE }, { code: 'fallback-random' }]} onAction={{ 'choose-model': () => undefined }} />);
    expect(q('[data-net-warning="no-model"]')).not.toBeNull();
    expect(q('[data-warning-action="choose-model"]')).toBeNull();
  });
});

describe('describeWarnings', () => {
  const t = translate;
  it('maps each code to a text and an action', () => {
    const w: EngineWarning[] = [
      { code: 'no-model', variant: V2 },
      { code: 'model-variant-mismatch', modelId: 'small-shared-all', variant: V2 },
      { code: 'backend-unavailable', backend: 'webgpu', fallback: 'wasm', reason: 'webgpu-no-adapter', detail: 'requestAdapter() returned null' },
      { code: 'backend-unavailable', backend: 'webgpu', fallback: 'random', reason: 'webgpu-lost', detail: 'GPU reset' },
      { code: 'model-load-failed', modelId: 'small-shared-all', reason: 'verify-failed', detail: 'sha256 differs' },
    ];
    const d = w.map((x) => describeWarnings([x], t)[0]!);
    expect(d.map((x) => x.action)).toEqual(['choose-model', 'choose-other', undefined, undefined, 'retry']);
    expect(new Set(d.map((x) => x.text)).size).toBe(5); // five different texts: the two backend texts differ by fallback
    const english = (k: keyof typeof en): string => en[k] as string; // the texts of the catalog, without placeholders
    expect(d[2]!.text).toContain(english('net.warn.backend-cpu').split('{')[0]!.trim());
    expect(d[3]!.text).toContain(english('net.warn.backend-random').split('{')[0]!.trim());
    expect(d[2]!.text).toContain(english('net.reason.webgpu-no-adapter'));
    expect(d[3]!.text).toContain(english('net.reason.webgpu-lost'));
    expect(d[4]!.text).toContain(english('net.reason.verify-failed'));
    // the English technical text is data of the collapsed details, never part of the message
    expect(d.map((x) => x.detail)).toEqual([undefined, undefined, 'requestAdapter() returned null', 'GPU reset', 'sha256 differs']);
    for (const x of d) expect(x.text).not.toMatch(/requestAdapter|GPU reset|sha256 differs/);
  });

  it.each(['no-model-id', 'variant-not-supported', 'webassembly-unavailable', 'webgpu-no-api', 'webgpu-no-adapter', 'webgpu-device-failed', 'webgpu-lost', 'load-failed', 'verify-failed', 'unknown'] as const)(
    'reason code %s has its own catalog text',
    (code) => {
      const key = `net.reason.${code}` as keyof typeof en;
      expect(Object.keys(en), 'the catalog has the key').toContain(key);
      expect(Object.keys(de), 'the German catalog has the key').toContain(key);
      const [d] = describeWarnings([{ code: 'model-load-failed', reason: code }], t);
      expect(d!.text).toContain((en[key] as string).replace(/\{variant\}/g, ''). trim().split(' ')[0]!); // the cause sentence of the catalog is part of the message
    },
  );

  it('the harmless CPU fallback gets the info tone, random moves and failed loads the alert tone (L5)', () => {
    const tone = (w: EngineWarning): string => describeWarnings([w], t)[0]!.tone;
    expect(tone({ code: 'backend-unavailable', backend: 'webgpu', fallback: 'wasm' })).toBe('info');
    expect(tone({ code: 'backend-unavailable', backend: 'wasm', fallback: 'random' })).toBe('alert');
    expect(tone({ code: 'no-model' })).toBe('alert');
    expect(tone({ code: 'model-load-failed' })).toBe('alert');
    expect(tone({ code: 'fallback-random' })).toBe('alert');
  });

  it('draws the icon of the tone', async () => {
    await render(<NetWarnings warnings={[{ code: 'backend-unavailable', backend: 'webgpu', fallback: 'wasm' }]} />);
    expect(q('[data-net-warning] use')!.getAttribute('href')).toBe('#i-info');
    await render(<NetWarnings warnings={[{ code: 'no-model' }]} />);
    expect(document.body.querySelector('[data-net-warning="no-model"] use')!.getAttribute('href')).toBe('#i-alert');
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
    await render(<NetWarnings warnings={[{ code: 'no-model' }, { code: 'model-load-failed', reason: 'unknown', detail: 'x' }]} onAction={{ retry: onRetry }} />);
    expect(q('[data-net-warning="no-model"] button')).toBeNull();
    await act(async () => q('[data-warning-action="retry"]')!.click());
    expect(onRetry).toHaveBeenCalledOnce();
  });

  it('shows the technical English text only in a collapsed native Details, none when there is no detail', async () => {
    await render(<NetWarnings warnings={[{ code: 'model-load-failed', reason: 'load-failed', detail: 'sha256 differs' }, { code: 'no-model' }]} />);
    const details = document.body.querySelectorAll<HTMLDetailsElement>('[data-net-warning] details');
    expect(details).toHaveLength(1);
    expect(details[0]!.open).toBe(false);
    expect(details[0]!.querySelector('summary')).not.toBeNull();
    expect(details[0]!.textContent).toContain('sha256 differs');
    expect(details[0]!.querySelector('[lang="en"]')).not.toBeNull();
    expect(q('[data-net-warning="model-load-failed"] .ml-netwarn__text')!.textContent).not.toContain('sha256 differs');
  });

  it('renders nothing for an empty list', async () => {
    await render(<NetWarnings warnings={[]} />);
    expect(q('[data-net-warning-list]')).toBeNull();
  });
});

describe('NetNotice (play screen)', () => {
  it('is not in the DOM at all while all is well, for a classical engine, and for warnings that need no notice (M3, L1)', async () => {
    await render(<NetNotice netEngine warnings={[]} onOpen={() => undefined} />);
    expect(document.body.querySelector('.ml-play__netnote')).toBeNull();
    expect(document.body.querySelector('[role="status"]')).toBeNull();
    await render(<NetNotice netEngine={false} warnings={[{ code: 'fallback-random' }, { code: 'backend-unavailable', fallback: 'wasm' }]} onOpen={() => undefined} />);
    expect(document.body.querySelector('.ml-play__netnote')).toBeNull();
    await render(<NetNotice netEngine warnings={[{ code: 'no-model' }, { code: 'model-load-failed' }]} onOpen={() => undefined} />);
    expect(document.body.querySelector('.ml-play__netnote')).toBeNull();
  });

  it.each([
    ['fallback-random', 'random', '#i-alert'],
    ['backend-unavailable', 'cpu', '#i-info'],
  ] as const)('%s gives the %s notice: a named polite status region with an open button and the icon of its tone', async (code, kind, icon) => {
    const onOpen = vi.fn();
    await render(<NetNotice netEngine warnings={[{ code, ...(code === 'backend-unavailable' ? { fallback: 'wasm' as const } : {}) }]} onOpen={onOpen} />);
    const el = q('.ml-play__netnote')!;
    expect(el.getAttribute('data-net-notice')).toBe(kind);
    expect(el.getAttribute('role')).toBe('status');
    expect(el.getAttribute('aria-live')).toBe('polite');
    expect(el.getAttribute('aria-label')!.length).toBeGreaterThan(0);
    expect(el.querySelector('use')!.getAttribute('href')).toBe(icon);
    await act(async () => q('[data-net-notice-open]')!.click());
    expect(onOpen).toHaveBeenCalledOnce();
  });

  it('random moves win over the CPU fallback when both are active', () => {
    expect(netNoticeKind(true, [{ code: 'backend-unavailable', fallback: 'wasm' }, { code: 'fallback-random' }])).toBe('random');
    expect(netNoticeKind(true, [{ code: 'backend-unavailable', fallback: 'wasm' }])).toBe('cpu');
    expect(netNoticeKind(true, [])).toBeNull();
    expect(netNoticeKind(false, [{ code: 'fallback-random' }])).toBeNull();
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
  /** Only the expert model: a variant it cannot play has no model at all, so the person's choice gives the mismatch warning. */
  const EXPERT_ONLY: readonly ModelInfo[] = listModels().filter((m) => m.id === 'small-2p-adjacent-expert');
  const dialog = async (
    selection: { engine: 'net-wasm' | 'cpp-wasm'; modelId?: string | null },
    initial = { jump: 'adjacent', mode: 'two', humanSeat: 0, engineSeat: 2 } as const,
    models?: readonly ModelInfo[],
  ): Promise<NetSelectionStore> => {
    const net = new NetSelectionStore({ initial: { ...selection, sims: 200 }, storage: null, wasm: true, lastModel: null, ...(models ? { models } : {}) });
    await render(<StartDialog model={{ kind: 'begin', initial: { camps: 'closed', rows: 4, ranking: 'first', ...initial }, onStart: () => undefined, net }} />);
    return net;
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

  it('the expert model for a 2-player game: no warning; three players without a fitting model gives the mismatch', async () => {
    await dialog({ engine: 'net-wasm', modelId: 'small-2p-adjacent-expert' }, undefined, EXPERT_ONLY);
    expect(warns()).toEqual([]);
    await act(async () => q('[data-choice="mode-hotseat"]')!.click());
    expect(warns()).toEqual(['model-variant-mismatch']);
  });

  it('the long jump rule also mismatches the expert model when no model fits', async () => {
    await dialog({ engine: 'net-wasm', modelId: 'small-2p-adjacent-expert' }, undefined, EXPERT_ONLY);
    await act(async () => q('[data-choice="jump-long"]')!.click());
    expect(warns()).toEqual(['model-variant-mismatch']);
  });

  it('switching to a variant the chosen model cannot play: the best fitting model takes over, no warning, the store stays', async () => {
    const net = await dialog({ engine: 'net-wasm', modelId: 'small-2p-adjacent-expert' });
    await act(async () => q('[data-choice="mode-engines"]')!.click());
    expect(warns()).toEqual([]);
    await act(async () => q('[data-choice="jump-long"]')!.click());
    expect(warns()).toEqual([]);
    expect(net.selection.modelId).toBe('small-2p-adjacent-expert'); // only the game that starts gets another model
    expect(net.modelFor({ jump: 'long', players: 3 })).toBe('small-shared-all');
  });

  it('nothing chosen and no model for the variant: the no-model warning, not the mismatch', async () => {
    await dialog({ engine: 'net-wasm' }, undefined, EXPERT_ONLY);
    expect(warns()).toEqual([]);
    await act(async () => q('[data-choice="mode-hotseat"]')!.click());
    expect(warns()).toEqual(['no-model']);
  });

  describe('link dialog (M6)', () => {
    const link = async (modelId: string | null, variant: Variant, engine: 'net-wasm' | 'cpp-wasm' = 'net-wasm', withStore = true, models = EXPERT_ONLY): Promise<void> => {
      const net = new NetSelectionStore({ initial: { engine, modelId, sims: 200 }, storage: null, wasm: true, models });
      await render(
        <StartDialog
          model={{ kind: 'link', ply: 4, jump: variant.jump, sideToMove: 0, onContinueAs: () => undefined, onNew: () => undefined, variant, ...(withStore ? { net } : {}) }}
        />,
      );
    };

    it('warns before Continue when the model cannot play the variant of the link', async () => {
      await link('small-2p-adjacent-expert', { jump: 'long', players: 2 });
      expect(warns()).toEqual(['model-variant-mismatch']);
      expect((q('.ml-start__actions button') as HTMLButtonElement).disabled).toBe(false);
    });

    it('judges the variant of the link, not the default: the expert model plays an adjacent 2-player link', async () => {
      await link('small-2p-adjacent-expert', { jump: 'adjacent', players: 2 });
      expect(warns()).toEqual([]);
      await link('small-2p-adjacent-expert', { jump: 'adjacent', players: 3 });
      expect(warns()).toEqual(['model-variant-mismatch']);
    });

    it('a classical engine or no store: no warning', async () => {
      await link('small-2p-adjacent-expert', { jump: 'long', players: 2 }, 'cpp-wasm');
      expect(warns()).toEqual([]);
      await link('small-2p-adjacent-expert', { jump: 'long', players: 2 }, 'net-wasm', false);
      expect(warns()).toEqual([]);
    });
  });

  it('a classical engine selected: no warning; no store: no warning', async () => {
    await dialog({ engine: 'cpp-wasm', modelId: null });
    expect(warns()).toEqual([]);
    act(() => root?.unmount());
    document.body.innerHTML = '';
    await render(<StartDialog model={{ kind: 'begin', initial: { jump: 'adjacent', camps: 'closed', rows: 4, ranking: 'first', mode: 'two', humanSeat: 0, engineSeat: 2 }, onStart: () => undefined }} />);
    expect(warns()).toEqual([]);
  });
});
