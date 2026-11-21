// The link from the sections "The game tree" and "Minimax and negamax" to figure V5 with the start position selected: the reader can count the
// 14 columns and the 14 replies of the first column there (spec v5-mid-spec.md, sections 7 and 9, finding 13). The figure listens for the event.
import { hrefForAbout } from '../../ui/Chrome';
import { prefersReducedMotion } from '../../ui/primitives';
import { AB_EXAMPLE_EVENT } from './AlphaBetaFigure';
import type { AbExample } from './AlphaBetaFigure';
import { useFigText } from './common';
import type { Catalog } from './common';

export function AbStartLink({ catalog }: { catalog: Catalog }): React.JSX.Element {
  const { t } = useFigText(catalog);
  const open = (example: AbExample): void => {
    window.dispatchEvent(new CustomEvent<AbExample>(AB_EXAMPLE_EVENT, { detail: example }));
    document.getElementById('fig-ab')?.scrollIntoView?.({ block: 'start', behavior: prefersReducedMotion() ? 'auto' : 'smooth' });
  };
  return (
    <p className="ml-fig-ab2__link" data-ab-link="start">
      <a
        href={hrefForAbout('classical', 'alpha-beta')}
        onClick={(event) => {
          if (event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
          event.preventDefault();
          open('start');
        }}
      >
        {t('fig.ab.link.start')}
      </a>
    </p>
  );
}
