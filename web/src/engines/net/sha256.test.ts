// The pure-JS SHA-256 against the FIPS 180-4 / NIST vectors and against node:crypto and crypto.subtle for random inputs,
// including every length around the padding borders.
import { createHash, randomBytes } from 'node:crypto';
import { describe, expect, it } from 'vitest';
import { sha256HexJs } from './sha256';

const enc = (s: string): Uint8Array => new TextEncoder().encode(s);

describe('sha256HexJs', () => {
  it.each([
    ['', 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855'],
    ['abc', 'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad'],
    ['abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq', '248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1'],
    ['abcdefghbcdefghicdefghijdefghijkefghijklfghijklmghijklmnhijklmnoijklmnopjklmnopqklmnopqrlmnopqrsmnopqrstnopqrstu', 'cf5b16a778af8380036ce59e7b0492370b249b11e8f07a51afac45037afee9d1'],
  ])('matches the NIST vector for %j', (input, want) => {
    expect(sha256HexJs(enc(input))).toBe(want);
  });

  it('matches the NIST vector of one million "a"', () => {
    expect(sha256HexJs(new Uint8Array(1_000_000).fill(0x61))).toBe('cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0');
  });

  it('matches node:crypto for every length from 0 to 200 (padding borders at 55, 56, 63, 64)', () => {
    for (let n = 0; n <= 200; n++) {
      const b = new Uint8Array(randomBytes(n));
      expect(sha256HexJs(b), `length ${n}`).toBe(createHash('sha256').update(b).digest('hex'));
    }
  });

  it('matches crypto.subtle for random inputs of random sizes up to 300 kB, also for a view into a larger buffer', async () => {
    for (let i = 0; i < 20; i++) {
      const size = Math.floor(Math.random() * 300_000);
      const big = new Uint8Array(randomBytes(size + 10));
      const view = big.subarray(3, 3 + size);
      const want = Buffer.from(await crypto.subtle.digest('SHA-256', view)).toString('hex');
      expect(sha256HexJs(view), `size ${size}`).toBe(want);
    }
  });
});
