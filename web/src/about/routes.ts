// The topics of the Info part and the shape of an `#/about/...` route. Data only: the main bundle imports this file,
// so it must not import any text, figure or React code.

export const ABOUT_TOPICS = ['architecture', 'classical', 'inference', 'training', 'tuning', 'java', 'glossary'] as const;
export type AboutTopic = (typeof ABOUT_TOPICS)[number];

export const isAboutTopic = (value: string): value is AboutTopic => (ABOUT_TOPICS as readonly string[]).includes(value);

/** A section slug (or, for the glossary, a term id): fixed English words, lower case, digits and hyphens. */
export const isSlug = (value: string): boolean => /^[a-z0-9][a-z0-9-]*$/.test(value);

/** What the fragment says about the About screen. No topic: the hub (with a notice when `notFound`). */
export interface AboutRoute {
  topic?: AboutTopic;
  section?: string;
  notFound?: boolean;
}

/** The id of a section heading, and the anchor target of every in-page link: `about-<topic>-<section>`. */
export const sectionId = (topic: AboutTopic, section: string): string => `about-${topic}-${section}`;
