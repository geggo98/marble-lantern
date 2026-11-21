// Scientific notation of the inline markup: {sci:3e-4} is 3 · 10⁻⁴, typeset as MathML; {sci:1.5e-4} keeps its mantissa.
// Pure helpers, so that the parser, the renderer and the plain-text variant share one reading of the token.

/** `3e-4`, `1.5e-4`, `-2E+3`: a decimal mantissa with a point, `e` or `E`, a signed integer exponent. */
const SCI = /^(?<mantissa>-?\d+(?:\.\d+)?)[eE](?<exponent>[+-]?\d+)$/;

export interface Sci {
  mantissa: number;
  exponent: number;
}

/** The value of a `{sci:…}` body, or undefined when the body is malformed. */
export function parseSci(body: string): Sci | undefined {
  const g = SCI.exec(body)?.groups;
  if (g === undefined) return undefined;
  const mantissa = Number(g['mantissa']);
  const exponent = Number(g['exponent']);
  return Number.isFinite(mantissa) && Number.isSafeInteger(exponent) ? { mantissa, exponent } : undefined;
}

/** The mantissa in the language of the page (German decimal comma), never rounded. */
export const formatMantissa = (mantissa: number, language: string): string =>
  new Intl.NumberFormat(language, { maximumFractionDigits: 20 }).format(mantissa);

/** A mantissa of exactly 1 is left out: 1e-5 reads 10⁻⁵, not 1 · 10⁻⁵. */
export const showsMantissa = (s: Sci): boolean => s.mantissa !== 1;

/** The words for a screen reader: "3 times 10 to the power of minus 4" / "3 mal 10 hoch minus 4". The words come from this function, not from the catalogs. */
export function sciSpoken(s: Sci, language: string): string {
  const de = language === 'de';
  const e = s.exponent < 0 ? `minus ${-s.exponent}` : String(s.exponent);
  const power = de ? `10 hoch ${e}` : `10 to the power of ${e}`;
  return showsMantissa(s) ? `${formatMantissa(s.mantissa, language)} ${de ? 'mal' : 'times'} ${power}` : power;
}

const SUPER: Record<string, string> = { '-': '⁻', '0': '⁰', '1': '¹', '2': '²', '3': '³', '4': '⁴', '5': '⁵', '6': '⁶', '7': '⁷', '8': '⁸', '9': '⁹' };

/** Plain text with Unicode superscripts: 3 · 10⁻⁴ (for an SVG label or an attribute). */
export function sciPlain(s: Sci, language: string): string {
  const power = `10${[...String(s.exponent)].map((c) => SUPER[c] ?? c).join('')}`;
  return showsMantissa(s) ? `${formatMantissa(s.mantissa, language)} · ${power}` : power;
}
