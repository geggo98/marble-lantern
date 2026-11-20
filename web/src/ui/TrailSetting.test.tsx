// @vitest-environment jsdom
// The trail card: "Fade out" with a slider of 1 to 10 seconds, or "Always show" (stored as 0 seconds).
import { act, useState } from 'react';
import { createRoot } from 'react-dom/client';
import type { Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import en from '../i18n/en.json';
import { TrailSetting } from './TrailSetting';

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

let host: HTMLElement;
let root: Root;
let stored: number[];

function Harness({ initial }: { initial: number }): React.JSX.Element {
  const [seconds, setSeconds] = useState(initial);
  return (
    <TrailSetting
      seconds={seconds}
      onChange={(n) => {
        stored.push(n);
        setSeconds(n);
      }}
    />
  );
}
async function mount(initial: number): Promise<void> {
  await act(async () => root.render(<Harness initial={initial} />));
}
const q = <T extends Element = HTMLElement>(selector: string): T | null => host.querySelector<T>(selector);
const mode = (m: 'timed' | 'always'): HTMLInputElement => q<HTMLInputElement>(`[data-trail-mode-input="${m}"]`)!;
const pick = async (m: 'timed' | 'always'): Promise<void> => {
  await act(async () => mode(m).click());
};
const slide = async (value: number): Promise<void> => {
  const input = q<HTMLInputElement>('[data-trail-setting]')!;
  await act(async () => {
    Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!.call(input, String(value));
    input.dispatchEvent(new Event('input', { bubbles: true }));
  });
};

beforeEach(() => {
  stored = [];
  host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
});
afterEach(async () => {
  await act(async () => root.unmount());
  host.remove();
});

describe('TrailSetting', () => {
  it('offers both choices by name, and "Fade out" shows the slider from 1 to 10 s with its value', async () => {
    await mount(3);
    expect(q('legend')?.textContent).toBe(en.trail_legend);
    expect([...host.querySelectorAll('.ml-trail-card__mode')].map((l) => l.textContent)).toEqual([en.trail_mode_timed, en.trail_mode_always]);
    expect(mode('timed').checked).toBe(true);
    const range = q<HTMLInputElement>('[data-trail-setting]')!;
    expect([range.min, range.max, range.step, range.value]).toEqual(['1', '10', '1', '3']);
    expect(q('[data-trail-value]')?.textContent).toBe('3 s');
    expect(q('[data-trail-note]')).toBeNull();
  });

  it('"Always show" stores 0 and replaces the slider with a note', async () => {
    await mount(3);
    await pick('always');
    expect(stored).toEqual([0]);
    expect(mode('always').checked).toBe(true);
    expect(q('[data-trail-setting]')).toBeNull();
    expect(q('[data-trail-note]')?.textContent).toBe(en.trail_always_hint);
  });

  it('a stored 0 opens on "Always show"; going back restores the default of 3 s', async () => {
    await mount(0);
    expect(mode('always').checked).toBe(true);
    expect(q('[data-trail-setting]')).toBeNull();
    await pick('timed');
    expect(stored).toEqual([3]);
    expect(q<HTMLInputElement>('[data-trail-setting]')!.value).toBe('3');
  });

  it('going back restores the last time of this session', async () => {
    await mount(3);
    await slide(7);
    expect(stored).toEqual([7]);
    expect(q('[data-trail-value]')?.textContent).toBe('7 s');
    await pick('always');
    await pick('timed');
    expect(stored).toEqual([7, 0, 7]);
  });

  it('the slider never reaches 0: "always" is its own choice', async () => {
    await mount(1);
    expect(q<HTMLInputElement>('[data-trail-setting]')!.min).toBe('1');
  });
});
