// The benchmark screen and the about screen (design-notes.md, section 9, screens 05 and 06).
import { useRef, useState } from 'react';
import { Icon } from './primitives';
import { REPO_URL, hrefFor, hrefForAbout } from './Chrome';
import { AboutTopic } from '../about/AboutTopic';
import { ABOUT_TOPICS } from '../about/routes';
import type { AboutRoute } from '../about/routes';
import '../about/about.css';
import { useT } from './texts';
import type { MemoryKind, NaCode } from '../bench/resources';
import type { MessageKey } from '../i18n/keys';

/** One engine's row of the result table. The benchmark code (WP10) fills it; this screen only shows it. */
export interface BenchRow {
  engineLabel: string;
  /** Fingerprint of the benchmark game equals the reference; null if it was not compared. */
  fingerprintOk: boolean | null;
  nodesPerSecond: number;
  msPerMove: number;
  /** Slowest run divided by the fastest run. */
  spread: number;
  loadMs: number;
  binaryBytes: number;
  binaryGzipBytes: number | null;
  rows1Requests: number;
  /** CPU time of the timed games in ms; null (with the reason in cpuNa) where the platform cannot measure it. */
  cpuMs: number | null;
  cpuNa: NaCode | null;
  /** Peak memory in bytes, what it is, or the reason it is missing. */
  memoryBytes: number | null;
  memoryKind: MemoryKind | null;
  memoryNa: NaCode | null;
}

export interface BenchReportView {
  rows: BenchRow[];
  /** One line: user agent, cores, plies, nodes, runs. */
  environment: string;
  /** The text the copy button puts on the clipboard. */
  json: string;
}

export interface BenchProgress {
  engineLabel: string;
  run: number;
  runs: number;
  ply: number;
  plies: number;
  /** 0 to 1 over the whole benchmark. */
  fraction: number;
}

export interface BenchRunner {
  run(onProgress: (p: BenchProgress) => void, signal: AbortSignal): Promise<BenchReportView>;
}

const NA_KEY: Record<NaCode, MessageKey> = {
  'no-cpu-api': 'bench_na_no_cpu_api',
  'no-rss-api': 'bench_na_no_rss_api',
  'jvm-via-gradle': 'bench_na_jvm_via_gradle',
  'wasm-gc-heap': 'bench_na_wasm_gc_heap',
  'no-js-heap-api': 'bench_na_no_js_heap_api',
  'not-isolated': 'bench_na_not_isolated',
  'no-ua-memory-api': 'bench_na_no_ua_memory_api',
};
const KIND_KEY: Record<MemoryKind, MessageKey> = {
  'process-rss': 'bench_mem_process_rss',
  'wasm-linear-memory': 'bench_mem_wasm_linear_memory',
  'js-heap-sampled': 'bench_mem_js_heap_sampled',
};

/** The footnotes under the table: why a figure is n/a and what each memory figure is, each with the engines it applies to. */
export function benchNotes(rows: readonly BenchRow[]): Array<{ key: MessageKey; engines: string[] }> {
  const notes = new Map<MessageKey, string[]>();
  const add = (key: MessageKey, engine: string): void => { notes.set(key, [...(notes.get(key) ?? []), engine]); };
  for (const r of rows) {
    if (r.cpuNa) add(NA_KEY[r.cpuNa], r.engineLabel);
    if (r.memoryNa) add(NA_KEY[r.memoryNa], r.engineLabel);
    if (r.memoryKind) add(KIND_KEY[r.memoryKind], r.engineLabel);
  }
  return [...notes].map(([key, engines]) => ({ key, engines }));
}

