// BoxDiagram (design notes, section 6 "BoxDiagram" and section 7): HTML buttons over an SVG edge layer in one coordinate system, wide
// and compact layout (figure width below 640 px), data edges and control edges that differ by line, head and marker, not by colour.
// The positions come from layoutBoxes (box-layout.ts). The component holds no user text: every label arrives as a prop.
import { useEffect, useMemo, useRef } from 'react';
import { useMediaQuery } from '../../ui/useMedia';
import { layoutBoxes, headPath, pathOf, percent } from './box-layout';
import type { ArchData, EdgeKind, LayoutMode } from './box-layout';
import { PLOT_BREAKPOINT_PX } from './plot-scale';
import { useMeasuredWidth } from './Plot';

export type BoxKind = 'component' | 'store' | 'external';
export type Flow = 'data' | 'control' | 'both';

export interface BoxDiagramLabels {
  /** Accessible name of the diagram group (the figure title is a good choice). */
  diagram: string;
  /** Accessible name of the numbered edge list (`fig.<id>.keylist`). */
  keylist: string;
  /** Spoken word before an edge row of each kind (`kit.edge.data`, `kit.edge.control`). */
  data: string;
  control: string;
}

export interface BoxDiagramProps {
  data: ArchData;
  labels: BoxDiagramLabels;
  /** `auto` (default) switches at a figure width of 640 px. */
  layout?: 'auto' | LayoutMode;
  /** Region label per `runsIn` value; default the value itself (data, not copy). */
  groupLabels?: Readonly<Record<string, string>>;
  /** Replaces the label of a box (a translation). */
  boxLabels?: Readonly<Record<string, string>>;
  /** Replaces the label of a box in the compact layout only (the `.short` variant of its key). */
  shortBoxLabels?: Readonly<Record<string, string>>;
  /** Wide layout: the regions (group ids) of each band, side by side; see `LayoutOptions` in box-layout.ts. */
  bands?: readonly (readonly string[])[];
  /** Second line of a box, wide layout only, monospace. */
  boxTech?: Readonly<Record<string, string>>;
  kinds?: Readonly<Record<string, BoxKind>>;
  /** Replaces the label of an edge, by edge index. */
  edgeLabels?: Readonly<Record<number, string>>;
  /** Boxes and edges (by index) of the current step. Undefined: idle, nothing has a state. Anything else is dimmed. */
  activeBoxes?: readonly string[];
  activeEdges?: readonly number[];
  /** Edges of the other kind are dimmed, not removed. */
  flow?: Flow;
  /** The box whose detail panel is open, and the callback for open, toggle and Escape. */
  openId?: string | null;
  onOpen?: (id: string | null) => void;
  /** Id of the detail panel, for aria-controls. */
  detailId?: string;
  /** The detail panel of the open box. It stands directly under the diagram, before the list of edges, and scrolls into view when it opens. */
  detail?: React.ReactNode;
}

const boxEl = (root: HTMLElement | null, id: string): HTMLElement | undefined =>
  Array.from(root?.querySelectorAll<HTMLElement>('[data-box]') ?? []).find((el) => el.dataset.box === id);

const flowShows = (flow: Flow, kind: EdgeKind): boolean => flow === 'both' || flow === kind;

