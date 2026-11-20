// The page side of the installable app with a fake browser: registration only when asked, the update notice only for a real update,
// a reload only after the click, and the install offer (beforeinstallprompt, or the hint on Safari for iOS).
import { describe, expect, it, vi } from 'vitest';
import { PwaController, UPDATE_CHECK_MS, isIosSafari } from './pwa';
import type { PwaEnv } from './pwa';

const UA = {
  iphoneSafari: 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.4 Mobile/15E148 Safari/604.1',
  iphoneChrome: 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) CriOS/140.0 Mobile/15E148 Safari/604.1',
  ipadSafari: 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.4 Safari/605.1.15',
  macSafari: 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.4 Safari/605.1.15',
  androidChrome: 'Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0 Mobile Safari/537.36',
};

describe('isIosSafari', () => {
  it('knows Safari on iPhone and iPad (which reports a Mac with touch), and no other browser', () => {
    expect(isIosSafari({ userAgent: UA.iphoneSafari })).toBe(true);
    expect(isIosSafari({ userAgent: UA.ipadSafari, platform: 'MacIntel', maxTouchPoints: 5 })).toBe(true);
    expect(isIosSafari({ userAgent: UA.macSafari, platform: 'MacIntel', maxTouchPoints: 0 })).toBe(false);
    expect(isIosSafari({ userAgent: UA.iphoneChrome })).toBe(false);
    expect(isIosSafari({ userAgent: UA.androidChrome, platform: 'Linux armv8l', maxTouchPoints: 5 })).toBe(false);
  });
});

class Target {
  private readonly handlers = new Map<string, ((e: Event) => void)[]>();
  addEventListener = (type: string, fn: (e: Event) => void): void => {
    this.handlers.set(type, [...(this.handlers.get(type) ?? []), fn]);
  };
  emit(type: string, event: Event = new Event(type)): void {
    for (const fn of this.handlers.get(type) ?? []) fn(event);
  }
}

class FakeWorker extends Target {
  state = 'installing';
  readonly messages: unknown[] = [];
  postMessage = (m: unknown): void => void this.messages.push(m);
  become(state: string): void {
    this.state = state;
    this.emit('statechange');
  }
}

class FakeRegistration extends Target {
  waiting: FakeWorker | null = null;
  installing: FakeWorker | null = null;
  update = vi.fn(async () => undefined);
}

interface Fake {
  env: PwaEnv;
  window: Target;
  document: Target & { visibilityState: string };
  container: Target & { controller: object | null; register: ReturnType<typeof vi.fn> };
  reg: FakeRegistration;
  reload: ReturnType<typeof vi.fn>;
  timers: { fn: () => void; ms: number }[];
}

function fake(o: { ua?: string; standalone?: boolean; controller?: boolean; noSw?: boolean; registerFails?: boolean } = {}): Fake {
  const window = new Target();
  const document = Object.assign(new Target(), { visibilityState: 'visible' });
  const reg = new FakeRegistration();
  const container = Object.assign(new Target(), {
    controller: o.controller === false ? null : {},
    register: vi.fn(async () => {
      if (o.registerFails) throw new DOMException('blocked', 'SecurityError');
      return reg;
    }),
  });
  const reload = vi.fn();
  const timers: { fn: () => void; ms: number }[] = [];
  const env = {
    window,
    document,
    navigator: { userAgent: o.ua ?? UA.androidChrome, ...(o.noSw ? {} : { serviceWorker: container }) },
    matchMedia: (q: string) => ({ matches: q === '(display-mode: standalone)' && o.standalone === true }),
    reload,
    setInterval: (fn: () => void, ms: number) => timers.push({ fn, ms }),
  } as unknown as PwaEnv;
  return { env, window, document, container, reg, reload, timers };
}

const OPTIONS = { register: true, swUrl: '/marble-lantern/sw.js', scope: '/marble-lantern/' };

