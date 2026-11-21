// Figure (design notes, section 6 "Figure"): the framed notebook cell. From top to bottom: label and title, control panel, one narration
// line (the only live region), the stage, an optional detail panel, caption, chips. The order never changes.
import { useId } from 'react';
import type { KeyboardEvent, ReactNode } from 'react';
import { Icon } from './icons';

/** One narration: the step label (when the figure shows one) and the sentence. */
export interface StatusLine {
  step?: string;
  text: string;
}

export interface FigureProps {
  /** Becomes the id prefix; the title is `<id>-title`. */
  id: string;
  /** The word before the number (`kit.fig.label`) and the number, for example "Fig." and 3. */
  labelWord?: string;
  number?: number;
  title: string;
  /** Control panel: `<Manipulate>`. */
  panel?: ReactNode;
  /** The narration line. Give it as soon as the figure has steps, so that the live region exists before it changes. */
  status?: {
    step?: string;
    text: string;
    all?: readonly StatusLine[];
    /** False switches the live region off (`aria-live="off"`), for example while a playback runs; the default is true (`polite`). */
    live?: boolean;
  };
  /** The plot, board or diagram, then legend and readouts. */
  children: ReactNode;
  /** Two columns (20rem and the rest) from the figure breakpoint. */
  split?: boolean;
  /** A `<FigureDetail>`; it stands below the stage, never over it. */
  detail?: ReactNode;
  caption?: ReactNode;
  /** Source chips and data chips. */
  foot?: ReactNode;
}

export function Figure({ id, labelWord, number, title, panel, status, children, split, detail, caption, foot }: FigureProps): React.JSX.Element {
  const titleId = `${id}-title`;
  return (
    <figure className="ml-fig" id={id} aria-labelledby={titleId}>
      <div className="ml-fig__head">
        {labelWord !== undefined && (
          <span className="ml-fig__label">
            {labelWord}
            {number !== undefined ? ` ${number}` : ''}
          </span>
        )}
        <h3 className="ml-fig__title" id={titleId}>
          {title}
        </h3>
      </div>
      {panel}
      {status && (
        <div className="ml-fig__status">
          <div className="ml-fig__status-line" role="status" aria-live={status.live === false ? 'off' : 'polite'}>
            {status.step && <span className="ml-fig__step">{status.step}</span>}
            <span>{status.text}</span>
          </div>
          {/* The height of the status area is that of the longest narration of the figure in this language at this width: all texts stand in the
              one grid cell of the live line, the others hidden from the eye and from screen readers, so that the figure does not jump from step to step. */}
          {status.all?.map((line, i) => (
            <div key={i} className="ml-fig__status-line ml-fig__status-line--ghost" aria-hidden="true">
              {line.step && <span className="ml-fig__step">{line.step}</span>}
              <span>{line.text}</span>
            </div>
          ))}
        </div>
      )}
      <div className={split ? 'ml-fig__stage ml-fig__stage--split' : 'ml-fig__stage'}>{children}</div>
      {detail}
      {(caption || foot) && (
        <figcaption>
          {caption && <p className="ml-fig__caption">{caption}</p>}
          {foot && <div className="ml-fig__foot">{foot}</div>}
        </figcaption>
      )}
    </figure>
  );
}

export interface FigureDetailProps {
  /** The panel is named by its title. */
  title: string;
  /** Small label above the title (`kit.node.kind.*`). */
  kicker?: string;
  children?: ReactNode;
  chips?: ReactNode;
  /** Accessible name of the close button (`kit.detail.close`). */
  closeLabel: string;
  onClose: () => void;
  /** The id that `aria-controls` of the boxes points to. */
  id?: string;
}

/** The detail panel of a box. Escape and the close button call `onClose`; the caller's box takes the focus back (BoxDiagram does). */
export function FigureDetail({ title, kicker, children, chips, closeLabel, onClose, id }: FigureDetailProps): React.JSX.Element {
  const own = useId();
  const titleId = `${own}-title`;
  const onKeyDown = (event: KeyboardEvent<HTMLElement>): void => {
    if (event.key !== 'Escape') return;
    event.stopPropagation();
    onClose();
  };
  return (
    <section className="ml-fig__detail" id={id} aria-labelledby={titleId} onKeyDown={onKeyDown}>
      {kicker && <p className="ml-kicker">{kicker}</p>}
      <h4 className="ml-fig__detail-title" id={titleId}>
        {title}
      </h4>
      {children && <div className="ml-fig__detail-text">{children}</div>}
      {chips && <div className="ml-fig__detail-chips">{chips}</div>}
      <button type="button" className="ml-btn ml-btn--icon ml-btn--quiet ml-fig__detail-close" aria-label={closeLabel} onClick={onClose}>
        <Icon name="close" />
      </button>
    </section>
  );
}

/** One entry of a legend: the mark (an inline SVG) and its text. */
export function LegendItem({ mark, children }: { mark: ReactNode; children: ReactNode }): React.JSX.Element {
  return (
    <span className="ml-fig-legend__item">
      <svg viewBox="0 0 32 16" aria-hidden="true" focusable="false">
        {mark}
      </svg>
      {children}
    </span>
  );
}

export function Legend({ children }: { children: ReactNode }): React.JSX.Element {
  return <div className="ml-fig-legend">{children}</div>;
}

/** A source chip or a data chip in the foot of a figure (design notes, section 6, "Source chip and data chip"). */
export function Chip({ href, prefix, children, data }: { href?: string; prefix: string; children: string; data?: boolean }): React.JSX.Element {
  const body = (
    <span className="ml-fig-chip__pill">
      <Icon name={data ? 'data' : 'code'} />
      <span className="ml-visually-hidden">{prefix} </span>
      <span>{children}</span>
    </span>
  );
  return href ? (
    <a className={data ? 'ml-fig-chip ml-fig-chip--data' : 'ml-fig-chip'} href={href} rel="noreferrer">
      {body}
    </a>
  ) : (
    <span className={data ? 'ml-fig-chip ml-fig-chip--data' : 'ml-fig-chip'}>{body}</span>
  );
}
