// The service worker of the static site. tooling/pwa-plugin.ts bundles this file into web/dist/sw.js after the main build and
// inlines the manifest of all files (__SW_MANIFEST__). The strategy itself lives in sw-core.ts.
import { activate, indexOf, install, respond, routeRequest } from './sw-core';
import type { SwDeps, SwManifest } from './sw-core';

declare const __SW_MANIFEST__: SwManifest;

const sw = self as unknown as ServiceWorkerGlobalScope;
const manifest = __SW_MANIFEST__;
const index = indexOf(manifest);

const hex = (buffer: ArrayBuffer): string => Array.from(new Uint8Array(buffer), (b) => b.toString(16).padStart(2, '0')).join('');

function deps(): SwDeps {
  return {
    caches: sw.caches,
    fetch: (url, init) => fetch(url, init),
    sha256: async (bytes) => hex(await crypto.subtle.digest('SHA-256', bytes)),
    scopeUrl: sw.registration.scope,
    manifest,
  };
}

// No skipWaiting here: an update waits until the page asks for it (the "New version" notice), so a running game never reloads by itself.
sw.addEventListener('install', (event) => {
  event.waitUntil(install(deps()));
});

sw.addEventListener('activate', (event) => {
  // claim: the first visit is offline-ready without a reload; an update reaches this point only after the page asked for it.
  event.waitUntil(activate(deps()).then(() => sw.clients.claim()));
});

sw.addEventListener('message', (event) => {
  if ((event.data as { type?: unknown } | null)?.type === 'SKIP_WAITING') void sw.skipWaiting();
});

sw.addEventListener('fetch', (event) => {
  const route = routeRequest(event.request, sw.registration.scope, index);
  if (route.kind === 'network') return; // not ours: the browser handles it as if there were no service worker
  event.respondWith(respond(route, deps(), (work) => event.waitUntil(work)));
});