describe('PwaController: registration and update', () => {
  it('registers sw.js under the base path once, and not at all when the build has no service worker', async () => {
    const f = fake();
    const pwa = new PwaController(f.env, OPTIONS);
    await pwa.start();
    await pwa.start();
    expect(f.container.register).toHaveBeenCalledTimes(1);
    expect(f.container.register).toHaveBeenCalledWith('/marble-lantern/sw.js', { scope: '/marble-lantern/' });
    const dev = fake();
    await new PwaController(dev.env, { ...OPTIONS, register: false }).start();
    expect(dev.container.register).not.toHaveBeenCalled();
    await expect(new PwaController(fake({ noSw: true }).env, OPTIONS).start()).resolves.toBeUndefined();
  });

  it('keeps working when the browser refuses the registration', async () => {
    const f = fake({ registerFails: true });
    const pwa = new PwaController(f.env, OPTIONS);
    await expect(pwa.start()).resolves.toBeUndefined();
    expect(pwa.getState().update).toBe(false);
  });

  it('keeps working when register resolves with no registration (Firefox under Playwright with blocked service workers)', async () => {
    const f = fake();
    f.container.register.mockResolvedValueOnce(undefined);
    const pwa = new PwaController(f.env, OPTIONS);
    await expect(pwa.start()).resolves.toBeUndefined();
    expect(f.timers).toEqual([]);
  });

  it('shows no notice for the first install (no controller yet)', async () => {
    const f = fake({ controller: false });
    f.reg.waiting = new FakeWorker();
    const pwa = new PwaController(f.env, OPTIONS);
    await pwa.start();
    expect(pwa.getState().update).toBe(false);
  });

  it('shows the notice for a version that already waits, and for one that finishes installing later', async () => {
    const early = fake();
    early.reg.waiting = new FakeWorker();
    const a = new PwaController(early.env, OPTIONS);
    await a.start();
    expect(a.getState().update).toBe(true);

    const late = fake();
    const b = new PwaController(late.env, OPTIONS);
    const seen = vi.fn();
    b.subscribe(seen);
    await b.start();
    const worker = new FakeWorker();
    late.reg.installing = worker;
    late.reg.emit('updatefound');
    worker.become('installing');
    expect(b.getState().update).toBe(false);
    worker.become('installed');
    expect(b.getState().update).toBe(true);
    expect(seen).toHaveBeenCalled();
  });

  it('reloads only after "Reload": the waiting worker gets SKIP_WAITING, then the controller change reloads', async () => {
    const f = fake();
    const worker = new FakeWorker();
    f.reg.waiting = worker;
    const pwa = new PwaController(f.env, OPTIONS);
    await pwa.start();
    f.container.emit('controllerchange'); // another tab took the update: this page keeps its game
    expect(f.reload).not.toHaveBeenCalled();
    pwa.applyUpdate();
    expect(worker.messages).toEqual([{ type: 'SKIP_WAITING' }]);
    expect(f.reload).not.toHaveBeenCalled();
    f.container.emit('controllerchange');
    expect(f.reload).toHaveBeenCalledTimes(1);
  });

  it('"Not now" hides the notice for this page view', async () => {
    const f = fake();
    f.reg.waiting = new FakeWorker();
    const pwa = new PwaController(f.env, OPTIONS);
    await pwa.start();
    pwa.dismissUpdate();
    expect(pwa.getState().update).toBe(false);
    const next = new FakeWorker();
    f.reg.installing = next;
    f.reg.emit('updatefound');
    next.become('installed');
    expect(pwa.getState().update).toBe(false);
  });

  it('asks for a new sw.js every hour and when the page becomes visible again', async () => {
    const f = fake();
    await new PwaController(f.env, OPTIONS).start();
    expect(f.timers.map((t) => t.ms)).toEqual([UPDATE_CHECK_MS]);
    f.timers[0]!.fn();
    f.document.visibilityState = 'hidden';
    f.document.emit('visibilitychange');
    f.document.visibilityState = 'visible';
    f.document.emit('visibilitychange');
    expect(f.reg.update).toHaveBeenCalledTimes(2);
  });
});

describe('PwaController: install', () => {
  const promptEvent = () => {
    const e = new Event('beforeinstallprompt', { cancelable: true }) as Event & { prompt: ReturnType<typeof vi.fn>; userChoice: Promise<{ outcome: string }> };
    e.prompt = vi.fn(async () => undefined);
    e.userChoice = Promise.resolve({ outcome: 'accepted' });
    return e;
  };

  it('offers the install after beforeinstallprompt, suppresses the browser bar, and shows the prompt once on click', async () => {
    const f = fake();
    const pwa = new PwaController(f.env, OPTIONS);
    expect(pwa.getState().install).toBeNull();
    const e = promptEvent();
    f.window.emit('beforeinstallprompt', e);
    expect(e.defaultPrevented).toBe(true);
    expect(pwa.getState().install).toBe('prompt');
    await pwa.promptInstall();
    await pwa.promptInstall();
    expect(e.prompt).toHaveBeenCalledTimes(1);
    expect(pwa.getState().install).toBeNull();
  });

  it('hides the offer after appinstalled, and offers nothing in the installed app', () => {
    const f = fake();
    const pwa = new PwaController(f.env, OPTIONS);
    f.window.emit('beforeinstallprompt', promptEvent());
    f.window.emit('appinstalled');
    expect(pwa.getState().install).toBeNull();
    const app = fake({ standalone: true });
    const inApp = new PwaController(app.env, OPTIONS);
    app.window.emit('beforeinstallprompt', promptEvent());
    expect(inApp.getState().install).toBeNull();
  });

  it('gives the Share hint on Safari for iOS, but not from the home screen and not in other iOS browsers', () => {
    expect(new PwaController(fake({ ua: UA.iphoneSafari }).env, OPTIONS).getState().install).toBe('ios');
    const home = fake({ ua: UA.iphoneSafari });
    (home.env.navigator as { standalone?: boolean }).standalone = true;
    expect(new PwaController(home.env, OPTIONS).getState().install).toBeNull();
    expect(new PwaController(fake({ ua: UA.iphoneChrome }).env, OPTIONS).getState().install).toBeNull();
  });
});
