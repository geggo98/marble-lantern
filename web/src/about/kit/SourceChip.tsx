// A monospace chip with a path of the repository; it links to the file (or, for a path that ends in "/", the directory) on GitHub.
// The chip text is data, not copy (plan section 1.3).
import { REPO_URL } from '../../ui/Chrome';

export const sourceUrl = (path: string): string => `${REPO_URL}/${path.endsWith('/') ? 'tree' : 'blob'}/main/${path.replace(/\/$/, '')}`;

export function SourceChip({ path }: { path: string }): React.JSX.Element {
  return (
    <a className="ml-src" href={sourceUrl(path)} rel="noreferrer" data-src={path}>
      <code>{path}</code>
    </a>
  );
}
