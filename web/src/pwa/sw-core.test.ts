// The strategy of the service worker, with an in-memory cache storage and a fake network: routing, install with carry-over and
// revision check, cleanup of old caches (only this site's), and cache-first answers.
import { createHash } from 'node:crypto';
import { describe, expect, it } from 'vitest';
import { REVISION_HEADER, activate, cacheNames, indexOf, install, respond, routeRequest, stamp } from './sw-core';
import type { CacheLike, CacheStorageLike, SwDeps, SwManifest } from './sw-core';

const SCOPE = 'https://example.org/marble-lantern/';
const sha = (text: string): string => createHash('sha256').update(text).digest('hex');
const rev = (text: string): string => sha(text).slice(0, 16);
const sha256 = async (bytes: ArrayBuffer): Promise<string> => createHash('sha256').update(new Uint8Array(bytes)).digest('hex');

/** File contents of the fake server, by path below the scope. */
const FILES: Record<string, string> = {
  'index.html': '<!doctype html>shell',
  'assets/index-abc.js': 'console.log(1)',
  'engines/java/engine-java.wasm': 'wasm-gc engine',
  'engines/cpp/engine-cpp.wasm': 'cpp engine',
  'models/m.safetensors.gz': 'model bytes',
};
const entry = (url: string, text = FILES[url]!) => ({ url, revision: rev(text), bytes: text.length });
const MANIFEST: SwManifest = {
  version: 'v2',
  precache: [entry('index.html'), entry('assets/index-abc.js'), entry('engines/java/engine-java.wasm')],
  runtime: [entry('engines/cpp/engine-cpp.wasm'), entry('models/m.safetensors.gz')],
};

class FakeCache implements CacheLike {
  readonly entries = new Map<string, Response>();
  async match(url: string): Promise<Response | undefined> {
    return this.entries.get(url)?.clone();
  }
  async put(url: string, response: Response): Promise<void> {
    this.entries.set(url, response);
  }
}
class FakeCaches implements CacheStorageLike {
  readonly map = new Map<string, FakeCache>();
  async open(name: string): Promise<FakeCache> {
    let c = this.map.get(name);
    if (!c) this.map.set(name, (c = new FakeCache()));
    return c;
  }
  async keys(): Promise<string[]> {
    return [...this.map.keys()];
  }
  async delete(name: string): Promise<boolean> {
    return this.map.delete(name);
  }
}

interface Net {
  calls: { url: string; init?: RequestInit }[];
  offline: boolean;
  /** Body overrides: what the server (or a stale CDN) sends instead of FILES. */
  serve: Record<string, string>;
  /** Body overrides for cache: 'reload' only (the HTTP cache held a stale copy). */
  reload: Record<string, string>;
  headers: Record<string, string>;
}

function setup(manifest = MANIFEST): { deps: SwDeps; caches: FakeCaches; net: Net } {
  const net: Net = { calls: [], offline: false, serve: {}, reload: {}, headers: {} };
  const caches = new FakeCaches();
  const deps: SwDeps = {
    caches,
    sha256,
    scopeUrl: SCOPE,
    manifest,
    fetch: async (url, init) => {
      net.calls.push({ url, ...(init ? { init } : {}) });
      if (net.offline) throw new TypeError('Failed to fetch');
      const path = url.slice(SCOPE.length);
      const body = (init?.cache === 'reload' ? net.reload[path] : undefined) ?? net.serve[path] ?? FILES[path];
      if (body === undefined) return new Response('not found', { status: 404 });
      return new Response(body, { status: 200, headers: { 'content-type': 'application/octet-stream', ...net.headers } });
    },
  };
  return { deps, caches, net };
}

const names = cacheNames(SCOPE, MANIFEST.version);
const text = async (r: Response | undefined): Promise<string> => (r ? await r.text() : '<none>');
const url = (path: string): string => SCOPE + path;