export function BoxDiagram(props: BoxDiagramProps): React.JSX.Element {
  const { data, labels, layout = 'auto', groupLabels, boxLabels, shortBoxLabels, bands, boxTech, kinds, edgeLabels, activeBoxes, activeEdges, flow = 'both', openId = null, onOpen, detailId, detail } = props;
  const holder = useRef<HTMLDivElement>(null);
  const figureWidth = useMeasuredWidth(holder, '.ml-fig');
  const reduced = useMediaQuery('(prefers-reduced-motion: reduce)', false);
  const mode: LayoutMode = layout === 'auto' ? (figureWidth > 0 && figureWidth < PLOT_BREAKPOINT_PX ? 'compact' : 'wide') : layout;
  const compact = mode === 'compact';
  // The layout needs only the length of the texts; the key makes it independent of the identity of the props.
  const edgeTextKey = data.edges.map((e, i) => edgeLabels?.[i] ?? e.label ?? '').join('\u0001');
  const groupTextKey = [...new Set(data.boxes.map((b) => b.runsIn ?? ''))].map((g) => groupLabels?.[g] ?? g).join('\u0001');
  const bandsKey = JSON.stringify(bands ?? []);
  const laid = useMemo(
    () =>
      layoutBoxes(data, mode, {
        bands: JSON.parse(bandsKey) as string[][],
        edgeText: Object.fromEntries(edgeTextKey.split('\u0001').map((t, i) => [i, t])),
        groupText: Object.fromEntries([...new Set(data.boxes.map((b) => b.runsIn ?? ''))].map((g, i) => [g, groupTextKey.split('\u0001')[i] ?? g])),
      }),
    [data, mode, edgeTextKey, groupTextKey, bandsKey],
  );
  const boxById = useMemo(() => new Map(data.boxes.map((b) => [b.id, b])), [data]);
  const nameOf = (id: string): string => (compact ? shortBoxLabels?.[id] : undefined) ?? boxLabels?.[id] ?? boxById.get(id)?.label ?? id;
  const edgeText = (index: number): string | undefined => edgeLabels?.[index] ?? data.edges[index]?.label;

  const boxState = (id: string): 'active' | 'dim' | undefined => (activeBoxes === undefined ? undefined : activeBoxes.includes(id) ? 'active' : 'dim');
  const edgeState = (index: number, kind: EdgeKind): 'active' | 'dim' | undefined => {
    if (!flowShows(flow, kind)) return 'dim';
    return activeEdges === undefined ? undefined : activeEdges.includes(index) ? 'active' : 'dim';
  };

  // Focus returns to the box when its detail panel closes (design notes, section 8: Escape and the close button).
  const lastOpen = useRef<string | null>(null);
  useEffect(() => {
    if (openId !== null) {
      lastOpen.current = openId;
      return;
    }
    const previous = lastOpen.current;
    lastOpen.current = null;
    const active = document.activeElement;
    if (previous && (!active || active === document.body)) boxEl(holder.current, previous)?.focus();
  }, [openId]);

  // The detail panel opens under the diagram; bring it into view when it is off screen (design review B2).
  const detailHolder = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (openId === null) return;
    const panel = detailHolder.current;
    if (panel && typeof panel.scrollIntoView === 'function') panel.scrollIntoView({ block: 'nearest', behavior: reduced ? 'auto' : 'smooth' });
  }, [openId, reduced]);

  return (
    <div ref={holder} className="ml-fig-boxwrap" data-layout={mode}>
      <div
        className="ml-fig-box"
        role="group"
        aria-label={labels.diagram}
        style={{ aspectRatio: `${laid.width} / ${laid.height}` }}
        onKeyDown={(event) => {
          if (event.key !== 'Escape' || openId === null) return;
          event.stopPropagation();
          onOpen?.(null);
          boxEl(holder.current, openId)?.focus();
        }}
      >
        {laid.groups.map((g) => (
          <div key={g.id} className="ml-fig-box__group" style={percent(laid, g)} data-group={g.id}>
            <span className="ml-fig-box__grouplabel">{groupLabels?.[g.id] ?? g.id}</span>
          </div>
        ))}
        <svg className="ml-fig-box__edges" viewBox={`0 0 ${laid.width} ${laid.height}`} aria-hidden="true" focusable="false">
          {laid.edges.map((e) => {
            const state = edgeState(e.index, e.kind);
            const d = pathOf(e.points);
            return (
              <g key={e.index} data-edge={e.index} data-kind={e.kind}>
                <path className={e.kind === 'control' ? 'ml-fig-edge ml-fig-edge--control' : 'ml-fig-edge'} d={d} data-state={state} />
                <path className={`ml-fig-edge__head ml-fig-edge__head--${e.kind}`} d={headPath(e.points, e.kind, state === 'active' ? 11 : 9)} data-state={state} />
                {state === 'active' && !reduced && <circle className="ml-fig-edge__dot" r={4.5} style={{ offsetPath: `path("${d}")` }} />}
              </g>
            );
          })}
        </svg>
        {laid.nodes.map((n) => {
          const kind = kinds?.[n.id] ?? 'component';
          const tech = !compact ? boxTech?.[n.id] : undefined;
          return (
            <button
              key={n.id}
              type="button"
              className={`ml-fig-box__node${kind === 'component' ? '' : ` ml-fig-box__node--${kind}`}`}
              style={percent(laid, n)}
              data-box={n.id}
              data-state={boxState(n.id)}
              aria-expanded={onOpen ? openId === n.id : undefined}
              aria-controls={onOpen ? detailId : undefined}
              onClick={() => onOpen?.(openId === n.id ? null : n.id)}
            >
              <span>{nameOf(n.id)}</span>
              {tech && <span className="ml-fig-box__tech">{tech}</span>}
            </button>
          );
        })}
        {laid.edges.map((e) => {
          const state = edgeState(e.index, e.kind);
          const at = { left: `${(e.mid.x / laid.width) * 100}%`, top: `${(e.mid.y / laid.height) * 100}%` };
          if (e.numbered) {
            return (
              <span key={e.index} className={`ml-fig-num ml-fig-box__num${e.kind === 'control' ? ' ml-fig-num--control' : ''}`} style={at} data-state={state} data-edge={e.index} aria-hidden="true">
                <span>{e.index + 1}</span>
              </span>
            );
          }
          const label = e.label;
          return label ? (
            <span
              key={e.index}
              className="ml-fig-box__edgelabel"
              style={{ ...at, width: `${(label.w / laid.width) * 100}%` }}
              data-state={state}
              data-edge={e.index}
              aria-hidden="true"
            >
              {label.lines.join('\n')}
            </span>
          ) : null;
        })}
      </div>
      <div ref={detailHolder} className="ml-fig-box__detail">
        {detail}
      </div>
      <ol className={laid.edges.some((e) => e.numbered) ? 'ml-fig-box__key' : 'ml-fig-box__key ml-fig-box__key--hidden'} aria-label={labels.keylist}>
        {laid.edges.map((e) => {
          const text = edgeText(e.index);
          return (
            <li key={e.index} className={e.numbered ? undefined : 'ml-fig-box__keyrow--hidden'} data-state={edgeState(e.index, e.kind)} data-kind={e.kind}>
              {e.numbered && (
                <span className={`ml-fig-num${e.kind === 'control' ? ' ml-fig-num--control' : ''}`} data-state={edgeState(e.index, e.kind)} aria-hidden="true">
                  <span>{e.index + 1}</span>
                </span>
              )}
              <span className="ml-fig-box__keytext">
                <span className="ml-visually-hidden">{e.kind === 'control' ? labels.control : labels.data}: </span>
                {`${nameOf(e.from)} → ${nameOf(e.to)}${text ? `: ${text}` : ''}`}
              </span>
            </li>
          );
        })}
      </ol>
    </div>
  );
}
