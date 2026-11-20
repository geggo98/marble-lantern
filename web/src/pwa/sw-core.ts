// The routing and caching strategy of the service worker (docs/web-ui.md, "Installable app and offline cache").
// Pure: the cache storage, fetch and SHA-256 come in through SwDeps, so that Vitest runs it with fakes. src/pwa/sw.ts wires it to the
// real service worker events; tooling/pwa-plugin.ts writes the manifest at build time.

/** One file of the build, by its path relative to the base path (no leading slash). */
export interface AssetEntry {
  url: string;
  /** The first REVISION_LENGTH hex digits of the SHA-256 of the file in web/dist. */
  revision: string;
  bytes: number;
}

/** What the build tells the service worker; inlined into sw.js, so that a changed file changes sw.js and starts an update. */
export interface SwManifest {
  /** Hash of all entries: the name of this build's caches. */
  version: string;
  /** Loaded on install: the app shell, the default engine, the race table. */
  precache: AssetEntry[];
  /** Cached on first use: the other engines, the models, other tables. */
  runtime: AssetEntry[];
}

export const REVISION_LENGTH = 16;
/** Response header that the cache entries carry: the revision of the bytes in the entry. */
export const REVISION_HEADER = 'x-ml-revision';
export const SHELL = 'index.html';

export interface CacheLike {
  match(url: string): Promise<Response | undefined>;
  put(url: string, response: Response): Promise<void>;
}
export interface CacheStorageLike {
  open(name: string): Promise<CacheLike>;
  keys(): Promise<string[]>;
  delete(name: string): Promise<boolean>;
}
export interface SwDeps {
  caches: CacheStorageLike;
  fetch: (url: string, init?: RequestInit) => Promise<Response>;
  /** Lower-case hex SHA-256 of the bytes. */
  sha256: (bytes: ArrayBuffer) => Promise<string>;
  /** The registration scope, an absolute URL with a trailing slash, e.g. https://example.org/marble-lantern/. */
  scopeUrl: string;
  manifest: SwManifest;
}

/**
 * The cache names of this site. One origin can host several sites (GitHub Pages serves every project of a user from one host),
 * so the names carry the scope path, and the cleanup touches only names with this prefix.
 */
export function cachePrefix(scopeUrl: string): string {
  return `ml:${new URL(scopeUrl).pathname}:`;
}
export function cacheNames(scopeUrl: string, version: string): { precache: string; runtime: string } {
  const p = cachePrefix(scopeUrl);
  return { precache: `${p}precache-${version}`, runtime: `${p}runtime-${version}` };
}

export type Route =
  | { kind: 'precache'; url: string; revision: string }
  | { kind: 'runtime'; url: string; revision: string }
  | { kind: 'network' };

export type AssetIndex = ReadonlyMap<string, { kind: 'precache' | 'runtime'; revision: string }>;

export function indexOf(manifest: SwManifest): AssetIndex {
  const index = new Map<string, { kind: 'precache' | 'runtime'; revision: string }>();
  for (const e of manifest.runtime) index.set(e.url, { kind: 'runtime', revision: e.revision });
  for (const e of manifest.precache) index.set(e.url, { kind: 'precache', revision: e.revision });
  return index;
}

/**
 * Decides who answers a request. Only GET requests for files of this build under the scope are served from a cache; everything
 * else (other origins, POST, the server endpoints under connect/, query strings, unknown paths) goes to the network untouched.
 * A navigation to the scope or to index.html gets the app shell, whatever its query string; the game lives in the fragment.
 */
export function routeRequest(request: { url: string; method: string; mode?: string }, scopeUrl: string, index: AssetIndex): Route {
  if (request.method !== 'GET') return { kind: 'network' };
  const scope = new URL(scopeUrl);
  let url: URL;
  try {
    url = new URL(request.url);
  } catch {
    return { kind: 'network' };
  }
  if (url.origin !== scope.origin || !url.pathname.startsWith(scope.pathname)) return { kind: 'network' };
  if (url.pathname.includes('/connect/')) return { kind: 'network' };
  const rel = url.pathname.slice(scope.pathname.length);
  const absolute = (path: string): string => scope.origin + scope.pathname + path;
  if (request.mode === 'navigate') {
    const shell = index.get(SHELL);
    if ((rel === '' || rel === SHELL) && shell) return { kind: 'precache', url: absolute(SHELL), revision: shell.revision };
    return { kind: 'network' };
  }
  if (url.search !== '') return { kind: 'network' };
  const hit = index.get(rel);
  return hit ? { kind: hit.kind, url: absolute(rel), revision: hit.revision } : { kind: 'network' };
}

/**
 * A copy of the response for the cache, with the revision header, or null when it must not be stored: no 200, or bytes that do not
 * match the revision (a CDN that still serves the previous deployment). A gzip file that the server sent with Content-Encoding
 * arrives inflated and cannot match its hash; it is stored unchecked, because the app verifies tables and models itself.
 */
