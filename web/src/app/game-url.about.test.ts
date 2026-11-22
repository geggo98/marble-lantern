// Routes of the Info part: #/about, #/about/<topic>, #/about/<topic>/<section> (plan section 1.2).
// Expected values come from the URL table of the plan, not from the output of parseHash.
import { describe, expect, it } from 'vitest';
import { formatGameFragment, parseHash } from './game-url';
import { ABOUT_TOPICS } from '../about/routes';
import { hrefForAbout } from '../ui/Chrome';

describe('parseHash: the About screen and its sub-routes', () => {
  it('keeps #/about as the hub, with no topic and no notice', () => {
    const parsed = parseHash('#/about');
    expect(parsed).toEqual({ kind: 'screen', screen: 'about' });
    expect(parsed).not.toHaveProperty('topic');
    expect(parsed).not.toHaveProperty('notFound');
  });

  it('accepts a trailing slash on the hub and on a topic', () => {
    expect(parseHash('#/about/')).toEqual({ kind: 'screen', screen: 'about' });
    expect(parseHash('#/about/classical/')).toEqual({ kind: 'screen', screen: 'about', topic: 'classical' });
  });

  it.each(['architecture', 'classical', 'inference', 'training', 'tuning', 'java', 'java-web', 'glossary'])('knows the topic %s', (topic) => {
    expect(parseHash(`#/about/${topic}`)).toEqual({ kind: 'screen', screen: 'about', topic });
  });

  it('lists the seven topics and the glossary, in order (java-web sits before the glossary)', () => {
    expect([...ABOUT_TOPICS]).toEqual(['architecture', 'classical', 'inference', 'training', 'tuning', 'java', 'java-web', 'glossary']);
  });

  it('reads the section of a topic', () => {
    expect(parseHash('#/about/classical/alpha-beta')).toEqual({ kind: 'screen', screen: 'about', topic: 'classical', section: 'alpha-beta' });
    expect(parseHash('#/about/glossary/node-budget')).toEqual({ kind: 'screen', screen: 'about', topic: 'glossary', section: 'node-budget' });
  });

  it('reads the java topic and its sections (the hilla section included)', () => {
    expect(parseHash('#/about/java')).toEqual({ kind: 'screen', screen: 'about', topic: 'java' });
    expect(parseHash('#/about/java/hilla')).toEqual({ kind: 'screen', screen: 'about', topic: 'java', section: 'hilla' });
    expect(parseHash('#/about/java/vector-api')).toEqual({ kind: 'screen', screen: 'about', topic: 'java', section: 'vector-api' });
  });

  it('shows the hub with a notice for an unknown topic', () => {
    expect(parseHash('#/about/nonsense')).toEqual({ kind: 'screen', screen: 'about', notFound: true });
    expect(parseHash('#/about/Classical')).toEqual({ kind: 'screen', screen: 'about', notFound: true }); // slugs are exact
  });

  it('shows the hub with a notice for a malformed section or a further segment', () => {
    expect(parseHash('#/about/classical/Alpha_Beta')).toEqual({ kind: 'screen', screen: 'about', notFound: true });
    expect(parseHash('#/about/classical/-x')).toEqual({ kind: 'screen', screen: 'about', notFound: true });
    expect(parseHash('#/about/classical/alpha-beta/more')).toEqual({ kind: 'screen', screen: 'about', notFound: true });
  });

  it('does not take other words that start with "about" for the About screen', () => {
    expect(parseHash('#/aboutx')).toEqual({ kind: 'screen', screen: 'play' });
    expect(parseHash('#/aboutx/classical')).toEqual({ kind: 'screen', screen: 'play' });
  });

  it('leaves the other routes as they were', () => {
    expect(parseHash('#/bench')).toEqual({ kind: 'screen', screen: 'bench' });
    expect(parseHash('#/bench/x')).toEqual({ kind: 'screen', screen: 'play' });
    expect(parseHash('')).toEqual({ kind: 'screen', screen: 'play' });
  });

  it('gives a game link its precedence over an about route that follows it', () => {
    const link = { moves: [], humanSide: 1, level: 3 } as const;
    const fragment = formatGameFragment({ moves: [], humanSide: link.humanSide, level: link.level });
    expect(parseHash(fragment).kind).toBe('game');
    expect(parseHash(`${fragment}/about/classical`).kind).not.toBe('screen'); // a game fragment, broken or not, never an about route
  });
});

describe('hrefForAbout', () => {
  it('builds the hub, topic and section fragments', () => {
    expect(hrefForAbout()).toBe('#/about');
    expect(hrefForAbout('inference')).toBe('#/about/inference');
    expect(hrefForAbout('classical', 'alpha-beta')).toBe('#/about/classical/alpha-beta');
  });

  it('round-trips through parseHash for every topic', () => {
    for (const topic of ABOUT_TOPICS) {
      expect(parseHash(hrefForAbout(topic, 'x1'))).toEqual({ kind: 'screen', screen: 'about', topic, section: 'x1' });
    }
  });
});
