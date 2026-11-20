// @vitest-environment jsdom
// The advantage strip in its states: value, pending, unavailable (two players), and the three-player bar before its first value.
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import type { Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { describeAdvantage } from '../guides/advantage';
import en from '../i18n/en.json';
import { AdvantageBar } from './AdvantageBar';
import { AdvantageBar3 } from './AdvantageBar3';

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

let host: HTMLElement;
let root: Root;
const draw = async (node: React.JSX.Element): Promise<void> => act(async () => root.render(node));
const q = <T extends Element = HTMLElement>(s: string): T | null => host.querySelector<T>(s);

beforeEach(() => {
  host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
});
afterEach(async () => {
  await act(async () => root.unmount());
  host.remove();
});

describe('AdvantageBar states', () => {
  it('value: heading, level text, a meter with the value and the fill on the side that is ahead; marbles at both ends', async () => {
    await draw(<AdvantageBar state="value" info={describeAdvantage(150)} ends={{ engine: 'b', you: 'a' }} />);
    expect(q('.ml-advantage')?.getAttribute('data-state')).toBe('value');
    expect(q('.ml-advantage__text')?.textContent).toBe('Advantage: You: clearly ahead');
    const meter = q('[role="meter"]')!;
    expect(meter.getAttribute('aria-valuetext')).toBe('You: clearly ahead');
    expect(q('.ml-advantage__fill--you')).not.toBeNull();
    expect([...host.querySelectorAll('.ml-advantage__gauge use')].map((u) => u.getAttribute('href'))).toEqual(['#ml-piece-b', '#ml-piece-a']);
  });

  it('pending: the muted text, an empty track with the centre mark, no meter value', async () => {
    await draw(<AdvantageBar state="pending" ends={{ engine: 'b', you: 'a' }} />);
    expect(q('.ml-advantage')?.getAttribute('data-state')).toBe('pending');
    expect(q('.ml-advantage__text')?.textContent).toBe(en.advantage_pending);
    expect(q('[role="meter"]')).toBeNull();
    expect(q('.ml-advantage__fill')).toBeNull();
    expect(q('.ml-advantage__centre')).not.toBeNull();
  });

  it('unavailable: its own text and state, and no marbles without ends', async () => {
    await draw(<AdvantageBar state="unavailable" />);
    expect(q('.ml-advantage')?.getAttribute('data-state')).toBe('unavailable');
    expect(q('.ml-advantage__text')?.textContent).toBe(en.advantage_unavailable);
    expect(host.querySelectorAll('.ml-advantage__gauge .ml-swatch')).toHaveLength(0);
    expect(q('.ml-advantage__track')?.getAttribute('aria-label')).toBe(`${en.advantage_heading}: ${en.advantage_unavailable}`);
  });

  it('a stale value says so in the text', async () => {
    await draw(<AdvantageBar state="value" info={describeAdvantage(-150)} stale />);
    expect(q('.ml-advantage')?.classList.contains('is-stale')).toBe(true);
    expect(q('.ml-advantage__text')?.textContent).toContain(`(${en.advantage_updating})`);
  });
});

describe('AdvantageBar3 before the first value', () => {
  it('shows one equal segment per seat with "…", state pending, and the pending text as its name', async () => {
    await draw(<AdvantageBar3 shares={null} names={['A', 'B', 'C']} styles={['a', 'b', 'c']} stale={false} />);
    expect(q('[data-advantage3]')?.getAttribute('data-state')).toBe('pending');
    const segs = [...host.querySelectorAll('.ml-adv3__seg')];
    expect(segs).toHaveLength(3);
    expect(segs.map((s) => s.querySelector('.ml-adv3__value')?.textContent)).toEqual(['…', '…', '…']);
    expect(segs.every((s) => !s.hasAttribute('data-percent') && !s.hasAttribute('data-place'))).toBe(true);
    expect(q('.ml-adv3')?.getAttribute('aria-label')).toBe(`${en.advantage_heading}: ${en.advantage_pending}`);
  });
});