export async function stamp(response: Response, url: string, revision: string, sha256: SwDeps['sha256']): Promise<Response | null> {
  if (response.status !== 200 || response.type === 'opaque') return null;
  const bytes = await response.arrayBuffer();
  const encoded = response.headers.has('content-encoding') && url.endsWith('.gz');
  if (!encoded && (await sha256(bytes)).slice(0, REVISION_LENGTH) !== revision) return null;
  const headers = new Headers(response.headers);
  headers.delete('content-encoding');
  headers.set('content-length', String(bytes.byteLength));
  headers.set(REVISION_HEADER, revision);
  return new Response(bytes, { status: 200, statusText: response.statusText, headers });
}

async function oldCaches(deps: SwDeps): Promise<string[]> {
  const prefix = cachePrefix(deps.scopeUrl);
  const own = cacheNames(deps.scopeUrl, deps.manifest.version);
  return (await deps.caches.keys()).filter((n) => n.startsWith(prefix) && n !== own.precache && n !== own.runtime);
}

/** An entry of an older cache of this site with the same revision: the bytes need no new download. */
async function carried(deps: SwDeps, names: readonly string[], url: string, revision: string): Promise<Response | null> {
  for (const name of names) {
    const hit = await (await deps.caches.open(name)).match(url);
    if (hit && hit.headers.get(REVISION_HEADER) === revision) return hit;
  }
  return null;
}

export interface InstallReport {
  fetched: number;
  carried: number;
  /** Bytes in the precache after install. */
  precacheBytes: number;
  /** Runtime entries taken over from an older cache. */
  runtimeCarried: number;
}

/**
 * Fills the precache of this build. A file whose revision is in an older cache of this site is copied; any other is fetched,
 * first through the HTTP cache (the page usually loaded it a moment ago), then, if the bytes do not match, with cache: 'reload'.
 * A file that still does not match fails the install: the previous service worker stays in charge and the browser tries again later.
 * Runtime entries with an unchanged revision move over too, so that an update keeps the engines and models already used.
 */
export async function install(deps: SwDeps): Promise<InstallReport> {
  const names = cacheNames(deps.scopeUrl, deps.manifest.version);
  const old = await oldCaches(deps);
  const pre = await deps.caches.open(names.precache);
  const report: InstallReport = { fetched: 0, carried: 0, precacheBytes: 0, runtimeCarried: 0 };
  const base = deps.scopeUrl;
  for (const entry of deps.manifest.precache) {
    const url = new URL(entry.url, base).href;
    const keep = await carried(deps, old, url, entry.revision);
    if (keep) {
      await pre.put(url, keep);
      report.carried++;
    } else {
      let fresh = await stamp(await deps.fetch(url), url, entry.revision, deps.sha256);
      fresh ??= await stamp(await deps.fetch(url, { cache: 'reload' }), url, entry.revision, deps.sha256);
      if (!fresh) throw new Error(`precache: ${entry.url} does not match revision ${entry.revision}`);
      await pre.put(url, fresh);
      report.fetched++;
    }
    report.precacheBytes += entry.bytes;
  }
  if (old.length > 0) {
    const rt = await deps.caches.open(names.runtime);
    for (const entry of deps.manifest.runtime) {
      const url = new URL(entry.url, base).href;
      const keep = await carried(deps, old, url, entry.revision);
      if (keep) {
        await rt.put(url, keep);
        report.runtimeCarried++;
      }
    }
  }
  return report;
}

/** Deletes the caches of older builds of this site; caches of other sites on the same origin stay. Returns the deleted names. */
export async function activate(deps: SwDeps): Promise<string[]> {
  const old = await oldCaches(deps);
  for (const name of old) await deps.caches.delete(name);
  return old;
}

/**
 * Answers a request that routeRequest gave to a cache: cache first, else the network. A network answer goes back to the page as it
 * came (streaming, with its progress), and a checked copy goes into the cache through `defer` (the event's waitUntil).
 * A runtime entry with another revision counts as a miss. Offline and not cached, the fetch error reaches the page.
 */
export async function respond(route: Exclude<Route, { kind: 'network' }>, deps: SwDeps, defer: (work: Promise<unknown>) => void): Promise<Response> {
  const names = cacheNames(deps.scopeUrl, deps.manifest.version);
  const cache = await deps.caches.open(route.kind === 'precache' ? names.precache : names.runtime);
  const hit = await cache.match(route.url);
  if (hit && (route.kind === 'precache' || hit.headers.get(REVISION_HEADER) === route.revision)) return hit;
  const response = await deps.fetch(route.url);
  if (response.status === 200) {
    const copy = response.clone();
    defer(
      stamp(copy, route.url, route.revision, deps.sha256)
        .then((s) => (s ? cache.put(route.url, s) : undefined))
        .catch(() => undefined),
    );
  }
  return response;
}
