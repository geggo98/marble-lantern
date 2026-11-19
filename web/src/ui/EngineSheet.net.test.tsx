// @vitest-environment jsdom
// The net part of the engine sheet: the model and simulation groups for each engine and variant, the live warnings with their buttons,
// the WebGPU row, what Apply hands over, and the keyboard. Texts come from the catalogs, so the tests read data attributes and roles.
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import type { Root } from 'react-dom/client';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { NetSelectionStore } from '../app/net-selection';
import type { EngineChoice } from '../engines/registry';
import type { ProbeResult } from '../engines/warnings';
import type { Variant } from '../game/variant';
import { EngineSheet } from './EngineSheet';
import type { SheetProps } from './EngineSheet';

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

const choice = (id: string, runtime = 'wasm'): EngineChoice =>
  ({
    available: true,
    definition: { id },
    info: { id, labelKey: 'engine.cppWasm', runtime, language: 'C++', version: 'v', binaryBytes: 480_000, binaryGzipBytes: 474_000 },
  }) as unknown as EngineChoice;
const CHOICES = [choice('cpp-wasm'), choice('net-wasm'), choice('net-webgpu')];
const V2: Variant = { jump: 'adjacent', players: 2 };
const V3: Variant = { jump: 'adjacent', players: 3 };
const EXPERT = 'small-2p-adjacent-expert';
const SHARED = 'small-shared-all';

let root: Root | null = null;
afterEach(() => {
  act(() => root?.unmount());
  root = null;
  document.body.innerHTML = '';
});

interface Opts {
  engine?: string;
  modelId?: string | null;
  sims?: 50 | 200 | 800;
  variant?: Variant;
  probe?: () => Promise<ProbeResult>;
  onApply?: SheetProps['onApply'];
  onClose?: () => void;
  initialFocus?: SheetProps['initialFocus'];
}

async function open(o: Opts = {}): Promise<NetSelectionStore> {
  const store = new NetSelectionStore({
    initial: { engine: (o.engine ?? 'net-wasm') as 'net-wasm', modelId: o.modelId ?? null, sims: o.sims ?? 200 },
    storage: null,
    wasm: true,
    probe: o.probe ?? (() => Promise.resolve({ available: true })),
  });
  const host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
  await act(async () => {
    root!.render(
      <EngineSheet
        choices={CHOICES}
        currentId={(o.engine ?? 'net-wasm') as 'net-wasm'}
        level={2}
        variant={o.variant ?? V2}
        netStore={store}
        onApply={o.onApply ?? (() => undefined)}
        onClose={o.onClose ?? (() => undefined)}
        {...(o.initialFocus ? { initialFocus: o.initialFocus } : {})}
      />,
    );
  });
  return store;
}

const q = <T extends Element = HTMLElement>(sel: string): T | null => document.body.querySelector<T>(sel);
const all = (sel: string): HTMLElement[] => Array.from(document.body.querySelectorAll<HTMLElement>(sel));
const models = (): string[] => all('[data-net-group="model"] [data-model]').map((e) => e.getAttribute('data-model')!);
const warnings = (): string[] => all('[data-net-warning]').map((e) => e.getAttribute('data-net-warning')!);
const click = async (el: Element | null): Promise<void> => {
  await act(async () => {
    (el as HTMLElement).click();
  });
};

describe('groups by engine', () => {
  it.each(['net-wasm', 'net-webgpu'])('%s shows the model and simulation groups and no node slider', async (engine) => {
    await open({ engine });
    expect(q('[data-net-group="model"]')).not.toBeNull();
    expect(q('[data-net-group="sims"]')).not.toBeNull();
    expect(q('input[type="range"]')).toBeNull();
    expect(q('[data-net-group="model"] [data-net-only-note]')).not.toBeNull(); // the hint that only net engines use a model lives here
  });

  it('a classical engine keeps the slider and hides both groups; the sheet carries no note about models (M3/L1: nothing new on the default path)', async () => {
    await open({ engine: 'cpp-wasm' });
    expect(q('input[type="range"]')).not.toBeNull();
    expect(q('[data-net-group]')).toBeNull();
    expect(q('[data-net-only-note]')).toBeNull();
    expect(warnings()).toEqual([]);
  });

  it('selecting an engine in the list switches the groups', async () => {
    await open({ engine: 'cpp-wasm' });
    await click(q('[data-engine="net-wasm"]'));
    expect(q('[data-net-group="model"]')).not.toBeNull();
    await click(q('[data-engine="cpp-wasm"]'));
    expect(q('[data-net-group="model"]')).toBeNull();
  });
});

