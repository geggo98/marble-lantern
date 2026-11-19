// Engine and strength: a modal bottom sheet on a phone, a dialog on a wide screen (design-notes.md, section 9, P1 and P2).
import { useEffect, useMemo, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import type { EngineId } from '../engines/engine';
import type { EngineChoice } from '../engines/registry';
import { isNetEngine } from '../engines/engine';
import { listModels } from '../engines/net/model-manifest';
import { evaluateSelection } from '../engines/net/selection';
import type { Sims } from '../engines/warnings';
import { DEFAULT_VARIANT } from '../game/variant';
import type { Variant } from '../game/variant';
import { NetSelectionStore, useNetSelection } from '../app/net-selection';
import { MODEL_GROUP, ModelGroup, SimsGroup } from './NetGroups';
import { NetWarnings } from './net-warnings';
import { Icon, Tag } from './primitives';
import { DEFAULT_LEVEL, NODE_LEVELS, levelKey, runtimeTag } from './Panels';
import { useT } from './texts';
import type { TextKey } from './texts';

/**
 * Speeds in nodes per second for the "time per move" estimate. Origin: the scout measurements in node with 800,000 nodes
 * (C++ Wasm 1.85 M, TeaVM Wasm GC 0.94 M, TeaVM JS 0.26 M) and the JVM run of the Hilla spike (about 1.4 M).
 * They are estimates of this machine class, not promises; the line says "about".
 */
const NODES_PER_SECOND: Record<string, number> = { wasm: 1_850_000, 'wasm-gc': 940_000, js: 260_000, server: 1_400_000 };

export function estimateMs(runtime: string, nodes: number): number {
  return Math.max(1, Math.round((nodes / (NODES_PER_SECOND[runtime] ?? 1_000_000)) * 1000));
}

export interface SheetProps {
  choices: readonly EngineChoice[];
  currentId: EngineId | null;
  level: number;
  /** `net` is given for a net engine only: the model and the simulations of the groups below. */
  onApply: (engine: EngineId, level: number, net?: { modelId: string | null; sims: Sims }) => void;
  /** The selection of the net engines (model, simulations, WebGPU probe). Without it the sheet keeps a private one. */
  netStore?: NetSelectionStore | undefined;
  /** The variant of the game: only the models that may play it are offered. */
  variant?: Variant | undefined;
  onClose: () => void;
}

const kb = (bytes: number, n: (v: number) => string): string => `${n(Math.round(bytes / 1000))} KB`;
/** Gzip size to show: the typical download when the manifest has one (net engines), else the sum of all files. */
export const gzipShown = (info: { binaryGzipBytes: number; downloadGzipBytes?: number }): number => info.downloadGzipBytes ?? info.binaryGzipBytes;

export function EngineSheet(props: SheetProps): React.JSX.Element {
  const { t, n } = useT();
  const first = props.choices.find((c) => c.info.id === props.currentId && c.available) ?? props.choices.find((c) => c.available);
  /** The engine the session starts with: the first available one in registry order (ADR 0003). */
  const defaultId = props.choices.find((c) => c.available)?.info.id ?? null;
  const [engine, setEngine] = useState<EngineId | null>(first?.info.id ?? null);
  const [level, setLevel] = useState(props.level < NODE_LEVELS.length ? props.level : DEFAULT_LEVEL);
  const dialogRef = useRef<HTMLElement | null>(null);
  const fallbackStore = useMemo(() => props.netStore ?? new NetSelectionStore({ storage: null }), [props.netStore]);
  const variant = props.variant ?? DEFAULT_VARIANT;
  const net = useNetSelection(fallbackStore, variant);
  const [modelId, setModelId] = useState<string | null>(net.selection.modelId);
  const [sims, setSims] = useState<Sims>(net.selection.sims);
  const hasWebGpuRow = props.choices.some((c) => c.info.id === 'net-webgpu');
  useEffect(() => {
    if (hasWebGpuRow) void net.probeWebGpu(); // the row shows what the probe says
  }, [hasWebGpuRow]); // eslint-disable-line react-hooks/exhaustive-deps -- once per sheet
  const opener = useRef<Element | null>(typeof document === 'undefined' ? null : document.activeElement);

  useEffect(() => {
    dialogRef.current?.querySelector<HTMLElement>('[role="radio"][aria-checked="true"]')?.focus();
    const back = opener.current;
    return () => {
      if (back instanceof HTMLElement) back.focus(); // focus returns to the button that opened the sheet
    };
  }, []);

  const onKeyDown = (event: React.KeyboardEvent): void => {
    if (event.key === 'Escape') {
      event.stopPropagation();
      props.onClose();
      return;
    }
    if (event.key === 'Tab') {
      // keep focus inside the modal
      const items = Array.from(
        dialogRef.current?.querySelectorAll<HTMLElement>('button:not([disabled]), input, [role="radio"]:not([aria-disabled="true"])') ?? [],
      ).filter((el) => el.tabIndex >= 0);
      const firstEl = items[0];
      const lastEl = items[items.length - 1];
      if (!firstEl || !lastEl) return;
      if (event.shiftKey && document.activeElement === firstEl) {
        event.preventDefault();
        lastEl.focus();
      } else if (!event.shiftKey && document.activeElement === lastEl) {
        event.preventDefault();
        firstEl.focus();
      }
    }
  };

  const group = (legendKey: TextKey, choices: EngineChoice[]): React.JSX.Element | null => {
    if (choices.length === 0) return null;
    const enabled = choices.filter((c) => c.available);
    const move = (from: EngineChoice, step: 1 | -1): void => {
      const i = enabled.indexOf(from);
      const next = enabled[(i + step + enabled.length) % enabled.length];
      if (!next) return;
      setEngine(next.info.id);
      dialogRef.current?.querySelector<HTMLElement>(`[data-engine="${next.info.id}"]`)?.focus();
    };
    const tabbable = enabled.find((c) => c.info.id === engine) ?? enabled[0];
    return (
      <fieldset className="ml-fieldset" role="radiogroup" aria-labelledby={`legend-${legendKey}`}>
        <legend className="ml-kicker" id={`legend-${legendKey}`}>{t(legendKey)}</legend>
        {choices.map((c) => {
          const id = c.info.id;
          const checked = id === engine;
          return (
            <div
              key={id}
              className="ml-option"
              role="radio"
              data-engine={id}
              aria-checked={checked}
              {...(c.available ? {} : { 'aria-disabled': true as const })}
              tabIndex={c === tabbable ? 0 : -1}
              onClick={() => c.available && setEngine(id)}
              onKeyDown={(e) => {
                if (e.key === ' ' || e.key === 'Enter') {
                  e.preventDefault();
                  if (c.available) setEngine(id);
                } else if (e.key === 'ArrowDown' || e.key === 'ArrowRight') {
                  e.preventDefault();
                  move(c, 1);
                } else if (e.key === 'ArrowUp' || e.key === 'ArrowLeft') {
                  e.preventDefault();
                  move(c, -1);
                }
              }}
            >
              <span className="ml-option__radio" aria-hidden="true" />
              <div className="ml-option__main">
                <span className="ml-option__name">{t(c.info.labelKey as TextKey)}</span>
                <span className="ml-option__meta">
                  <Tag>{c.info.language}</Tag>
                  <Tag>{runtimeTag(c.info.runtime)}</Tag>
                  {c.info.runtime === 'server' ? (
                    <span>{t('on_server')}</span>
                  ) : (
                    c.info.binaryBytes > 0 && (
                      <span>
                        {kb(c.info.binaryBytes, n)}
                        {gzipShown(c.info) > 0 ? ` · ${kb(gzipShown(c.info), n)} gzip` : ''}
                      </span>
                    )
                  )}
                  {id === defaultId && <Tag solid>{t('default')}</Tag>}
                </span>
                {id === 'net-webgpu' && c.available && (
                  <p className="ml-option__reason" data-webgpu-status={net.availability.webgpu === null ? 'checking' : net.availability.webgpu ? 'available' : 'unavailable'}>
                    <Icon name={net.availability.webgpu === false ? 'alert' : 'check'} />
                    <span>
                      {net.availability.webgpu === null
                        ? t('net.webgpu.checking')
                        : net.availability.webgpu
                          ? t('net.webgpu.available')
                          : t('net.webgpu.unavailable', { reason: net.availability.webgpuReason ?? '' })}
                    </span>
                  </p>
                )}
                {!c.available && (
                  <p className="ml-option__reason">
                    <Icon name="alert" />
                    <span>{t(c.reason === 'not-shipped' ? 'reason_not_shipped' : c.reason === 'variant' ? 'reason_variant' : 'reason_unsupported')}</span>
                  </p>
                )}
              </div>
            </div>
          );
        })}
      </fieldset>
    );
  };

  const chosen = props.choices.find((c) => c.info.id === engine);
  const nodes = NODE_LEVELS[level] ?? NODE_LEVELS[DEFAULT_LEVEL];
  const fill = `${(level / (NODE_LEVELS.length - 1)) * 100}%`;
  const isNet = engine !== null && isNetEngine(engine);
  const evaluation = evaluateSelection({ engine: engine ?? 'cpp-wasm', modelId, sims }, variant, net.availability);
  const focusModels = (): void => {
    const target = dialogRef.current?.querySelector<HTMLElement>(`[data-net-group="${MODEL_GROUP}"] input:checked`) ?? dialogRef.current?.querySelector<HTMLElement>(`[data-net-group="${MODEL_GROUP}"] input`);
    target?.focus();
    target?.scrollIntoView?.({ block: 'nearest' });
  };
  const browser = props.choices.filter((c) => c.info.runtime !== 'server');
  const server = props.choices.filter((c) => c.info.runtime === 'server');

  return createPortal(
    <div className="ml-overlay" onMouseDown={(e) => e.target === e.currentTarget && props.onClose()}>
      <section
        ref={dialogRef}
        className="ml-sheet"
        role="dialog"
        aria-modal="true"
        aria-labelledby="ml-sheet-title"
        onKeyDown={onKeyDown}
      >
        <div className="ml-sheet__grip" aria-hidden="true" />
        <div className="ml-sheet__head">
          <h2 className="ml-sheet__title" id="ml-sheet-title">{t('engine_title')}</h2>
          <button type="button" className="ml-btn ml-btn--quiet ml-btn--icon" aria-label={t('close')} onClick={props.onClose}>
            <Icon name="close" />
          </button>
        </div>
        {group('engines_browser', browser)}
        {group('engines_server', server)}
        {isNet && (
          <>
            <NetWarnings warnings={evaluation.warnings} live onAction={{ 'choose-model': focusModels, 'choose-other': focusModels }} />
            <ModelGroup variant={variant} all={listModels()} models={evaluation.compatibleModels} modelId={modelId} onChange={setModelId} />
            <SimsGroup sims={sims} onChange={setSims} />
          </>
        )}
        {!isNet && <p className="ml-hint" data-net-only-note>{t('net.classical.note')}</p>}
        {!isNet && <div className="ml-strength">
          <p className="ml-kicker" id="ml-strength-label">{t('strength')}</p>
          <div className="ml-strength__readout">
            <span className="ml-strength__level">{t(levelKey(level))}</span>
            <span className="ml-strength__nodes">
              {n(nodes ?? 0)} <small>{t('nodes_per_move')}</small>
            </span>
          </div>
          <input
            className="ml-slider"
            type="range"
            min={0}
            max={NODE_LEVELS.length - 1}
            step={1}
            value={level}
            style={{ ['--ml-slider-fill' as string]: fill }}
            aria-labelledby="ml-strength-label"
            aria-valuetext={`${t(levelKey(level))}, ${n(nodes ?? 0)}`}
            onChange={(e) => setLevel(Number(e.target.value))}
          />
          <div className="ml-strength__ticks ml-data" aria-hidden="true">
            {NODE_LEVELS.map((v, i) => (
              <span key={v} {...(i === level ? { 'aria-current': true } : {})}>
                {v >= 1000 ? `${v / 1000}k` : v}
              </span>
            ))}
          </div>
          <p className="ml-hint">
            <Icon name="tune" />
            <span>
              {t('strength_hint')}{' '}
              <span className="ml-data">≈ {chosen ? n(estimateMs(chosen.info.runtime, nodes ?? 0)) : '–'} ms</span>
            </span>
          </p>
        </div>}
        <div className="ml-sheet__actions">
          <button type="button" className="ml-btn" onClick={props.onClose}>{t('cancel')}</button>
          <button
            type="button"
            className="ml-btn ml-btn--primary"
            disabled={engine === null}
            onClick={() => engine && (isNetEngine(engine) ? props.onApply(engine, level, { modelId, sims }) : props.onApply(engine, level))}
          >
            {t('apply')}
          </button>
        </div>
      </section>
    </div>,
    document.body,
  );
}
