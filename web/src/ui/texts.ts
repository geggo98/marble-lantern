// UI texts of the screens. English placeholders until WP12 writes the final texts and moves the keys into
// web/src/i18n/{en,de}.json. Every key here is a working name from design/design-notes.md, section 11.
// Lookup order: the language's UI catalog (none yet for German), the English UI catalog, then the base catalog of
// src/i18n (app.title, engine.*, error.*, mode.*), then the key itself.
import { useSyncExternalStore } from 'react';
import { i18n } from '../i18n/i18n';
import type { MessageKey } from '../i18n/keys';

export const uiEn = {
  nav_label: 'Main navigation',
  nav_play: 'Play',
  nav_bench: 'Benchmark',
  nav_about: 'About',
  language: 'Language',
  theme: 'Theme',
  theme_auto: 'Follow system',
  theme_light: 'Light',
  theme_dark: 'Dark',
  close: 'Close',
  footer_note: 'A browser demo',
  source_code: 'Source code',
  new_game: 'New game',
  undo: 'Undo',
  swap_sides: 'Swap sides',
  demo: 'Engine demo',
  demo_stop: 'Stop demo',
  board_label: 'Board',
  turn_you: 'Your move',
  turn_engine: 'Engine to move',
  engine_thinking: 'Engine is thinking',
  status_over: 'Game over',
  status_loading: 'Starting the engine',
  ply: 'Ply',
  cell_empty: 'empty',
  cell_own: 'your piece',
  cell_other: 'engine piece',
  cell_side_a: 'amber piece',
  cell_side_b: 'blue piece',
  cell_selected: 'selected',
  cell_target: 'target',
  announce_move: '{side}: {from} to {to}',
  side_you: 'You',
  side_engine: 'Engine',
  side_a: 'Amber',
  side_b: 'Blue',
  illegal_target: 'That piece cannot go there. Pick one of the marked cells.',
  engine_heading: 'Engine',
  engine_change: 'Engine and strength',
  nodes_per_move: 'nodes per move',
  level_1: 'Beginner',
  level_2: 'Casual',
  level_3: 'Standard',
  level_4: 'Strong',
  level_5: 'Maximum',
  last_engine_move: 'Last engine move',
  nodes: 'Nodes',
  time: 'Time',
  depth: 'Depth',
  source: 'Source',
  src_search: 'search',
  src_race: 'race table',
  moves_heading: 'Moves',
  loading_title: 'Loading the engine',
  phase_fetch: 'Download',
  phase_inflate: 'Inflate',
  phase_verify: 'Verify',
  phase_instantiate: 'Start engine',
  phase_table: 'Load table',
  result_win_you: 'You win',
  result_win_engine: 'The engine wins',
  result_win_side: '{side} wins',
  result_nomoves: 'No legal move',
  result_after: 'after',
  plies: 'plies',
  result_limit: 'No winner at the move limit',
  result_limit_note: 'The game stopped after',
  distance_heading: 'Distance to the goal (approximate)',
  ahead: 'ahead',
  retry: 'Retry',
  use_java_js: 'Use the Java JS engine',
  use_cpp: 'Use the C++ engine',
  restart_engine: 'Restart the engine',
  err_checksum_title: 'The race table is damaged',
  err_checksum_body: 'The downloaded table does not match its checksum. A new download usually fixes this.',
  err_wasmgc_title: 'This browser cannot run that engine',
  err_wasmgc_body: 'The engine needs WebAssembly GC. Pick the Java JavaScript engine or the C++ engine.',
  err_crash_title: 'The engine stopped working',
  err_crash_body: 'The game is kept. Restart the engine to continue.',
  err_server_title: 'The server does not answer',
  err_server_body: 'Check the connection, or play with an engine that runs in this browser.',
  err_no_engine_title: 'No engine available',
  err_no_engine_body: 'This build did not ship an engine that this browser can run.',
  engine_title: 'Engine and strength',
  engines_browser: 'Runs in this browser',
  engines_server: 'Runs on the server',
  on_server: 'on the server',
  default: 'default',
  reason_unsupported: 'This browser cannot run this engine.',
  reason_not_shipped: 'This build does not include this engine.',
  strength: 'Strength',
  strength_hint: 'Time per move with this engine:',
  cancel: 'Cancel',
  apply: 'Apply',
  bench_title: 'Benchmark',
  bench_lead: 'Plays the same game with every engine and compares speed.',
  bench_start: 'Start',
  bench_stop: 'Stop',
  bench_progress: 'Benchmark progress',
  bench_caption: 'Benchmark results per engine',
  bench_unavailable: 'The benchmark is not part of this build yet.',
  bench_idle: 'No results yet.',
  run: 'Run',
  runs: 'runs',
  col_engine: 'Engine',
  col_fingerprint: 'Fingerprint',
  col_nps: 'Nodes per second',
  col_ms_move: 'ms per move',
  col_spread: 'Spread',
  col_load: 'Load ms',
  col_size: 'Size (raw / gzip)',
  col_rows1: 'rows1 requests',
  copy_json: 'Copy JSON',
  copied: 'Copied',
  about_title: 'About',
  about_lead: 'A small game that doubles as a showcase for Java on GPUs and in the browser.',
  about_what:
    'You play against an engine that searches the game tree. Several engines can play: one written in C++ and compiled to WebAssembly, and one written in Java and compiled by TeaVM.',
  about_rules:
    'The rules are those of the game known as Chinese Checkers or Sternhalma: two players move ten marbles each across a star-shaped board into the opposite corner.',
  about_engines:
    'In the offline build everything runs in your browser. The online build adds engines that run on a server.',
  about_tech: 'Technology',
  tech_tornadovm: 'Runs the neural network on GPUs from Java.',
  tech_teavm: 'Compiles the Java engine to WebAssembly and JavaScript.',
  tech_emscripten: 'Compiles the C++ engine to WebAssembly.',
  tech_hilla: 'Serves the online build; React draws the board.',
  link_repo: 'Repository',
  link_docs: 'Documentation',
  link_bench: 'Open the benchmark',
} as const;

export type UiKey = keyof typeof uiEn;
export type TextKey = UiKey | MessageKey;
export type Translate = (key: TextKey, values?: Record<string, string | number>) => string;

const uiCatalogs: Partial<Record<string, Partial<Record<UiKey, string>>>> = { en: uiEn };

function fill(text: string, values?: Record<string, string | number>): string {
  if (!values) return text;
  return text.replace(/\{(\w+)\}/g, (whole, name: string) => (name in values ? String(values[name]) : whole));
}

export function translate(key: TextKey, values?: Record<string, string | number>): string {
  const own = (uiCatalogs[i18n.current]?.[key as UiKey] ?? uiEn[key as UiKey]) as string | undefined;
  if (own !== undefined) return fill(own, values);
  return i18n.t(key as MessageKey, values);
}

/** Re-renders the component when the language changes. */
export function useT(): { t: Translate; n: (value: number) => string; language: string } {
  const language = useSyncExternalStore(
    (listener) => i18n.subscribe(listener),
    () => i18n.current,
  );
  return { t: translate, n: i18n.formatNumber, language };
}
