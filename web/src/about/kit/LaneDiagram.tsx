// LaneDiagram and GhostCell (design spec of figure J7, section 12). Not part of kit/index.ts yet: the figure imports them from here, and the
// styles are in figures/boundary.css (imported by the figure), so that no shared file changes.
//
// LaneDiagram: lanes in the order of a call, with a styled wall between two lanes and one optional region that holds some of them. Vertical
// below 40rem of figure width, horizontal from there; the DOM is the same, a container query does the rest. The lanes and walls are not buttons.
// GhostCell: all variants of a short text in one grid cell; the largest sets the size, the others are hidden from the eye and from screen readers,
// so that a switch between variants (or a step) does not change the layout.
import type { ReactNode } from 'react';

export interface GhostCellProps<K extends string> {
  /** The key of the visible item. */
  active: K;
  items: readonly { readonly key: K; readonly node: ReactNode }[];
  /** The element of the cell and of each item (`span` by default). */
  as?: 'span' | 'div' | 'dd';
  itemAs?: 'span' | 'div';
  className?: string;
  /** Extra attributes of the cell, for example `data-r="worker"`. */
  attrs?: Record<`data-${string}`, string>;
}

export function GhostCell<K extends string>({ active, items, as: Tag = 'span', itemAs: Item = 'span', className, attrs }: GhostCellProps<K>): React.JSX.Element {
  return (
    <Tag className={className ? `ml-fig-ghostcell ${className}` : 'ml-fig-ghostcell'} {...attrs}>
      {items.map(({ key, node }) => {
        const on = key === active;
        return (
          <Item key={key} data-key={key} {...(on ? {} : { 'data-off': '', 'aria-hidden': true })}>
            {node}
          </Item>
        );
      })}
    </Tag>
  );
}

/** A text that gets bold in some state without changing the layout: a hidden bold copy stands in the same grid cell and reserves the larger size (a bold name can wrap one line more). */
export function StableBold({ children }: { children: string }): React.JSX.Element {
  return (
    <span className="ml-fig-stablebold">
      <span>{children}</span>
      <span data-ghost aria-hidden="true">{children}</span>
    </span>
  );
}

export function LaneDiagram({ label, children }: { label: string; children: ReactNode }): React.JSX.Element {
  return (
    <ol className="ml-fig-lanes" aria-label={label}>
      {children}
    </ol>
  );
}

export interface LaneProps {
  /** `data-lane`. */
  id: string;
  name: string;
  /** Absent at step 0; the active lane gets `aria-current="step"`. */
  state?: 'active' | 'dim';
  children?: ReactNode;
}

export function Lane({ id, name, state, children }: LaneProps): React.JSX.Element {
  return (
    <li className="ml-fig-lanes__lane" data-lane={id} {...(state ? { 'data-state': state } : {})} {...(state === 'active' ? { 'aria-current': 'step' as const } : {})}>
      <span className="ml-fig-lanes__name">
        <StableBold>{name}</StableBold>
      </span>
      {children}
    </li>
  );
}

const ARROW = (
  <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">
    <path d="M4 12h15M13 6l6 6-6 6" />
  </svg>
);

export interface WallProps {
  /** `data-wall`. */
  id: string;
  /** `data-kind` of the line: `msg`, `none`, `convert`, `slot`. */
  kind: string;
  name: string;
  /** The tag below the name; a `GhostCell` when it differs per variant. */
  tag: ReactNode;
  /** The direction of the crossing at this step; the badge is always in the layout and hidden when absent. */
  dir?: 'in' | 'out' | null;
}

export function Wall({ id, kind, name, tag, dir }: WallProps): React.JSX.Element {
  return (
    <li className="ml-fig-lanes__wall" data-wall={id} {...(dir ? { 'data-dir': dir } : {})}>
      <span className="ml-fig-lanes__wname">
        <StableBold>{name}</StableBold>
      </span>
      <span className="ml-fig-lanes__line" data-kind={kind} />
      <span className="ml-fig-lanes__dir">{ARROW}</span>
      <span className="ml-fig-lanes__wtag">{tag}</span>
    </li>
  );
}

export function LaneGroup({ label, id, children }: { label: string; id: string; children: ReactNode }): React.JSX.Element {
  return (
    <li className="ml-fig-lanes__group" role="group" aria-labelledby={id}>
      <span className="ml-fig-lanes__glabel" id={id}>
        {label}
      </span>
      <ol className="ml-fig-lanes__inner" role="list">
        {children}
      </ol>
    </li>
  );
}
