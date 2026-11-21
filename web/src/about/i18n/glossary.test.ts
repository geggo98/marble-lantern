// The display rule of the English term in the glossary and the popover (owner feedback of 2026-11-21: "F32 und F16 (F32 and F16)",
// "Faltung (Convolution) (convolution)" and "FLOP (FLOP (floating-point operation))" repeat what the name already says).
import { describe, expect, it } from 'vitest';
import { allTerms, englishToShow } from './glossary';

describe('englishToShow', () => {
  it('shows a different English term in German', () => {
    expect(englishToShow('de', 'Gewicht', 'weight')).toBe('weight');
    expect(englishToShow('de', 'Rückpropagierung', 'backpropagation')).toBe('backpropagation');
  });

  it('hides an English term that equals the name, in any case', () => {
    expect(englishToShow('de', 'Adam', 'Adam')).toBeUndefined();
    expect(englishToShow('de', 'Batch', 'batch')).toBeUndefined();
  });

  it('hides an English term that differs only by the conjunction (F32 und F16 / F32 and F16)', () => {
    expect(englishToShow('de', 'F32 und F16', 'F32 and F16')).toBeUndefined();
  });

  it('ignores hyphens and underscores when it compares (devenv-task / devenv task)', () => {
    expect(englishToShow('de', 'devenv-task', 'devenv task')).toBeUndefined();
  });

  it('hides an English term that the name contains (Faltung (Convolution) / convolution)', () => {
    expect(englishToShow('de', 'Faltung (Convolution)', 'convolution')).toBeUndefined();
  });

  it('hides an English term that contains the name (FLOP / FLOP (floating-point operation))', () => {
    expect(englishToShow('de', 'FLOP', 'FLOP (floating-point operation)')).toBeUndefined();
  });

  it('shows nothing without an English term and never shows it in English', () => {
    expect(englishToShow('de', 'Gewicht', undefined)).toBeUndefined();
    expect(englishToShow('en', 'Weight', 'weight')).toBeUndefined();
    expect(englishToShow('en', 'Gewicht', 'weight')).toBeUndefined();
  });

  it('shows, in the real German catalog, only terms whose English differs from the name', () => {
    const shown = allTerms('de').flatMap((e) => {
      const english = englishToShow('de', e.name, e.english);
      return english === undefined ? [] : [{ id: e.id, name: e.name.toLowerCase(), english: english.toLowerCase() }];
    });
    expect(shown.filter((s) => s.name === s.english || s.name.includes(s.english) || s.english.includes(s.name))).toEqual([]);
    expect(shown.some((s) => s.id === 'f32-f16' || s.id === 'convolution' || s.id === 'flop')).toBe(false);
  });
});