describe('model list', () => {
  it('2 players, adjacent: None first and checked by default, then both models, no hidden note', async () => {
    await open();
    expect(models()).toEqual(['none', EXPERT, SHARED]);
    expect(q<HTMLInputElement>('[data-model="none"] input')!.checked).toBe(true);
    expect(q('[data-net-hidden-note]')).toBeNull();
  });

  it('3 players: the expert model is not offered, the shared one is, and a note says why', async () => {
    await open({ variant: V3 });
    expect(models()).toEqual(['none', SHARED]);
    expect(q('[data-net-hidden-note]')).not.toBeNull();
  });

  it('2 players with the long jump: the expert (adjacent only) is not offered', async () => {
    await open({ variant: { jump: 'long', players: 2 } });
    expect(models()).toEqual(['none', SHARED]);
  });

  it('each model shows its download size in KB and the measured strength per jump rule', async () => {
    await open();
    expect(q('[data-model="' + EXPERT + '"] .ml-choice__meta')!.textContent).toMatch(/115\D*KB/); // packedBytes 114957
    expect(all('[data-model="' + EXPERT + '"] [data-strength-fact]').map((e) => e.dataset.strengthFact)).toEqual(['adjacent']);
    expect(all('[data-model="' + SHARED + '"] [data-strength-fact]').map((e) => e.dataset.strengthFact)).toEqual(['adjacent', 'long']);
  });

  it('a model the variant does not fit is not checked, and the mismatch warning offers the way to choose another', async () => {
    await open({ variant: V3, modelId: EXPERT });
    expect(all('[data-net-group="model"] input:checked')).toHaveLength(0);
    expect(warnings()).toEqual(['model-variant-mismatch']);
  });
});

describe('simulations', () => {
  it('three presets with the stored one checked', async () => {
    await open({ sims: 800 });
    expect(all('[data-net-group="sims"] [data-sims]').map((e) => e.dataset.sims)).toEqual(['50', '200', '800']);
    expect(q<HTMLInputElement>('[data-sims="800"] input')!.checked).toBe(true);
    expect(q<HTMLInputElement>('[data-sims="200"] input')!.checked).toBe(false);
  });
});

describe('live warnings', () => {
  it('no model: no-model, with a button that moves focus to the model group', async () => {
    await open();
    expect(warnings()).toEqual(['no-model']);
    await click(q('[data-warning-action="choose-model"]'));
    expect(document.activeElement).toBe(q('[data-model="none"] input'));
  });

  it('choosing the expert model for 2 players clears the warning at once', async () => {
    await open();
    await click(q(`[data-model="${EXPERT}"] input`));
    expect(warnings()).toEqual([]);
  });

  it('net-webgpu without WebGPU: backend-unavailable (the CPU net plays), no random-move text', async () => {
    await open({ engine: 'net-webgpu', modelId: EXPERT, probe: () => Promise.resolve({ available: false, reason: 'webgpu-no-adapter', detail: 'no adapter' }) });
    expect(warnings()).toEqual(['backend-unavailable']);
    expect(q('[data-net-warning="backend-unavailable"]')!.textContent).toContain('net.reason.webgpu-no-adapter');
    expect(q('[data-net-warning="backend-unavailable"]')!.getAttribute('data-tone')).toBe('info');
    expect(q('[data-warning-action]')).toBeNull();
  });

  it('the live wrapper exists while there is no warning (a live region must be there before its text)', async () => {
    await open({ modelId: EXPERT });
    expect(q('[data-net-warning-live][aria-live="polite"]')).not.toBeNull();
    expect(warnings()).toEqual([]);
  });
});

describe('live region and focus (L2, L3)', () => {
  it('the live wrapper is in the sheet for a classical engine too and stays the same element when a net row is selected', async () => {
    await open({ engine: 'cpp-wasm' });
    const before = q('[data-net-warning-live][aria-live="polite"]');
    expect(before).not.toBeNull();
    expect(before!.childElementCount).toBe(0);
    await click(q('[data-engine="net-wasm"]'));
    expect(warnings()).toEqual(['no-model']);
    expect(q('[data-net-warning-live][aria-live="polite"]')).toBe(before);
    await click(q('[data-engine="cpp-wasm"]'));
    expect(q('[data-net-warning-live][aria-live="polite"]')).toBe(before);
    expect(before!.childElementCount).toBe(0);
  });

  it('selecting a net row scrolls its warning into view; opening the sheet on a net engine does not', async () => {
    const scrolled: Element[] = [];
    const original = Element.prototype.scrollIntoView;
    Element.prototype.scrollIntoView = function (this: Element): void {
      scrolled.push(this);
    };
    try {
      await open({ engine: 'cpp-wasm' });
      expect(scrolled).toEqual([]);
      await click(q('[data-engine="net-wasm"]'));
      expect(scrolled).toEqual([q('[data-net-warning-live]')]);
      scrolled.length = 0;
      await click(q('[data-engine="cpp-wasm"]'));
      expect(scrolled).toEqual([]);
    } finally {
      Element.prototype.scrollIntoView = original;
    }
  });

  it('initialFocus "model" puts the focus on the checked model radio of the group; by default it stays on the engine list', async () => {
    await open({ modelId: SHARED, initialFocus: 'model' });
    expect(document.activeElement).toBe(q(`[data-model="${SHARED}"] input`));
    act(() => root?.unmount());
    document.body.innerHTML = '';
    await open({});
    expect(document.activeElement).toBe(q('[data-engine="net-wasm"]'));
  });

  it('initialFocus "model" with no model chosen focuses None', async () => {
    await open({ initialFocus: 'model' });
    expect(document.activeElement).toBe(q('[data-model="none"] input'));
  });
});