export function BenchScreen({ runner }: { runner: BenchRunner | null }): React.JSX.Element {
  const { t, n } = useT();
  const [running, setRunning] = useState(false);
  const [progress, setProgress] = useState<BenchProgress | null>(null);
  const [report, setReport] = useState<BenchReportView | null>(null);
  const [failed, setFailed] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);
  const abort = useRef<AbortController | null>(null);

  const start = async (): Promise<void> => {
    if (!runner) return;
    const controller = new AbortController();
    abort.current = controller;
    setRunning(true);
    setFailed(null);
    setCopied(false);
    try {
      const result = await runner.run(setProgress, controller.signal);
      setReport(result);
    } catch (e) {
      if (!controller.signal.aborted) setFailed(e instanceof Error ? e.message : String(e));
    } finally {
      setRunning(false);
      setProgress(null);
    }
  };

  const copy = async (): Promise<void> => {
    if (!report) return;
    try {
      await navigator.clipboard.writeText(report.json);
      setCopied(true);
    } catch {
      setCopied(false); // no clipboard in an insecure context; the button stays
    }
  };

  const best = Math.max(1, ...(report?.rows.map((r) => r.nodesPerSecond) ?? []));
  const percent = progress ? Math.round(progress.fraction * 100) : 0;

  return (
    <main className="ml-page">
      <div>
        <h1 className="ml-page__title">{t('bench_title')}</h1>
        <p className="ml-page__lead">{t('bench_lead')}</p>
      </div>
      <section className="ml-card ml-bench__run" aria-live="polite">
        <div className="ml-bench__actions">
          <button type="button" className="ml-btn ml-btn--primary" disabled={running || !runner} onClick={() => void start()}>
            <Icon name="play" />
            <span>{t('bench_start')}</span>
          </button>
          {running && (
            <button type="button" className="ml-btn" onClick={() => abort.current?.abort()}>
              {t('bench_stop')}
            </button>
          )}
        </div>
        {!runner && <p className="ml-hint"><Icon name="alert" /><span>{t('bench_unavailable')}</span></p>}
        {running && (
          <>
            <div className="ml-progress" role="progressbar" aria-valuemin={0} aria-valuemax={100} aria-valuenow={percent} aria-label={t('bench_progress')}>
              <div className="ml-progress__fill" style={{ width: `${percent}%` }} />
            </div>
            {progress && (
              <p className="ml-bench__line">
                <span>{progress.engineLabel}</span>
                <span>
                  {t('run')} {progress.run}/{progress.runs} · {t('ply')} {progress.ply}/{progress.plies}
                </span>
              </p>
            )}
          </>
        )}
        {failed && <p className="ml-alert__code" role="alert">{failed}</p>}
      </section>
      {report ? (
        <>
          <section className="ml-card ml-table-wrap">
            <table className="ml-table">
              <caption className="ml-visually-hidden">{t('bench_caption')}</caption>
              <thead>
                <tr>
                  {(['col_engine', 'col_fingerprint', 'col_nps', 'col_ms_move', 'col_spread', 'col_cpu', 'col_memory', 'col_load', 'col_size', 'col_rows1'] as const).map((k) => (
                    <th key={k} scope="col">{t(k)}</th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {report.rows.map((r) => (
                  <tr key={r.engineLabel}>
                    <td>{r.engineLabel}</td>
                    <td data-label={t('col_fingerprint')}>{r.fingerprintOk === null ? '–' : <span className="ml-ok">{r.fingerprintOk ? 'ok' : 'differs'}</span>}</td>
                    <td data-label={t('col_nps')}>
                      <span>
                        {n(Math.round(r.nodesPerSecond))}
                        <span className="ml-bar" style={{ width: `${Math.max(2, Math.round((r.nodesPerSecond / best) * 100))}%` }} />
                      </span>
                    </td>
                    <td data-label={t('col_ms_move')}>{n(Math.round(r.msPerMove * 10) / 10)}</td>
                    <td data-label={t('col_spread')}>{n(Math.round(r.spread * 100) / 100)}</td>
                    <td data-label={t('col_cpu')}>
                      {r.cpuMs === null ? <span title={r.cpuNa ? t(NA_KEY[r.cpuNa]) : undefined}>{t('na')}</span> : n(Math.round(r.cpuMs))}
                    </td>
                    <td data-label={t('col_memory')}>
                      {r.memoryBytes === null ? (
                        <span title={r.memoryNa ? t(NA_KEY[r.memoryNa]) : undefined}>{t('na')}</span>
                      ) : (
                        <span title={r.memoryKind ? t(KIND_KEY[r.memoryKind]) : undefined}>{n(Math.round((r.memoryBytes / (1024 * 1024)) * 10) / 10)}</span>
                      )}
                    </td>
                    <td data-label={t('col_load')}>{n(Math.round(r.loadMs))}</td>
                    <td data-label={t('col_size')}>
                      {n(Math.round(r.binaryBytes / 1000))} KB / {r.binaryGzipBytes ? `${n(Math.round(r.binaryGzipBytes / 1000))} KB` : '—'}
                    </td>
                    <td data-label={t('col_rows1')}>{r.rows1Requests}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </section>
          <section className="ml-bench__notes" aria-labelledby="bench-notes-title">
            <p className="ml-kicker" id="bench-notes-title">{t('bench_notes_title')}</p>
            <ul>
              {benchNotes(report.rows).map((note) => (
                <li key={note.key}>
                  <strong>{note.engines.join(', ')}:</strong> {t(note.key)}
                </li>
              ))}
            </ul>
          </section>
          <p className="ml-env">{report.environment}</p>
          <div className="ml-bench__actions">
            <button type="button" className="ml-btn" onClick={() => void copy()}>
              <Icon name="copy" />
              <span>{t('copy_json')}</span>
            </button>
            {copied && (
              <span className="ml-hint" role="status">
                <Icon name="check" />
                <span>{t('copied')}</span>
              </span>
            )}
          </div>
        </>
      ) : (
        runner && !running && <p className="ml-hint">{t('bench_idle')}</p>
      )}
    </main>
  );
}

// Technology names are data, not copy (design-notes.md, section 11).
const TECH: { name: string; key: 'tech_tornadovm' | 'tech_teavm' | 'tech_emscripten' | 'tech_hilla' }[] = [
  { name: 'TornadoVM', key: 'tech_tornadovm' },
  { name: 'TeaVM', key: 'tech_teavm' },
  { name: 'Emscripten', key: 'tech_emscripten' },
  { name: 'Vaadin Hilla, React', key: 'tech_hilla' },
];

export interface AboutScreenProps extends AboutRoute {
  /** A jump inside the "On this page" list: the app replaces the history entry. */
  onJumpSection?: (section: string) => void;
}

/** The hub (today's About content, then the topic cards), or a topic page when the route names one. */
export function AboutScreen({ topic, section, notFound = false, onJumpSection }: AboutScreenProps = {}): React.JSX.Element {
  const { t } = useT();
  if (topic !== undefined) return <AboutTopic topic={topic} section={section} onJumpSection={onJumpSection ?? (() => undefined)} />;
  return (
    <main className="ml-page">
      {notFound && <p className="ml-hint" role="status">{t('about_notfound')}</p>}
      <div>
        <h1 className="ml-page__title">{t('about_title')}</h1>
        <p className="ml-page__lead">{t('about_lead')}</p>
      </div>
      <div className="ml-prose">
        <p>{t('about_what')}</p>
        <p>{t('about_rules')}</p>
        <p>{t('about_engines')}</p>
      </div>
      <section>
        <p className="ml-kicker" style={{ marginBottom: '0.5rem' }}>{t('about_tech')}</p>
        <dl className="ml-card ml-techlist">
          {TECH.map((row) => (
            <div key={row.name} className="ml-techlist__row">
              <dt>{row.name}</dt>
              <dd>{t(row.key)}</dd>
            </div>
          ))}
        </dl>
      </section>
      <div className="ml-links">
        <a className="ml-btn" href={REPO_URL} rel="noreferrer">
          <Icon name="ext" />
          <span>{t('link_repo')}</span>
        </a>
        <a className="ml-btn" href={`${REPO_URL}/tree/main/docs`} rel="noreferrer">
          <Icon name="ext" />
          <span>{t('link_docs')}</span>
        </a>
        <a className="ml-btn" href={hrefFor('bench')}>
          <Icon name="play" />
          <span>{t('link_bench')}</span>
        </a>
      </div>
      <section aria-labelledby="about-learn-title" className="ml-learn">
        <h2 id="about-learn-title" className="ml-learn__title">{t('about_learn_title')}</h2>
        <p className="ml-page__lead">{t('about_learn_lead')}</p>
        <ul className="ml-learn__cards">
          {ABOUT_TOPICS.map((topic) => (
            <li key={topic} className="ml-card ml-learn__card" data-topic={topic}>
              <p className="ml-kicker">{t(`about_learn_${topic}_kicker`, { n: ABOUT_TOPICS.indexOf(topic) + 1 })}</p>
              <h3 className="ml-card__title">
                <a className="ml-learn__link" href={hrefForAbout(topic)}>{t(`about_learn_${topic}_title`)}</a>
              </h3>
              <p>{t(`about_learn_${topic}_teaser`)}</p>
              <p className="ml-kicker">{t(`about_learn_${topic}_time`)}</p>
            </li>
          ))}
        </ul>
      </section>
    </main>
  );
}
