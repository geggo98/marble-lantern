// ReadingList (design notes, section 6, "Other page parts"): one ordered list in one card. Per entry: the title as an external link
// (44 px high), authors and year, a level tag, a "paywalled" tag where it applies, one note line. Titles and authors are data;
// the headings, tags and notes arrive as props.
import { Icon } from './icons';

export interface ReadingItem {
  id: string | number;
  title: string;
  authors?: string | undefined;
  year?: number | undefined;
  url: string;
  /** Key into `levelLabels`, for example `beginner` or `advanced`. */
  level: string;
  paywalled?: boolean | undefined;
  /** The note line (catalog text `read.<id>.note`). */
  note?: string | undefined;
}

export interface ReadingListProps {
  /** Heading (`kit.reading.title`). */
  title: string;
  items: readonly ReadingItem[];
  /** Tag text per level (`kit.reading.level.*`); a level without an entry shows no tag. */
  levelLabels: Readonly<Record<string, string>>;
  /** `kit.reading.paywalled`. */
  paywalled: string;
  /** Heading level of the title, default 2. */
  headingLevel?: 2 | 3;
}

export function ReadingList({ title, items, levelLabels, paywalled, headingLevel = 2 }: ReadingListProps): React.JSX.Element {
  const Heading = headingLevel === 2 ? 'h2' : 'h3';
  return (
    <section className="ml-fig-reading">
      <Heading className="ml-fig-section__title">{title}</Heading>
      <ol className="ml-fig-reading__list">
        {items.map((item) => (
          <li key={item.id} className="ml-fig-reading__item">
            <a className="ml-fig-reading__link" href={item.url} rel="noreferrer">
              <span>{item.title}</span>
              <Icon name="ext" />
            </a>
            <span className="ml-fig-reading__meta">
              {item.authors && <span>{item.authors}</span>}
              {item.year !== undefined && <span>{item.year}</span>}
              {levelLabels[item.level] && <span className="ml-tag">{levelLabels[item.level]}</span>}
              {item.paywalled && <span className="ml-tag">{paywalled}</span>}
            </span>
            {item.note && <span className="ml-fig-reading__note">{item.note}</span>}
          </li>
        ))}
      </ol>
    </section>
  );
}
