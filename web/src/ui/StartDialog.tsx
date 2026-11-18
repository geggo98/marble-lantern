// The dialog that stands before a game: "Do you want to begin?", "Continue this game?" for a game link, and the error
// for a link that does not work. A modal dialog: focus moves in, Tab stays inside, focus returns to where it was.
// Escape closes the dialog only when a game is already running (`onCancel`); otherwise there is nothing to go back to.
import { useEffect, useRef } from 'react';
import { createPortal } from 'react-dom';
import { MOVE_LIMIT } from '../game/board-state';
import type { Side } from '../game/board-state';
import { useT } from './texts';
import './start-dialog.css';

export type StartDialogModel =
  | { kind: 'begin'; onChoose: (humanBegins: boolean) => void }
  | {
      kind: 'link';
      ply: number;
      sideToMove: Side;
      /** The side that the link names as the human's, if it does; this one gets the first focus. */
      preferred?: Side | undefined;
      onContinueAs: (humanSide: Side) => void;
      onNew: () => void;
    }
  | { kind: 'broken'; reason: 'moves' | 'link'; onNew: () => void };

export interface StartDialogProps {
  model: StartDialogModel;
  /** Present when a game is running: Escape and the "keep" button close the dialog and leave that game alone. */
  onCancel?: (() => void) | undefined;
}

const TABBABLE = 'button:not([disabled]), [href], input:not([disabled]), [tabindex]:not([tabindex="-1"])';

export function StartDialog({ model, onCancel }: StartDialogProps): React.JSX.Element {
  const { t } = useT();
  const ref = useRef<HTMLElement | null>(null);
  const opener = useRef<Element | null>(typeof document === 'undefined' ? null : document.activeElement);

  useEffect(() => {
    const first = ref.current?.querySelector<HTMLElement>('[data-autofocus]') ?? ref.current?.querySelector<HTMLElement>(TABBABLE);
    first?.focus();
  }, [model.kind]);

  useEffect(() => {
    const back = opener.current;
    return () => {
      if (back instanceof HTMLElement && back.isConnected) back.focus();
    };
  }, []);

  const onKeyDown = (event: React.KeyboardEvent): void => {
    if (event.key === 'Escape' && onCancel) {
      event.stopPropagation();
      onCancel();
      return;
    }
    if (event.key !== 'Tab') return;
    const items = Array.from(ref.current?.querySelectorAll<HTMLElement>(TABBABLE) ?? []);
    const firstEl = items[0];
    const lastEl = items[items.length - 1];
    if (!firstEl || !lastEl) return;
    const active = document.activeElement;
    if (!ref.current?.contains(active)) {
      event.preventDefault();
      firstEl.focus();
    } else if (event.shiftKey && active === firstEl) {
      event.preventDefault();
      lastEl.focus();
    } else if (!event.shiftKey && active === lastEl) {
      event.preventDefault();
      firstEl.focus();
    }
  };

  const sideName = (side: Side): string => t(side === 0 ? 'dlg_side_0' : 'dlg_side_1');
  const titleId = 'ml-start-title';
  const bodyId = 'ml-start-body';
  let title: string;
  let body: React.JSX.Element;
  let actions: React.JSX.Element;

  if (model.kind === 'begin') {
    title = t('start_title');
    body = <p id={bodyId}>{t('start_lead')}</p>;
    actions = (
      <>
        <button type="button" className="ml-btn ml-btn--primary" data-autofocus data-choice="begin-yes" onClick={() => model.onChoose(true)}>
          {t('start_yes')}
        </button>
        <button type="button" className="ml-btn" data-choice="begin-no" onClick={() => model.onChoose(false)}>
          {t('start_no')}
        </button>
      </>
    );
  } else if (model.kind === 'link') {
    title = t('link_title');
    body = (
      <>
        <p id={bodyId}>{t('link_info', { ply: model.ply, limit: MOVE_LIMIT, side: sideName(model.sideToMove) })}</p>
        <p className="ml-hint">{t('link_as_hint')}</p>
      </>
    );
    const other: Side = model.sideToMove === 0 ? 1 : 0;
    const focusSide: Side = model.preferred ?? model.sideToMove;
    const asButton = (side: Side, choice: string): React.JSX.Element => (
      <button
        type="button"
        className={side === focusSide ? 'ml-btn ml-btn--primary' : 'ml-btn'}
        data-choice={choice}
        {...(side === focusSide ? { 'data-autofocus': true } : {})}
        onClick={() => model.onContinueAs(side)}
      >
        {t('link_as', { side: sideName(side) })}
      </button>
    );
    actions = (
      <>
        {asButton(model.sideToMove, 'link-side-to-move')}
        {asButton(other, 'link-other-side')}
        <button type="button" className="ml-btn" data-choice="link-new" onClick={model.onNew}>
          {t('link_new')}
        </button>
        {onCancel && (
          <button type="button" className="ml-btn ml-btn--quiet" data-choice="link-keep" onClick={onCancel}>
            {t('link_keep')}
          </button>
        )}
      </>
    );
  } else {
    title = t('bad_link_title');
    body = <p id={bodyId}>{t(model.reason === 'moves' ? 'bad_link_moves' : 'bad_link_body')}</p>;
    actions = (
      <>
        <button type="button" className="ml-btn ml-btn--primary" data-autofocus data-choice="broken-new" onClick={model.onNew}>
          {t('bad_link_new')}
        </button>
        {onCancel && (
          <button type="button" className="ml-btn ml-btn--quiet" data-choice="link-keep" onClick={onCancel}>
            {t('link_keep')}
          </button>
        )}
      </>
    );
  }

  return createPortal(
    <div className="ml-overlay ml-overlay--start">
      <section
        ref={ref}
        className="ml-sheet ml-start"
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        aria-describedby={bodyId}
        data-dialog={model.kind}
        onKeyDown={onKeyDown}
      >
        <h2 className="ml-sheet__title" id={titleId}>{title}</h2>
        {body}
        <div className="ml-start__actions">{actions}</div>
      </section>
    </div>,
    document.body,
  );
}
