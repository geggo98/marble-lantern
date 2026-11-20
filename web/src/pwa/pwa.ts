// The page side of the installable app: registers the service worker, reports a waiting update, and offers the install.
// One instance per page (main.tsx); the UI reads it through PwaPort. The Hilla build creates none, so the online mode has no service worker.

export interface PwaState {
  /** A new service worker is installed and waits for the page to ask for it. */
  update: boolean;
  /** 'prompt': the browser offered beforeinstallprompt; 'ios': Safari on iOS, not yet on the home screen; null: nothing to offer. */
  install: 'prompt' | 'ios' | null;
}

export interface PwaPort {
  getState(): PwaState;
  subscribe(listener: () => void): () => void;
  /** Registers the service worker (if this build has one); the app calls it once the first engine has loaded. */
  start(): Promise<void>;
  /** Activates the waiting version and reloads this page once the new service worker controls it. Only on a click. */
  applyUpdate(): void;
  /** Hides the update notice for this page view; the new version starts with the next visit. */
  dismissUpdate(): void;
  /** Shows the browser's install dialog (only while install is 'prompt'). */
  promptInstall(): Promise<void>;
}

/** The part of beforeinstallprompt that the page uses; the event is not in the DOM types. */
interface InstallPromptEvent extends Event {
  prompt(): Promise<unknown>;
  userChoice?: Promise<{ outcome: string }>;
}

/** The browser around the controller; tests pass fakes. */
export interface PwaEnv {
  window: Pick<Window, 'addEventListener'>;
  document: Pick<Document, 'addEventListener' | 'visibilityState'>;
  navigator: {
    userAgent: string;
    platform?: string;
    maxTouchPoints?: number;
    /** Safari on iOS: true when the page runs from the home screen. */
    standalone?: boolean;
    serviceWorker?: Pick<ServiceWorkerContainer, 'register' | 'addEventListener' | 'controller'>;
  };
  matchMedia: (query: string) => { matches: boolean };
  reload: () => void;
  setInterval: (fn: () => void, ms: number) => unknown;
}

export interface PwaOptions {
  /** Register the service worker: production build of the static site only. */
  register: boolean;
  /** URL of sw.js and the scope, both under the base path. */
  swUrl: string;
  scope: string;
}

/** How often a long-open page asks the server for a new sw.js. */
export const UPDATE_CHECK_MS = 60 * 60 * 1000;

/**
 * Safari on iOS or iPadOS, the browser that can put the page on the home screen without beforeinstallprompt.
 * iPadOS reports itself as a Mac ("MacIntel") with touch points. Chrome, Firefox and Edge on iOS name themselves (CriOS, FxiOS, EdgiOS).
 */
export function isIosSafari(nav: PwaEnv['navigator']): boolean {
  const ua = nav.userAgent;
  const ios = /iPad|iPhone|iPod/.test(ua) || (nav.platform === 'MacIntel' && (nav.maxTouchPoints ?? 0) > 1);
  return ios && /Safari\//.test(ua) && !/CriOS|FxiOS|EdgiOS|OPiOS|GSA\//.test(ua);
}

export function isStandalone(env: Pick<PwaEnv, 'navigator' | 'matchMedia'>): boolean {
  return env.navigator.standalone === true || env.matchMedia('(display-mode: standalone)').matches;
}

export class PwaController implements PwaPort {
  private state: PwaState = { update: false, install: null };
  private readonly listeners = new Set<() => void>();
  private deferred: InstallPromptEvent | null = null;
  private waiting: ServiceWorker | null = null;
  private reloadOnChange = false;
  private dismissed = false;
  private registered = false;

  constructor(
    private readonly env: PwaEnv,
    private readonly options: PwaOptions,
  ) {
    if (!isStandalone(env) && isIosSafari(env.navigator)) this.state = { ...this.state, install: 'ios' };
    env.window.addEventListener('beforeinstallprompt', (e) => {
      e.preventDefault(); // no mini-infobar: the menu offers the install
      this.deferred = e as InstallPromptEvent;
      if (!isStandalone(env)) this.set({ install: 'prompt' });
    });
    env.window.addEventListener('appinstalled', () => {
      this.deferred = null;
      this.set({ install: null });
    });
  }

  getState = (): PwaState => this.state;

  subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  };

  /**
   * Registers sw.js. The app calls it once the first engine has loaded: the install then fetches the table and the engine through the
   * HTTP cache instead of competing with the first download. Calling it again does nothing.
   */
  async start(): Promise<void> {
    const container = this.env.navigator.serviceWorker;
    if (!this.options.register || !container || this.registered) return;
    this.registered = true;
    container.addEventListener('controllerchange', () => {
      if (this.reloadOnChange) this.env.reload(); // only after a click on "Reload": another tab's update must not reload this page
    });
    let reg: ServiceWorkerRegistration | undefined;
    try {
      reg = await container.register(this.options.swUrl, { scope: this.options.scope });
    } catch {
      return; // blocked (private mode, a test browser): the site works as before, without the offline cache
    }
    if (!reg) return; // Firefox under Playwright's serviceWorkers: 'block' resolves with undefined
    const registration = reg;
    const offer = (worker: ServiceWorker | null): void => {
      // with no controller this is the first install, not an update: it activates by itself and needs no notice
      if (!worker || !container.controller) return;
      this.waiting = worker;
      if (!this.dismissed) this.set({ update: true });
    };
    offer(registration.waiting);
    registration.addEventListener('updatefound', () => {
      const worker = registration.installing;
      worker?.addEventListener('statechange', () => {
        if (worker.state === 'installed') offer(worker);
      });
    });
    const check = (): void => void registration.update().catch(() => undefined); // offline: try again next time
    this.env.setInterval(check, UPDATE_CHECK_MS);
    this.env.document.addEventListener('visibilitychange', () => {
      if (this.env.document.visibilityState === 'visible') check();
    });
  }

  applyUpdate = (): void => {
    if (!this.waiting) return;
    this.reloadOnChange = true;
    this.waiting.postMessage({ type: 'SKIP_WAITING' });
  };

  dismissUpdate = (): void => {
    this.dismissed = true;
    this.set({ update: false });
  };

  promptInstall = async (): Promise<void> => {
    const event = this.deferred;
    if (!event) return;
    this.deferred = null; // a prompt event works once
    this.set({ install: null });
    try {
      await event.prompt();
      const choice = await event.userChoice;
      if (choice?.outcome === 'dismissed') return; // Chrome sends a new beforeinstallprompt later
    } catch {
      // the browser refused the prompt; nothing to show
    }
  };

  private set(patch: Partial<PwaState>): void {
    this.state = { ...this.state, ...patch };
    for (const l of this.listeners) l();
  }
}

/** The controller of the static site in the browser. */
export function browserPwa(register: boolean): PwaController {
  const base = import.meta.env.BASE_URL;
  return new PwaController(
    {
      window,
      document,
      navigator: navigator as PwaEnv['navigator'],
      matchMedia: (q) => window.matchMedia(q),
      reload: () => window.location.reload(),
      setInterval: (fn, ms) => window.setInterval(fn, ms),
    },
    { register, swUrl: `${base}sw.js`, scope: base },
  );
}
