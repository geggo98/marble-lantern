// The bottom bar of the play screen on a small screen: the actions that are used all the time, and the menu button.
import { Icon } from './primitives';
import { MenuButton } from './Menu';
import { useT } from './texts';

export interface ActionBarProps {
  /** The net panel lies over the bar: nothing in it may take focus or clicks while the panel is open. */
  inert?: boolean;
  disabled: boolean;
  canUndo: boolean;
  menuOpen: boolean;
  menuId: string;
  menuRef: React.Ref<HTMLButtonElement>;
  onUndo: () => void;
  onNewGame: () => void;
  onMenu: () => void;
}

export function ActionBar(props: ActionBarProps): React.JSX.Element {
  const { t } = useT();
  return (
    <div className="ml-play__bar ml-actionbar" role="group" aria-label={t('bar_label')} inert={props.inert}>
      <button type="button" className="ml-btn" disabled={props.disabled || !props.canUndo} onClick={props.onUndo}>
        <Icon name="undo" />
        <span>{t('undo')}</span>
      </button>
      <button type="button" className="ml-btn ml-btn--primary" disabled={props.disabled} onClick={props.onNewGame}>
        <Icon name="plus" />
        <span>{t('new_game')}</span>
      </button>
      <MenuButton ref={props.menuRef} expanded={props.menuOpen} controlsId={props.menuId} onClick={props.onMenu} />
    </div>
  );
}
