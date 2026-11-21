// V4: the source tree explorer of topic 1. An ARIA tree of the important directories of the repository and a filter by where the code runs.
// Every row can be selected (the detail panel then says what it is and links to GitHub); a folder can also be opened. A selected module
// marks its dependencies in the graph of the ten Gradle modules (wide layout). Design: spec v4-affordance-spec.md.
import { useId, useMemo, useRef, useState } from 'react';
import type { CSSProperties, KeyboardEvent, MouseEvent } from 'react';
import { Figure, FigureDetail, Manipulate, PLOT_BREAKPOINT_PX, Segmented, useMeasuredWidth } from '../kit';
import { Icon } from '../kit/icons';
import { Markup } from '../kit/markup';
import { sourceUrl } from '../kit/SourceChip';
import data from '../data/source-tree.json';
import { FigFoot, figLabel, useFigText } from './common';
import type { Catalog, Lookup } from './common';
import { GRAPH, WHERE, gitState, graphLayout, hasChildren, keyAction, kindOf, levelOf, matchCount, matchesFilter, moduleDeps, nearestVisible, pointerAction, visibleEntries } from './tree-model';
import type { RowKind, TreeAction, TreeData, TreeEntry, Where } from './tree-model';

const TREE = data as unknown as TreeData;

export interface TreeFigureProps {
  catalog: Catalog;
  number: readonly [number, number];
}

/** Kind icons of a row and the two Git tags; the shared kit icons (chevron, check mark, close) come from the kit. */
const ICON = {
  folder: <path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z" />,
  open: <path d="M3 18V7a2 2 0 0 1 2-2h4l2 2h7a2 2 0 0 1 2 2v1M3 18l2.5-7h16L19 18z" />,
  file: <path d="M6 3h8l4 4v14H6zM14 3v4h4" />,
  cube: <path d="M12 3l8 4.5v9L12 21l-8-4.5v-9zM4 7.5l8 4.5 8-4.5M12 12v9" />,
  nogit: <path d="M4 4l16 16M12 5a7 7 0 0 1 6.6 9.3M7 7a7 7 0 0 0 9.6 9.6" />,
  lfs: <path d="M12 4v12M7 11l5 5 5-5M5 20h14" />,
};

function TreeIcon({ name }: { name: keyof typeof ICON }): React.JSX.Element {
  return (
    <svg className="ml-icon" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
      {ICON[name]}
    </svg>
  );
}

const pathParts = (path: string): React.ReactNode[] =>
  path.split('/').map((part, i, all) => (i < all.length - 1 ? <span key={i}>{part}/<wbr /></span> : part));

function Tags({ entry, t }: { entry: TreeEntry; t: Lookup }): React.JSX.Element {
  const git = gitState(entry);
  return (
    <>
      {kindOf(entry) === 'module' && <span className="ml-fig-tree__tag ml-fig-tree__tag--module">{t('fig.tree.tag.module')}</span>}
      {git === 'ignored' && (
        <span className="ml-fig-tree__tag ml-fig-tree__tag--ignored">
          <TreeIcon name="nogit" />
          {t('fig.tree.tag.ignored')}
        </span>
      )}
      {git === 'lfs' && (
        <span className="ml-fig-tree__tag">
          <TreeIcon name="lfs" />
          {t('fig.tree.tag.lfs')}
        </span>
      )}
    </>
  );
}

