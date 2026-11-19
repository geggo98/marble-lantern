import { describe, expect, it } from 'vitest';
import { seatNameKey, seatViews } from './seat-view';

describe('seatViews', () => {
  it('two players: the person is amber (A), the engine blue (B), on either seat', () => {
    expect(seatViews(['human', 'engine'], false).map((v) => v.style)).toEqual(['a', 'b']);
    expect(seatViews(['engine', 'human'], false).map((v) => v.style)).toEqual(['b', 'a']);
  });

  it('two players, demo: seat 1 stays amber as before', () => {
    expect(seatViews(['engine', 'engine'], true).map((v) => v.style)).toEqual(['b', 'a']);
  });

  it('three players, one person: the person is amber, the engines take blue and pearl in seat order', () => {
    expect(seatViews(['engine', 'human', 'engine'], false).map((v) => v.style)).toEqual(['b', 'a', 'c']);
    expect(seatViews(['human', 'engine', 'engine'], false).map((v) => v.style)).toEqual(['a', 'b', 'c']);
    expect(seatViews(['engine', 'engine', 'human'], false).map((v) => v.style)).toEqual(['b', 'c', 'a']);
  });

  it('hot-seat: the marble follows the seat, so each person keeps one marble', () => {
    for (const seats of [['human', 'human', 'engine'], ['human', 'engine', 'human'], ['engine', 'human', 'human']] as const) {
      expect(seatViews(seats, false).map((v) => v.style)).toEqual(['a', 'b', 'c']);
      expect(seatViews(seats, false).some((v) => v.you)).toBe(false);
    }
  });

  it('three engines (a demo): three different marbles', () => {
    expect(seatViews(['engine', 'engine', 'engine'], true).map((v) => v.style)).toEqual(['a', 'b', 'c']);
  });

  it('numbers the seats from 1 and names the one person "you"', () => {
    const views = seatViews(['engine', 'human', 'engine'], false);
    expect(views.map((v) => v.number)).toEqual([1, 2, 3]);
    expect(views.map(seatNameKey)).toEqual(['seat_engine', 'seat_you', 'seat_engine']);
    expect(seatViews(['human', 'human', 'engine'], false).map(seatNameKey)).toEqual(['seat_player', 'seat_player', 'seat_engine']);
  });
});
