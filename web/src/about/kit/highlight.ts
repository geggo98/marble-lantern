// A tiny tokenizer for the code snippets of the Info part: keywords, annotations, comments and strings of Java, TypeScript and Kotlin
// script. No dependency, no grammar: a snippet is a few lines cut from the repository, and three colours are all the page needs.
// Limit: a `/` inside a regex literal or a template string is not understood; no snippet has one (the snippet test would show a wrong cut).

export type CodeLang = 'java' | 'ts' | 'kts';
export type TokenKind = 'plain' | 'kw' | 'comment' | 'string';
export interface Token {
  kind: TokenKind;
  text: string;
}

const JAVA = [
  'abstract', 'assert', 'boolean', 'break', 'byte', 'case', 'catch', 'char', 'class', 'continue', 'default', 'do', 'double', 'else', 'enum',
  'extends', 'final', 'finally', 'float', 'for', 'if', 'implements', 'import', 'instanceof', 'int', 'interface', 'long', 'new', 'null', 'package',
  'private', 'protected', 'public', 'record', 'return', 'short', 'static', 'super', 'switch', 'this', 'throw', 'throws', 'try', 'value', 'void', 'while',
  'true', 'false', 'var',
];
const TS = [
  'async', 'await', 'boolean', 'break', 'case', 'catch', 'class', 'const', 'continue', 'default', 'else', 'export', 'extends', 'false', 'finally',
  'for', 'from', 'function', 'if', 'import', 'in', 'interface', 'let', 'new', 'null', 'number', 'of', 'return', 'string', 'switch', 'this', 'throw',
  'true', 'try', 'type', 'undefined', 'var', 'void', 'while',
];
const KTS = [
  'as', 'break', 'by', 'class', 'continue', 'else', 'false', 'for', 'fun', 'if', 'in', 'is', 'null', 'object', 'return', 'true', 'val', 'var', 'when', 'while',
];

const KEYWORDS: Record<CodeLang, ReadonlySet<string>> = { java: new Set(JAVA), ts: new Set(TS), kts: new Set(KTS) };

// One alternative per kind, tried in this order at each position:
// block comment `/* ... */` (also unterminated), line comment `// ...`, string `"..."` or `'...'` with backslash escapes, annotation `@Name`, word.
const TOKEN = /(?<block>\/\*[\s\S]*?(?:\*\/|$))|(?<line>\/\/[^\n]*)|(?<str>"(?:\\.|[^"\\\n])*"?|'(?:\\.|[^'\\\n])*'?)|(?<ann>@[A-Za-z_]\w*)|(?<word>[A-Za-z_$][\w$]*)/g;

export function tokenize(text: string, lang: CodeLang): Token[] {
  const keywords = KEYWORDS[lang];
  const out: Token[] = [];
  const push = (kind: TokenKind, chunk: string): void => {
    if (chunk === '') return;
    const last = out[out.length - 1];
    if (last && last.kind === kind) last.text += chunk;
    else out.push({ kind, text: chunk });
  };
  let at = 0;
  for (const m of text.matchAll(TOKEN)) {
    const start = m.index ?? 0;
    push('plain', text.slice(at, start));
    const g = m.groups!;
    if (g['block'] !== undefined || g['line'] !== undefined) push('comment', m[0]);
    else if (g['str'] !== undefined) push('string', m[0]);
    else if (g['ann'] !== undefined) push('kw', m[0]);
    else push(keywords.has(m[0]) ? 'kw' : 'plain', m[0]);
    at = start + m[0].length;
  }
  push('plain', text.slice(at));
  return out;
}

/** The text without the indentation that every non-empty line shares (display and copy; the data keeps the verbatim lines). */
export function dedent(text: string): string {
  const lines = text.split('\n');
  const indents = lines.filter((l) => l.trim() !== '').map((l) => /^[ \t]*/.exec(l)![0].length);
  const cut = indents.length === 0 ? 0 : Math.min(...indents);
  return lines.map((l) => l.slice(Math.min(cut, /^[ \t]*/.exec(l)![0].length))).join('\n');
}
