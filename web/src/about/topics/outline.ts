// The section slugs of each topic (plan section 2). Slugs are fixed English words, the same in both languages.
// Imported by the topic pages and by the tests, never by the main bundle.
import type { AboutTopic } from '../routes';

export const OUTLINE: Record<Exclude<AboutTopic, 'glossary'>, readonly string[]> = {
  architecture: ['overview', 'modules', 'offline', 'build', 'online', 'training', 'threads'],
  classical: ['board', 'game-tree', 'minimax', 'alpha-beta', 'budget', 'speedups', 'evaluation', 'race', 'three-players', 'strength', 'mcts-note'],
  inference: ['why-mcts', 'network-basics', 'encoding', 'hex-conv', 'trunk-heads', 'forward-pass', 'mcts', 'backends', 'model-files'],
  training: ['overview', 'samples', 'teacher', 'learning', 'loop', 'throughput', 'where', 'results', 'jobs'],
  tuning: ['what', 'measuring', 'decisions', 'hand-tuning', 'regression', 'grids', 'sweeps', 'recipe-sweep', 'pre-registered', 'never-tuned'],
  java: ['overview', 'plain-java', 'vector-api', 'auto-vectorization', 'tornadovm', 'outlook', 'more', 'hilla'],
};
