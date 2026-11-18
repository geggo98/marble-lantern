// A fake browser history for tests. Do not import it from production code.
import { isOwnEntry } from './game-nav';
import type { NavEvent, NavigationPort } from './game-nav';

interface Entry {
  hash: string;
  state: unknown;
}

const OWN_STATE = { ml: 'game' };

export class FakeNavigation implements NavigationPort {
  entries: Entry[];
  index = 0;
  private listeners = new Set<(event: NavEvent) => void>();
  readonly pushes: string[] = [];
  readonly replaces: string[] = [];

  constructor(initialHash = '', initialState: unknown = null) {
    this.entries = [{ hash: initialHash, state: initialState }];
  }

  get hash(): string {
    return this.entries[this.index]!.hash;
  }

  get state(): unknown {
    return this.entries[this.index]!.state;
  }

  get href(): string {
    return `http://localhost/marble-lantern/${this.hash}`;
  }

  push(fragment: string): void {
    this.entries.splice(this.index + 1);
    this.entries.push({ hash: fragment, state: OWN_STATE });
    this.index++;
    this.pushes.push(fragment);
  }

  replace(fragment: string): void {
    this.entries[this.index] = { hash: fragment, state: OWN_STATE };
    this.replaces.push(fragment);
  }

  subscribe(listener: (event: NavEvent) => void): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  private fire(): void {
    const event = { hash: this.hash, state: this.state };
    for (const l of [...this.listeners]) l(event);
  }

  back(): void {
    if (this.index === 0) return;
    this.index--;
    this.fire();
  }

  forward(): void {
    if (this.index === this.entries.length - 1) return;
    this.index++;
    this.fire();
  }

  /** The user types or pastes an address with this fragment: a new entry that the app did not write. */
  userNavigates(fragment: string): void {
    this.entries.splice(this.index + 1);
    this.entries.push({ hash: fragment, state: null });
    this.index++;
    this.fire();
  }

  /** Hashes of all entries, oldest first, for assertions. */
  get hashes(): string[] {
    return this.entries.map((e) => e.hash);
  }

  get ownEntries(): number {
    return this.entries.filter((e) => isOwnEntry(e.state)).length;
  }
}
