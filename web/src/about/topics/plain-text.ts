// A catalog string as plain text (for an SVG label, an aria-label or an attribute), where the inline markup of plan section 1.6 cannot be
// rendered as elements: `code` loses its backticks, {n:20000} becomes the number in the active language, a term or section shows its text.
import { parseMarkup } from '../kit/markup';
import { sciPlain } from '../kit/sci';

export function plainText(source: string, formatNumber: (value: number) => string, language = 'en'): string {
  return parseMarkup(source, () => undefined)
    .map((node) => {
      switch (node.type) {
        case 'text':
        case 'code':
          return node.text;
        case 'n':
          return formatNumber(node.value);
        case 'sci':
          return sciPlain(node, language);
        case 'src':
          return node.path;
        case 'term':
          return node.text ?? node.id;
        case 'sec':
          return node.text ?? node.section;
      }
    })
    .join('');
}
