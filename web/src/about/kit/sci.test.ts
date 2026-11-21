// {sci:…}: the value, the mantissa per language, the words for a screen reader and the plain text.
import { describe, expect, it } from 'vitest';
import { parseSci, sciPlain, sciSpoken } from './sci';

describe('parseSci', () => {
  it('reads a mantissa and a signed exponent', () => {
    expect(parseSci('3e-4')).toEqual({ mantissa: 3, exponent: -4 });
    expect(parseSci('1.5e-4')).toEqual({ mantissa: 1.5, exponent: -4 });
    expect(parseSci('2E+3')).toEqual({ mantissa: 2, exponent: 3 });
    expect(parseSci('5e6')).toEqual({ mantissa: 5, exponent: 6 });
  });
  it.each(['', 'e-4', '3e', '3e-', '3.e-4', '.5e-4', '3e-4.5', '3 e-4', '0x3e-4', '3e--4', 'abc'])('refuses %j', (body) => {
    expect(parseSci(body)).toBeUndefined();
  });
});

describe('words and plain text', () => {
  it('speaks the number in the language of the page, with a decimal comma in German', () => {
    expect(sciSpoken({ mantissa: 3, exponent: -4 }, 'en')).toBe('3 times 10 to the power of minus 4');
    expect(sciSpoken({ mantissa: 1.5, exponent: -4 }, 'de')).toBe('1,5 mal 10 hoch minus 4');
    expect(sciSpoken({ mantissa: 1, exponent: -5 }, 'en')).toBe('10 to the power of minus 5');
    expect(sciSpoken({ mantissa: 2, exponent: 3 }, 'de')).toBe('2 mal 10 hoch 3');
  });
  it('writes Unicode superscripts as plain text', () => {
    expect(sciPlain({ mantissa: 3, exponent: -4 }, 'en')).toBe('3 · 10⁻⁴');
    expect(sciPlain({ mantissa: 1.5, exponent: 12 }, 'de')).toBe('1,5 · 10¹²');
    expect(sciPlain({ mantissa: 1, exponent: -5 }, 'en')).toBe('10⁻⁵');
  });
});
