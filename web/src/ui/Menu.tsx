// The HUD menu of a small screen: a button, and a modal sheet that holds the settings and the secondary actions.
// The sheet is the dialog of useDialog (focus in, Tab trapped, Escape, focus back) with the page behind it inert.
// It also closes on a tap on the backdrop, on the close button, and on a swipe down on its top edge.
import { useRef } from 'react';
import { createPortal } from 'react-dom';
import { Icon, prefersReducedMotion } from './primitives';
import { useT } from './texts';
import { shouldDismissSwipe } from './touch-model';
import { useDialog } from './useDialog';
import './mobile.css';

export function MenuButton(props: {
  ref?: React.Ref<HTMLButtonElement>;
  expanded: boolean;
  controlsId: string;
  onClick: () => void;
  disabled?: boolean;
}): React.JSX.Element {
  const { t } = useT();
  return (
    <button
      ref={props.ref}
      type="button"
      className="ml-btn"
      aria-haspopup="dialog"
      aria-expanded={props.expanded}
      aria-controls={props.controlsId}
      disabled={props.disabled}
      onClick={props.onClick}
    >
      <Icon name="menu" />
      <span>{t('menu')}</span>
    </button>
  );
}

interface Pull {
  id: number;
  startY: number;
  startAt: number;
}

export function Menu(props: {
  id: string;
  onClose: () => void;
  /** True while another dialog (the engine sheet) stands above the menu. */
  inert?: boolean;
  /** The menu button: focus returns there on close. */
  returnFocusTo?: React.RefObject<HTMLElement | null>;
  children: React.ReactNode;
}): React.JSX.Element {
  const { t } = useT();
  const { ref, onKeyDown } = useDialog<HTMLElement>({ onEscape: props.onClose, isolate: true, returnFocusTo: props.returnFocusTo });
  const backdropDown = useRef(false);
  const pull = useRef<Pull | null>(null);
  const titleId = `${props.id}-title`;

  const move = (dy: number, animate: boolean): void => {
    const el = ref.current;
    if (!el) return;
    el.style.transition = animate && !prefersReducedMotion() ? 'transform 160ms ease-out' : 'none';
    el.style.transform = dy > 0 ? `translateY(${dy}px)` : '';
  };
  const onDown = (event: React.PointerEvent<HTMLElement>): void => {
    if (event.button !== 0 && event.pointerType === 'mouse') return;
    pull.current = { id: event.pointerId, startY: event.clientY, startAt: event.timeStamp };
    // Capturing would take the click from the close button inside the grab area.
    if (!(event.target as Element).closest('button')) event.currentTarget.setPointerCapture?.(event.pointerId);
  };
  const onMove = (event: React.PointerEvent<HTMLElement>): void => {
    const p = pull.current;
    if (p && p.id === event.pointerId) move(event.clientY - p.startY, false);
  };
  const onUp = (event: React.PointerEvent<HTMLElement>): void => {
    const p = pull.current;
    pull.current = null;
    if (!p || p.id !== event.pointerId) return;
    const dy = event.clientY - p.startY;
    if (shouldDismissSwipe(dy, event.timeStamp - p.startAt)) props.onClose();
    else move(0, true);
  };

  return createPortal(
    <div
      className="ml-overlay ml-overlay--menu"
      onPointerDown={(e) => {
        backdropDown.current = e.target === e.currentTarget;
      }}
      onClick={(e) => {
        if (e.target === e.currentTarget && backdropDown.current) props.onClose();
        backdropDown.current = false;
      }}
      {...(props.inert ? { inert: true } : {})}
    >
      <section
        ref={ref}
        id={props.id}
        className="ml-sheet ml-menu"
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        onKeyDown={onKeyDown}
      >
        <div
          className="ml-menu__grab"
          data-menu-grab=""
          onPointerDown={onDown}
          onPointerMove={onMove}
          onPointerUp={onUp}
          onPointerCancel={() => {
            pull.current = null;
            move(0, true);
          }}
        >
          <div className="ml-sheet__grip" aria-hidden="true" />
          <div className="ml-sheet__head">
            <h2 className="ml-sheet__title" id={titleId}>{t('menu')}</h2>
            <button type="button" className="ml-btn ml-btn--quiet ml-btn--icon" data-menu-close="" aria-label={t('close')} onClick={props.onClose}>
              <Icon name="close" />
            </button>
          </div>
        </div>
        <div className="ml-menu__body">{props.children}</div>
      </section>
    </div>,
    document.body,
  );
}