describe('routeRequest', () => {
  const index = indexOf(MANIFEST);
  const route = (u: string, method = 'GET', mode = 'cors') => routeRequest({ url: u, method, mode }, SCOPE, index);

  it('serves the shell for a navigation to the scope or index.html, with any query; other navigations go to the network', () => {
    for (const u of [SCOPE, `${SCOPE}index.html`, `${SCOPE}?fake=1`, `${SCOPE}#g=v1.abc`]) {
      expect(route(u, 'GET', 'navigate'), u).toEqual({ kind: 'precache', url: url('index.html'), revision: rev(FILES['index.html']!) });
    }
    expect(route(`${SCOPE}bench/index.html`, 'GET', 'navigate')).toEqual({ kind: 'network' });
  });

  it('routes known files to their cache and everything else to the network', () => {
    expect(route(url('assets/index-abc.js'))).toMatchObject({ kind: 'precache', url: url('assets/index-abc.js') });
    expect(route(url('engines/cpp/engine-cpp.wasm'))).toMatchObject({ kind: 'runtime', revision: rev(FILES['engines/cpp/engine-cpp.wasm']!) });
    expect(route(url('assets/unknown.js'))).toEqual({ kind: 'network' });
    expect(route(`${url('assets/index-abc.js')}?v=2`)).toEqual({ kind: 'network' }); // a query string is not the file of the manifest
    expect(route(url('assets/index-abc.js'), 'POST')).toEqual({ kind: 'network' });
    expect(route('https://cdn.example.com/marble-lantern/assets/index-abc.js')).toEqual({ kind: 'network' }); // other origin
    expect(route('https://example.org/other-site/assets/index-abc.js')).toEqual({ kind: 'network' }); // other site, same origin
    expect(route(url('connect/GameEndpoint/engines'), 'GET')).toEqual({ kind: 'network' }); // server engines
  });

  it('never routes connect/ to a cache, even if a file of that name were in the manifest', () => {
    const odd = indexOf({ ...MANIFEST, runtime: [entry('connect/x', 'x')] });
    expect(routeRequest({ url: url('connect/x'), method: 'GET' }, SCOPE, odd)).toEqual({ kind: 'network' });
  });
});

describe('install', () => {
  it('fetches every precache file into the cache of this version, stamped with its revision; runtime files wait for their first use', async () => {
    const { deps, caches, net } = setup();
    const report = await install(deps);
    expect(report).toEqual({ fetched: 3, carried: 0, precacheBytes: MANIFEST.precache.reduce((n, e) => n + e.bytes, 0), runtimeCarried: 0 });
    const pre = caches.map.get(names.precache)!;
    expect([...pre.entries.keys()].sort()).toEqual(MANIFEST.precache.map((e) => url(e.url)).sort());
    const shell = (await pre.match(url('index.html')))!;
    expect(shell.headers.get(REVISION_HEADER)).toBe(rev(FILES['index.html']!));
    expect(await shell.text()).toBe(FILES['index.html']);
    expect(net.calls.map((c) => c.url)).not.toContain(url('engines/cpp/engine-cpp.wasm'));
  });

  it('copies unchanged files from the caches of the previous version instead of downloading them', async () => {
    const { deps, caches, net } = setup();
    await install({ ...deps, manifest: { ...MANIFEST, version: 'v1' } });
    const oldRuntime = await caches.open(cacheNames(SCOPE, 'v1').runtime);
    await oldRuntime.put(url('engines/cpp/engine-cpp.wasm'), (await stamp(new Response(FILES['engines/cpp/engine-cpp.wasm']), 'x', rev(FILES['engines/cpp/engine-cpp.wasm']!), sha256))!);
    // the model changed in v2: its old runtime entry must not move over
    await oldRuntime.put(url('models/m.safetensors.gz'), (await stamp(new Response('old model'), 'x', rev('old model'), sha256))!);
    net.calls.length = 0;
    const changed = 'console.log(2)';
    net.serve['assets/index-abc.js'] = changed;
    const v2: SwManifest = { ...MANIFEST, precache: [entry('index.html'), entry('assets/index-abc.js', changed), entry('engines/java/engine-java.wasm')] };
    const report = await install({ ...deps, manifest: v2 });
    expect(report).toMatchObject({ fetched: 1, carried: 2, runtimeCarried: 1 });
    expect(net.calls.map((c) => c.url)).toEqual([url('assets/index-abc.js')]);
    const rt = caches.map.get(names.runtime)!;
    expect([...rt.entries.keys()]).toEqual([url('engines/cpp/engine-cpp.wasm')]);
  });

  it('retries with cache: reload when the HTTP cache holds a stale copy', async () => {
    const { deps, caches, net } = setup();
    net.serve['assets/index-abc.js'] = 'stale bytes';
    net.reload['assets/index-abc.js'] = FILES['assets/index-abc.js']!;
    await install(deps);
    expect(net.calls.filter((c) => c.url === url('assets/index-abc.js')).map((c) => c.init?.cache ?? 'default')).toEqual(['default', 'reload']);
    expect(await text(await caches.map.get(names.precache)!.match(url('assets/index-abc.js')))).toBe(FILES['assets/index-abc.js']);
  });

  it('fails when a file does not match its revision even after a reload (a CDN that still serves the old deployment)', async () => {
    const { deps, net } = setup();
    net.serve['engines/java/engine-java.wasm'] = 'previous engine';
    await expect(install(deps)).rejects.toThrow(/engines\/java\/engine-java\.wasm does not match/);
  });

  it('fails when a file is missing on the server', async () => {
    const { deps } = setup({ ...MANIFEST, precache: [...MANIFEST.precache, entry('gone.png', 'png')] });
    await expect(install(deps)).rejects.toThrow(/gone\.png/);
  });
});

