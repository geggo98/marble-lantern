// V4: the source tree explorer of topic 1. An ARIA tree of the important directories of the repository, a filter by where the code
// runs, and the dependency graph of the ten Gradle modules (wide layout) or two text lines for the selected module (compact layout).
import { useId, useMemo, useRef, useState } from 'react';
import type { KeyboardEvent } from 'react';
import { Figure, Manipulate, PLOT_BREAKPOINT_PX, Segmented, useMeasuredWidth } from '../kit';
import { Icon } from '../kit/icons';
import { Markup } from '../kit/markup';
import data from '../data/source-tree.json';
import { FigFoot, figLabel, useFigText } from './common';
import type { Catalog } from './common';
import { GRAPH, WHERE, graphLayout, hasChildren, levelOf, matchesFilter, moduleDeps, visibleEntries } from './tree-model';
import type { TreeData, TreeEntry, Where } from './tree-model';

const TREE = data as unknown as TreeData;

export interface TreeFigureProps {
  catalog: Catalog;
  number: readonly [number, number];
}

const TAG_KEY = { ignored: 'fig.tree.tag.ignored', lfs: 'fig.tree.tag.lfs' } as const;

export function TreeFigure({ catalog, number }: TreeFigureProps): React.JSX.Element {
  const f = useFigText(catalog);
  const { t, kit } = f;
  const [expanded, setExpanded] = useState<ReadonlySet<string>>(() => new Set());
  const [filter, setFilter] = useState<Where | 'all'>('all');
  const [selected, setSelected] = useState<string | null>(null);
  const rows = useMemo(() => visibleEntries(TREE.entries, expanded), [expanded]);
  const [focusId, setFocusId] = useState<string>(() => TREE.entries[0]!.id);
  const list = useRef<HTMLUListElement>(null);
  const holder = useRef<HTMLDivElement>(null);
  const width = useMeasuredWidth(holder, '.ml-fig');
  const compact = width > 0 && width < PLOT_BREAKPOINT_PX;
  const graphId = useId();

  const focusRow = (id: string): void => {
    setFocusId(id);
    Array.from(list.current?.querySelectorAll<HTMLElement>('[data-entry]') ?? [])
      .find((el) => el.dataset.entry === id)
      ?.focus();
  };
  const toggle = (e: TreeEntry, open: boolean): void => {
    setExpanded((prev) => {
      const next = new Set(prev);
      if (open) next.add(e.id);
      else next.delete(e.id);
      return next;
    });
  };
  const activate = (e: TreeEntry): void => {
    if (e.module !== undefined) setSelected((prev) => (prev === e.module ? null : e.module!));
    if (hasChildren(TREE.entries, e.id)) toggle(e, !expanded.has(e.id));
  };
  const onKeyDown = (event: KeyboardEvent<HTMLUListElement>): void => {
    const target = (event.target as HTMLElement).closest<HTMLElement>('[data-entry]');
    if (!target || event.altKey || event.ctrlKey || event.metaKey) return;
    const index = rows.findIndex((r) => r.id === target.dataset.entry);
    const row = rows[index];
    if (!row) return;
    const go = (to: TreeEntry | undefined): void => {
      event.preventDefault();
      if (to) focusRow(to.id);
    };
    switch (event.key) {
      case 'ArrowDown':
        return go(rows[index + 1]);
      case 'ArrowUp':
        return go(rows[index - 1]);
      case 'Home':
        return go(rows[0]);
      case 'End':
        return go(rows[rows.length - 1]);
      case 'ArrowRight':
        event.preventDefault();
        if (hasChildren(TREE.entries, row.id)) {
          if (!expanded.has(row.id)) toggle(row, true);
          else focusRow(rows[index + 1]!.id);
        }
        return;
      case 'ArrowLeft':
        event.preventDefault();
        if (hasChildren(TREE.entries, row.id) && expanded.has(row.id)) toggle(row, false);
        else if (row.parent !== null) focusRow(row.parent);
        return;
      case 'Enter':
      case ' ':
        event.preventDefault();
        activate(row);
    }
  };

  const layout = useMemo(() => graphLayout(TREE), []);
  const deps = selected === null ? null : moduleDeps(TREE, selected);
  const edgeState = (from: string, to: string): 'active' | 'dim' | undefined => (selected === null ? undefined : from === selected || to === selected ? 'active' : 'dim');
  const boxState = (m: string): 'active' | 'dim' | undefined =>
    selected === null ? undefined : m === selected || deps!.dependsOn.includes(m) || deps!.usedBy.includes(m) ? 'active' : 'dim';

  const filterOptions = [
    { value: 'all' as const, label: t('fig.tree.filter.all') },
    ...WHERE.map((w) => ({ value: w, label: t(`fig.tree.filter.${w}`) })),
  ];
  const names = (list_: readonly string[]): string => list_.join(', ');

  return (
    <Figure
      id="fig-tree"
      labelWord={figLabel(kit, number[0], number[1])}
      title={t('fig.tree.title')}
      split={!compact}
      panel={
        <Manipulate>
          <Segmented label={t('fig.tree.filter.label')} value={filter} onChange={setFilter} options={filterOptions} />
        </Manipulate>
      }
      caption={<Markup text={t('fig.tree.caption')} />}
      foot={<FigFoot kit={kit} paths={['settings.gradle.kts', 'docs/architecture.md']} data="source-tree.json" />}
    >
      <div ref={holder} className="ml-fig-tree">
        <ul ref={list} className="ml-fig-tree__list" role="tree" aria-label={t('fig.tree.tree.label')} onKeyDown={onKeyDown}>
          {rows.map((e) => {
            const parent = hasChildren(TREE.entries, e.id);
            const open = expanded.has(e.id);
            return (
              <li
                key={e.id}
                className="ml-fig-tree__row"
                role="treeitem"
                data-entry={e.id}
                data-dim={matchesFilter(e, filter) ? undefined : 'true'}
                data-selected={e.module !== undefined && e.module === selected ? 'true' : undefined}
                aria-level={levelOf(TREE.entries, e)}
                aria-expanded={parent ? open : undefined}
                aria-selected={e.module !== undefined ? e.module === selected : undefined}
                tabIndex={e.id === focusId ? 0 : -1}
                style={{ paddingInlineStart: `calc(0.5rem + ${levelOf(TREE.entries, e) - 1} * 1.25rem)` }}
                onFocus={() => setFocusId(e.id)}
                onClick={() => {
                  setFocusId(e.id);
                  activate(e);
                }}
              >
                <span className="ml-fig-tree__chev" data-open={parent && open ? 'true' : undefined} aria-hidden="true">
                  {parent && <Icon name="chev" />}
                </span>
                <span className="ml-fig-tree__text">
                  <span className="ml-fig-tree__path">
                    <code>{e.path.split('/').map((part, i, all) => (i < all.length - 1 ? <span key={i}>{part}/<wbr /></span> : part))}</code>
                    {e.tags.map((tag) => (
                      <span key={tag} className="ml-fig-tag">
                        {t(TAG_KEY[tag])}
                      </span>
                    ))}
                  </span>
                  <span className="ml-fig-tree__note">{t(`fig.tree.note.${e.id}`)}</span>
                </span>
              </li>
            );
          })}
        </ul>
        {compact && deps && (
          <div className="ml-fig-deps" aria-live="polite">
            <p>
              <strong>{selected}</strong>
            </p>
            <p>
              <span>{t('fig.tree.deps.label')}</span> <code>{deps.dependsOn.length > 0 ? names(deps.dependsOn) : t('fig.tree.deps.none')}</code>
            </p>
            {deps.usedBy.length > 0 && (
              <p>
                <span>{t('fig.tree.deps.usedby')}</span> <code>{names(deps.usedBy)}</code>
              </p>
            )}
          </div>
        )}
      </div>
      {!compact && (
        <div className="ml-fig-deps-wrap">
          <svg className="ml-fig-dep" viewBox={`0 0 ${layout.width} ${layout.height}`} role="img" aria-labelledby={`${graphId}-t ${graphId}-d`}>
            <title id={`${graphId}-t`}>{t('fig.tree.deps.title')}</title>
            <desc id={`${graphId}-d`}>{f.plain('fig.tree.desc')}</desc>
            {layout.edges.map((e) => {
              const head = 6;
              const dx = e.x2 - e.x1;
              const dy = e.y2 - e.y1;
              const len = Math.hypot(dx, dy) || 1;
              const ux = dx / len;
              const uy = dy / len;
              const bx = e.x2 - ux * head * 1.6;
              const by = e.y2 - uy * head * 1.6;
              return (
                <g key={`${e.from}-${e.to}`} data-state={edgeState(e.from, e.to)}>
                  <line className="ml-fig-dep__edge" data-state={edgeState(e.from, e.to)} x1={e.x1} y1={e.y1} x2={bx} y2={by} />
                  <path className="ml-fig-dep__head" data-state={edgeState(e.from, e.to)} d={`M${e.x2} ${e.y2}L${bx - uy * head} ${by + ux * head}L${bx + uy * head} ${by - ux * head}Z`} />
                </g>
              );
            })}
            {layout.boxes.map((b) => (
              <g key={b.module} data-state={boxState(b.module)} data-module={b.module}>
                <rect className="ml-fig-dep__box" data-state={boxState(b.module)} x={b.x} y={b.y} width={b.w} height={b.h} rx={5} />
                <text className="ml-fig-dep__label" data-state={boxState(b.module)} x={b.x + b.w / 2} y={b.y + b.h / 2}>
                  {b.module}
                </text>
              </g>
            ))}
          </svg>
          <div className="ml-fig-deps" aria-live="polite">
            {deps ? (
              <>
                <p>
                  <strong>{selected}</strong>
                </p>
                <p>
                  <span>{t('fig.tree.deps.label')}</span> <code>{deps.dependsOn.length > 0 ? names(deps.dependsOn) : t('fig.tree.deps.none')}</code>
                </p>
                {deps.usedBy.length > 0 && (
                  <p>
                    <span>{t('fig.tree.deps.usedby')}</span> <code>{names(deps.usedBy)}</code>
                  </p>
                )}
              </>
            ) : null}
          </div>
        </div>
      )}
    </Figure>
  );
}

// the constants of the layout are also read by the tests
export { GRAPH };