export function TreeFigure({ catalog, number }: TreeFigureProps): React.JSX.Element {
  const f = useFigText(catalog);
  const { t, kit } = f;
  const [expanded, setExpanded] = useState<ReadonlySet<string>>(() => new Set());
  const [filter, setFilter] = useState<Where | 'all'>('all');
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const rows = useMemo(() => visibleEntries(TREE.entries, expanded), [expanded]);
  const [focusId, setFocusId] = useState<string>(() => TREE.entries[0]!.id);
  const list = useRef<HTMLUListElement>(null);
  const holder = useRef<HTMLDivElement>(null);
  const width = useMeasuredWidth(holder, '.ml-fig');
  const compact = width > 0 && width < PLOT_BREAKPOINT_PX;
  const graphId = useId();

  const selected = selectedId === null ? null : (TREE.entries.find((e) => e.id === selectedId) ?? null);
  const selectedModule = selected?.module ?? null;

  const focusRow = (id: string): void => {
    setFocusId(id);
    Array.from(list.current?.querySelectorAll<HTMLElement>('[data-entry]') ?? [])
      .find((el) => el.dataset.entry === id)
      ?.focus();
  };
  const apply = (action: TreeAction): void => {
    if (action.toggle) {
      const { id, open } = action.toggle;
      setExpanded((prev) => {
        const next = new Set(prev);
        if (open) next.add(id);
        else next.delete(id);
        return next;
      });
    }
    if (action.select !== undefined) setSelectedId(action.select);
    if (action.clear) setSelectedId(null);
    if (action.focus !== undefined) focusRow(action.focus);
  };
  /** The close button and Escape in the panel: the selection goes, the focus returns to its row (or to the row that now shows it). */
  const closeDetail = (): void => {
    if (selectedId !== null) focusRow(nearestVisible(TREE.entries, expanded, selectedId));
    setSelectedId(null);
  };

  const onClick = (event: MouseEvent<HTMLUListElement>): void => {
    const row = (event.target as HTMLElement).closest<HTMLElement>('[data-entry]');
    if (!row) return;
    const onChevron = (event.target as HTMLElement).closest('[data-toggle]') !== null;
    apply(pointerAction(TREE.entries, expanded, row.dataset.entry!, onChevron));
  };
  const onKeyDown = (event: KeyboardEvent<HTMLUListElement>): void => {
    const target = (event.target as HTMLElement).closest<HTMLElement>('[data-entry]');
    if (!target || event.altKey || event.ctrlKey || event.metaKey) return;
    if (event.key === 'Escape' && selectedId === null) return; // nothing to clear: leave Escape to the page
    const action = keyAction(TREE.entries, expanded, target.dataset.entry!, event.key);
    if (!action) return;
    event.preventDefault();
    apply(action);
  };

  const layout = useMemo(() => graphLayout(TREE), []);
  const deps = selectedModule === null ? null : moduleDeps(TREE, selectedModule);
  const edgeState = (from: string, to: string): 'active' | 'dim' | undefined => (selectedModule === null ? undefined : from === selectedModule || to === selectedModule ? 'active' : 'dim');
  const boxState = (m: string): 'active' | 'dim' | undefined =>
    selectedModule === null ? undefined : m === selectedModule || deps!.dependsOn.includes(m) || deps!.usedBy.includes(m) ? 'active' : 'dim';

  const filterOptions = [
    { value: 'all' as const, label: t('fig.tree.filter.all') },
    ...WHERE.map((w) => ({ value: w, label: t(`fig.tree.filter.${w}`) })),
  ];
  const names = (list_: readonly string[]): string => list_.join(', ');
  const kindLabel = (kind: RowKind): string => t(`fig.tree.kind.${kind}`);
  const total = TREE.entries.length;

  const detail = (e: TreeEntry): React.JSX.Element => {
    const git = gitState(e);
    const children = TREE.entries.filter((c) => c.parent === e.id).length;
    return (
      <FigureDetail title={e.path} kicker={kindLabel(kindOf(e))} closeLabel={kit('kit.detail.close')} onClose={closeDetail}>
        <p className="ml-fig-tree__dtext">{t(`fig.tree.note.${e.id}`)}</p>
        <dl className="ml-fig-tree__facts">
          <dt>{t('fig.tree.detail.runs')}</dt>
          <dd>
            {e.runsIn.length > 0 ? e.runsIn.map((w) => <span key={w} className="ml-fig-tree__tag">{t(`fig.tree.filter.${w}`)}</span>) : t('fig.tree.detail.runs.none')}
          </dd>
          <dt>{t('fig.tree.detail.git')}</dt>
          <dd>{t(`fig.tree.detail.git.${git}`)}</dd>
          {deps && selected && (
            <>
              <dt>{t('fig.tree.deps.label')}</dt>
              <dd>{deps.dependsOn.length > 0 ? names(deps.dependsOn) : t('fig.tree.deps.none')}</dd>
              <dt>{t('fig.tree.deps.usedby')}</dt>
              <dd>{deps.usedBy.length > 0 ? names(deps.usedBy) : t('fig.tree.deps.unused')}</dd>
            </>
          )}
          {children > 0 && (
            <>
              <dt>{t('fig.tree.detail.children')}</dt>
              <dd>{children}</dd>
            </>
          )}
        </dl>
        {git === 'ignored' ? (
          <p className="ml-fig-tree__local">{t('fig.tree.detail.local')}</p>
        ) : (
          <a className="ml-fig-tree__gh" href={sourceUrl(e.path)} target="_blank" rel="noreferrer" aria-label={t('fig.tree.detail.github.name', { path: e.path })}>
            <Icon name="ext" />
            <span>{t('fig.tree.detail.github')}</span>
          </a>
        )}
      </FigureDetail>
    );
  };

  return (
    <Figure
      id="fig-tree"
      labelWord={figLabel(kit, number[0], number[1])}
      title={t('fig.tree.title')}
      panel={
        <Manipulate>
          <Segmented label={t('fig.tree.filter.label')} value={filter} onChange={setFilter} options={filterOptions} />
        </Manipulate>
      }
      caption={<Markup text={t('fig.tree.caption')} />}
      foot={<FigFoot kit={kit} paths={['settings.gradle.kts', 'docs/architecture.md']} data="source-tree.json" />}
    >
      <div ref={holder} className="ml-fig-tree" data-layout={compact ? 'compact' : 'wide'} data-filter={filter}>
        <p className="ml-fig-tree__count" role="status">
          {filter === 'all' ? (
            <>
              <b>{total}</b> {t('fig.tree.count.all')}
            </>
          ) : (
            <>
              <b>
                {matchCount(TREE.entries, filter)} / {total}
              </b>{' '}
              {t(`fig.tree.count.filter.${filter}`)}
            </>
          )}
        </p>
        <ul ref={list} className="ml-fig-tree__list" role="tree" aria-label={t('fig.tree.tree.label')} onKeyDown={onKeyDown} onClick={onClick}>
          {rows.map((e) => {
            const parent = hasChildren(TREE.entries, e.id);
            const open = expanded.has(e.id);
            const kind = kindOf(e);
            const match = matchesFilter(e, filter);
            const level = levelOf(TREE.entries, e);
            return (
              <li
                key={e.id}
                className="ml-fig-tree__row"
                role="treeitem"
                data-entry={e.id}
                data-kind={kind}
                data-git={gitState(e)}
                data-match={match ? 'true' : 'false'}
                data-lvl={level - 1}
                aria-level={level}
                aria-expanded={parent ? open : undefined}
                aria-selected={e.id === selectedId}
                tabIndex={e.id === focusId ? 0 : -1}
                style={{ '--lvl': level - 1 } as CSSProperties}
                onFocus={() => setFocusId(e.id)}
              >
                <span className="ml-fig-tree__chev" data-toggle={parent ? '' : undefined} aria-hidden="true">
                  {parent && <Icon name="chev" />}
                </span>
                <span className="ml-fig-tree__kind" aria-hidden="true">
                  <TreeIcon name={kind === 'module' ? 'cube' : kind === 'file' ? 'file' : parent && open ? 'open' : 'folder'} />
                </span>
                <span className="ml-fig-tree__text">
                  <span className="ml-fig-tree__path">
                    <span className="ml-visually-hidden">{kindLabel(kind)} </span>
                    <code>{pathParts(e.path)}</code>
                    <Tags entry={e} t={t} />
                  </span>
                  <span className="ml-fig-tree__note">{t(`fig.tree.note.${e.id}`)}</span>
                  {!match && filter !== 'all' && <span className="ml-visually-hidden">{t('fig.tree.filter.nomatch', { where: t(`fig.tree.filter.${filter}`) })}</span>}
                </span>
                <span className="ml-fig-tree__mark" aria-hidden="true">
                  <Icon name="check" />
                </span>
              </li>
            );
          })}
        </ul>
        <div className="ml-fig-tree__side">
          {selected ? (
            detail(selected)
          ) : (
            <p className="ml-fig-tree__empty" data-empty="true">
              {t('fig.tree.detail.empty')}
            </p>
          )}
        </div>
        <div className="ml-fig-tree__legend">
          <span>
            <Icon name="chev" />
            {t('fig.tree.legend.folder')}
          </span>
          <span>
            <TreeIcon name="cube" />
            {t('fig.tree.legend.module')}
          </span>
          <span>
            <TreeIcon name="file" />
            {t('fig.tree.legend.file')}
          </span>
          <span>
            <span className="ml-fig-tree__tag ml-fig-tree__tag--ignored">
              <TreeIcon name="nogit" />
              {t('fig.tree.tag.ignored')}
            </span>
          </span>
          <span>
            <span className="ml-fig-tree__tag">
              <TreeIcon name="lfs" />
              {t('fig.tree.tag.lfs')}
            </span>
          </span>
        </div>
        {!compact && (
          <div className="ml-fig-tree__graph">
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
          </div>
        )}
      </div>
    </Figure>
  );
}

// the constants of the layout are also read by the tests
export { GRAPH };
