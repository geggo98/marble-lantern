// A monospace chip with a path of the repository; it links to the file (or, for a path that ends in "/", the directory) on GitHub.
// With `lines` it names a line range: the chip shows `path:start-end` and the link ends in `#L<start>-L<end>`.
// The chip text is data, not copy (plan section 1.3).
import { Fragment } from 'react';
import { REPO_URL } from '../../ui/Chrome';

export const sourceUrl = (path: string, lines?: readonly [number, number]): string =>
  `${REPO_URL}/${path.endsWith('/') ? 'tree' : 'blob'}/main/${path.replace(/\/$/, '')}${lines ? `#L${lines[0]}-L${lines[1]}` : ''}`;

/** `:3-9` for a range, `:3` for one line. */
export const lineSuffix = (lines: readonly [number, number]): string => `:${lines[0] === lines[1] ? lines[0] : `${lines[0]}-${lines[1]}`}`;

export interface SourceChipProps {
  path: string;
  /** First and last line (1-based, inclusive). */
  lines?: readonly [number, number];
  /** Spoken name of the range (kit.code.lines filled in); the chip then reads "<path>, <label>". */
  linesLabel?: string;
  /** A file that is not in Git (generated): the chip shows the path and links nowhere. */
  noLink?: boolean;
}

export function SourceChip({ path, lines, linesLabel, noLink = false }: SourceChipProps): React.JSX.Element {
  const body = (
    <code>
      {path.split('/').map((part, i, all) => (
        <Fragment key={i}>
          {part}
          {i < all.length - 1 && '/'}
          {i < all.length - 1 && <wbr />}
        </Fragment>
      ))}
      {lines && lineSuffix(lines)}
    </code>
  );
  if (noLink) {
    return (
      <span className="ml-src" data-src={path}>
        {body}
      </span>
    );
  }
  return (
    <a className="ml-src" href={sourceUrl(path, lines)} rel="noreferrer" data-src={path} {...(linesLabel ? { "aria-label": `${path}, ${linesLabel}` } : {})}>
      {body}
    </a>
  );
}
