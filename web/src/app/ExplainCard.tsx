// The card "Explain this position": five links into the Info part. Pressing one stores a snapshot of the game in the app (onExplain), then the
// link navigates as an ordinary hash link. It starts no engine and downloads nothing: the figures start their engines when the reader presses
// their own live switch. Disabled, with the reason as text, before a game runs, when the game is over, and for a variant that is not classic.
import type { MouseEvent } from 'react';
import { hrefForAbout } from '../ui/Chrome';
import { useT } from '../ui/texts';
import type { TextKey } from '../ui/texts';
import type { ExplainTarget } from '../about/live/types';

export type ExplainBlock = 'nogame' | 'over' | 'variant';

interface LinkDef {
  target: ExplainTarget;
  label: TextKey;
  /** The link downloads a net engine and a model when the reader presses the live switch. */
  net?: true;
}

const LINKS: readonly LinkDef[] = [
  { target: { topic: 'classical', section: 'budget' }, label: 'explain_link_budget' },
  { target: { topic: 'classical', section: 'alpha-beta' }, label: 'explain_link_roots' },
  { target: { topic: 'classical', section: 'evaluation' }, label: 'explain_link_eval' },
  { target: { topic: 'inference', section: 'mcts' }, label: 'explain_link_mcts', net: true },
  { target: { topic: 'inference', section: 'forward-pass' }, label: 'explain_link_forward', net: true },
];

const REASON: Record<ExplainBlock, TextKey> = {
  nogame: 'explain_disabled_nogame',
  over: 'explain_disabled_over',
  variant: 'explain_disabled_variant',
};

export function ExplainCard({ blocked, onExplain }: { blocked: ExplainBlock | null; onExplain: (target: ExplainTarget) => void }): React.JSX.Element {
  const { t } = useT();
  return (
    <section className="ml-card ml-explain" aria-labelledby="ml-explain-title" data-explain-card="" data-disabled={blocked ? '' : undefined}>
      <h3 id="ml-explain-title" className="ml-card__title">{t('explain_title')}</h3>
      <p className="ml-explain__lead">{t('explain_lead')}</p>
      <ul className="ml-explain__list">
        {LINKS.map(({ target, label, net }) => (
          <li key={target.section}>
            {blocked ? (
              <span className="ml-explain__link" aria-disabled="true" data-explain-link={target.section}>{t(label)}</span>
            ) : (
              <a
                className="ml-explain__link"
                href={hrefForAbout(target.topic, target.section)}
                data-explain-link={target.section}
                // No preventDefault: the link navigates by itself. The snapshot is stored first, so that the page it opens has it.
                onClick={(_event: MouseEvent<HTMLAnchorElement>) => onExplain(target)}
              >
                {t(label)}
              </a>
            )}
            {net && !blocked && <span className="ml-explain__note">{t('explain_net_note')}</span>}
          </li>
        ))}
      </ul>
      {blocked && <p className="ml-explain__reason" data-explain-reason="">{t(REASON[blocked])}</p>}
    </section>
  );
}