describe('activate', () => {
  it('deletes the older caches of this site and keeps those of the current version and of other sites on the origin', async () => {
    const { deps, caches } = setup();
    for (const n of [names.precache, names.runtime, 'ml:/marble-lantern/:precache-v1', 'ml:/marble-lantern/:runtime-v1', 'ml:/other-site/:precache-v1', 'unrelated']) {
      await caches.open(n);
    }
    expect((await activate(deps)).sort()).toEqual(['ml:/marble-lantern/:precache-v1', 'ml:/marble-lantern/:runtime-v1']);
    expect((await caches.keys()).sort()).toEqual([names.precache, names.runtime, 'ml:/other-site/:precache-v1', 'unrelated'].sort());
  });
});

describe('respond', () => {
  const run = async (deps: SwDeps, path: string): Promise<{ response: Response; deferred: Promise<unknown>[] }> => {
    const route = routeRequest({ url: url(path), method: 'GET' }, SCOPE, indexOf(deps.manifest));
    if (route.kind === 'network') throw new Error(`${path} routes to the network`);
    const deferred: Promise<unknown>[] = [];
    const response = await respond(route, deps, (p) => deferred.push(p));
    await Promise.all(deferred);
    return { response, deferred };
  };

  it('answers a precached file from the cache without the network', async () => {
    const { deps, net } = setup();
    await install(deps);
    net.calls.length = 0;
    net.offline = true;
    expect(await (await run(deps, 'assets/index-abc.js')).response.text()).toBe(FILES['assets/index-abc.js']);
    expect(net.calls).toEqual([]);
  });

  it('caches a runtime file on first use and answers it offline afterwards', async () => {
    const { deps, caches, net } = setup();
    expect(await (await run(deps, 'engines/cpp/engine-cpp.wasm')).response.text()).toBe(FILES['engines/cpp/engine-cpp.wasm']);
    expect(caches.map.get(names.runtime)!.entries.has(url('engines/cpp/engine-cpp.wasm'))).toBe(true);
    net.offline = true;
    expect(await (await run(deps, 'engines/cpp/engine-cpp.wasm')).response.text()).toBe(FILES['engines/cpp/engine-cpp.wasm']);
  });

  it('treats a runtime entry with another revision as a miss', async () => {
    const { deps, caches, net } = setup();
    const rt = await caches.open(names.runtime);
    await rt.put(url('models/m.safetensors.gz'), (await stamp(new Response('old model'), 'x', rev('old model'), sha256))!);
    expect(await (await run(deps, 'models/m.safetensors.gz')).response.text()).toBe(FILES['models/m.safetensors.gz']);
    expect(net.calls.map((c) => c.url)).toEqual([url('models/m.safetensors.gz')]);
    expect(await text(await rt.match(url('models/m.safetensors.gz')))).toBe(FILES['models/m.safetensors.gz']);
  });

  it('passes a wrong file to the page but does not cache it', async () => {
    const { deps, caches, net } = setup();
    net.serve['engines/cpp/engine-cpp.wasm'] = 'stale cdn bytes';
    expect(await (await run(deps, 'engines/cpp/engine-cpp.wasm')).response.text()).toBe('stale cdn bytes');
    expect(caches.map.get(names.runtime)!.entries.size).toBe(0);
  });

  it('lets the fetch error reach the page when offline and not cached', async () => {
    const { deps, net } = setup();
    net.offline = true;
    await expect(run(deps, 'engines/cpp/engine-cpp.wasm')).rejects.toThrow(/Failed to fetch/);
  });

  it('stores a .gz file that arrives inflated (Content-Encoding: gzip) without the hash check, and drops the encoding header', async () => {
    const { deps, caches, net } = setup();
    net.serve['models/m.safetensors.gz'] = 'inflated bytes, not the hashed file';
    net.headers['content-encoding'] = 'gzip';
    await run(deps, 'models/m.safetensors.gz');
    const stored = (await caches.map.get(names.runtime)!.match(url('models/m.safetensors.gz')))!;
    expect(stored.headers.get('content-encoding')).toBeNull();
    expect(stored.headers.get('content-length')).toBe(String('inflated bytes, not the hashed file'.length));
    expect(await stored.text()).toBe('inflated bytes, not the hashed file');
  });
});

describe('stamp', () => {
  it('refuses responses other than 200, and a hash mismatch for a file without Content-Encoding', async () => {
    expect(await stamp(new Response('x', { status: 206 }), 'a.js', rev('x'), sha256)).toBeNull();
    expect(await stamp(new Response('y'), 'a.js', rev('x'), sha256)).toBeNull();
    expect(await stamp(new Response('y', { headers: { 'content-encoding': 'gzip' } }), 'a.js', rev('x'), sha256)).toBeNull(); // only .gz files
    expect((await stamp(new Response('x'), 'a.js', rev('x'), sha256))!.headers.get(REVISION_HEADER)).toBe(rev('x'));
  });
});