describe('WebGPU row', () => {
  const status = (): string | null | undefined => q('[data-webgpu-status]')?.getAttribute('data-webgpu-status');
  it('says checking while the probe runs, then available', async () => {
    let done: (r: { available: boolean }) => void = () => undefined;
    await open({ engine: 'net-webgpu', probe: () => new Promise((r) => (done = r)) });
    expect(status()).toBe('checking');
    expect(q('[data-webgpu-status] use')!.getAttribute('href'), 'a neutral icon while the probe runs, no check mark').toBe('#i-wait');
    await act(async () => done({ available: true }));
    expect(status()).toBe('available');
    expect(q('[data-webgpu-status] use')!.getAttribute('href')).toBe('#i-check');
  });

  it('says unavailable with the reason from the probe', async () => {
    await open({ engine: 'net-webgpu', probe: () => Promise.resolve({ available: false, reason: 'webgpu-no-adapter', detail: 'no adapter' }) });
    expect(status()).toBe('unavailable');
    expect(q('[data-webgpu-status]')!.textContent).toContain('net.reason.webgpu-no-adapter'); // the localized text of the code
    expect(q<HTMLDetailsElement>('[data-webgpu-status] details')!.open).toBe(false); // the English technical text is folded away
    expect(q('[data-webgpu-status] details')!.textContent).toContain('no adapter');
  });

  it('is not probed when the sheet opens with another engine; selecting the WebGPU row probes once, and only then shows its status (M3, L1)', async () => {
    const probe = vi.fn(() => Promise.resolve({ available: true }));
    await open({ engine: 'cpp-wasm', probe });
    expect(probe).not.toHaveBeenCalled();
    expect(status()).toBeUndefined();
    await click(q('[data-engine="net-wasm"]'));
    expect(probe).not.toHaveBeenCalled();
    await click(q('[data-engine="net-webgpu"]'));
    expect(probe).toHaveBeenCalledTimes(1);
    expect(status()).toBe('available');
    await click(q('[data-engine="net-wasm"]'));
    await click(q('[data-engine="net-webgpu"]'));
    expect(probe).toHaveBeenCalledTimes(1);
  });

  it('is not probed when no WebGPU row is listed', async () => {
    const probe = vi.fn(() => Promise.resolve({ available: true }));
    const host = document.createElement('div');
    document.body.append(host);
    root = createRoot(host);
    const store = new NetSelectionStore({ initial: { engine: 'cpp-wasm', modelId: null, sims: 200 }, storage: null, probe });
    await act(async () => {
      root!.render(<EngineSheet choices={[choice('cpp-wasm')]} currentId="cpp-wasm" level={2} netStore={store} onApply={() => undefined} onClose={() => undefined} />);
    });
    expect(probe).not.toHaveBeenCalled();
  });
});

describe('apply and keyboard', () => {
  it('Apply hands over engine, level and the chosen model and simulations', async () => {
    const onApply = vi.fn();
    await open({ onApply });
    await click(q(`[data-model="${SHARED}"] input`));
    await click(q('[data-sims="50"] input'));
    await click(all('.ml-sheet__actions .ml-btn')[1]!);
    expect(onApply).toHaveBeenCalledWith('net-wasm', 2, { modelId: SHARED, sims: 50 });
  });

  it('Apply for a classical engine hands over no net settings', async () => {
    const onApply = vi.fn();
    await open({ engine: 'cpp-wasm', onApply });
    await click(all('.ml-sheet__actions .ml-btn')[1]!);
    expect(onApply).toHaveBeenCalledWith('cpp-wasm', 2);
  });

  it('Cancel changes nothing in the store', async () => {
    const store = await open();
    await click(q(`[data-model="${EXPERT}"] input`));
    expect(store.selection.modelId).toBeNull();
  });

  it('Escape closes; the radios are native inputs of one group per fieldset (arrow keys, one tab stop)', async () => {
    const onClose = vi.fn();
    await open({ onClose });
    const names = new Set(all('[data-net-group="model"] input').map((i) => (i as HTMLInputElement).name));
    expect(names.size).toBe(1);
    await act(async () => {
      q('.ml-sheet')!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    });
    expect(onClose).toHaveBeenCalled();
  });

  it('every control of the groups is a radio with an accessible name from its label', async () => {
    await open();
    for (const input of all('[data-net-group] input')) expect(input.closest('label')!.textContent!.length).toBeGreaterThan(0);
    for (const fieldset of all('[data-net-group]')) expect(fieldset.querySelector('legend')).not.toBeNull();
  });
});
