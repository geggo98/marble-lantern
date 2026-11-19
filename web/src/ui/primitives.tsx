// Small shared pieces: the sprite sheet, icons, the marble swatch of a side, tags.
import { SPRITE_DEFS } from './sprite';

/** Render once per page. Zero size, not display: none, so that gradients keep painting. */
export function Sprite(): React.JSX.Element {
  return (
    <svg width="0" height="0" style={{ position: 'absolute' }} aria-hidden="true" focusable="false">
      <defs dangerouslySetInnerHTML={{ __html: SPRITE_DEFS }} />
    </svg>
  );
}

export type IconName =
  | 'plus' | 'undo' | 'swap' | 'play' | 'offline' | 'online' | 'close' | 'check' | 'alert' | 'copy' | 'tune'
  | 'sun' | 'moon' | 'auto' | 'ext' | 'menu' | 'pause';

export function Icon({ name }: { name: IconName }): React.JSX.Element {
  return (
    <svg className="ml-icon" viewBox="0 0 24 24" aria-hidden="true">
      <use href={`#i-${name}`} />
    </svg>
  );
}

/** The marble of a side: A plain amber, B blue with a ring, C pearl with a diamond (the third seat of a three-player game). */
export type SideStyle = 'a' | 'b' | 'c';

/** Every place that shows a side shows the marble itself, never a bare colour patch. */
export function Swatch({ style }: { style: SideStyle }): React.JSX.Element {
  return (
    <svg className="ml-swatch" viewBox="-16 -16 32 32" aria-hidden="true">
      <use href={`#ml-piece-${style}`} />
    </svg>
  );
}

export function Tag({ children, solid = false }: { children: React.ReactNode; solid?: boolean }): React.JSX.Element {
  return <span className={solid ? 'ml-tag ml-tag--solid' : 'ml-tag'}>{children}</span>;
}

export function prefersReducedMotion(): boolean {
  try {
    return typeof matchMedia === 'function' && matchMedia('(prefers-reduced-motion: reduce)').matches;
  } catch {
    return false;
  }
}
