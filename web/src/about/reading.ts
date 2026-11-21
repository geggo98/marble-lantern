// The reading list entries that topic pages cite. Verified on 2026-11-21 (hyperparameters fact sheet, section 6).
// STUB of WP1: WP0 delivers web/src/about/data/reading.json with all 27 rows; this file then imports it instead of listing entries.
export interface ReadingEntry {
  id: string;
  title: string;
  authors?: string;
  year?: number;
  url: string;
  level: 'beginner' | 'advanced';
  verifiedOn: string;
  paywalled?: boolean;
}

export const READING: Readonly<Record<string, ReadingEntry>> = {
  'cpw-minimax': { id: 'cpw-minimax', title: 'Minimax', authors: 'Chessprogramming Wiki', url: 'https://www.chessprogramming.org/Minimax', level: 'beginner', verifiedOn: '2026-11-21' },
  'cpw-alpha-beta': { id: 'cpw-alpha-beta', title: 'Alpha-Beta', authors: 'Chessprogramming Wiki', url: 'https://www.chessprogramming.org/Alpha-Beta', level: 'beginner', verifiedOn: '2026-11-21' },
  alphazero: {
    id: 'alphazero',
    title: 'Mastering Chess and Shogi by Self-Play with a General Reinforcement Learning Algorithm',
    authors: 'D. Silver, T. Hubert, J. Schrittwieser et al.',
    year: 2017,
    url: 'https://arxiv.org/abs/1712.01815',
    level: 'advanced',
    verifiedOn: '2026-11-21',
  },
  elo: { id: 'elo', title: 'Elo rating system', authors: 'Wikipedia', url: 'https://en.wikipedia.org/wiki/Elo_rating_system', level: 'beginner', verifiedOn: '2026-11-21' },
  'webgpu-mdn': { id: 'webgpu-mdn', title: 'WebGPU API', authors: 'MDN Web Docs', url: 'https://developer.mozilla.org/en-US/docs/Web/API/WebGPU_API', level: 'beginner', verifiedOn: '2026-11-21' },
};
