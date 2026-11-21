// The hand-over from the play screen to the Info figures (live positions). Types only: the main bundle imports this file, so it holds no runtime code.
import type { AnalysisSource } from '../../app/switchable-engine';
import type { Variant } from '../../game/variant';

/** A copy of the game taken when the user presses an "Explain this position" link. It does not follow later moves; it lives in memory only. */
export interface LiveSnapshot {
  /** Increases with every press; caches key on it. */
  id: number;
  /** The moves from the start position, oldest first. */
  history: readonly number[];
  variant: Variant;
  /** history.length */
  ply: number;
  /** The game was over at the press (no legal move). */
  over: boolean;
}

export interface LiveSources {
  /** EngineSession.analysisSource(): the classical engine of this browser. */
  classical: AnalysisSource | null;
  /** EngineSession.netSource(): always 'net-wasm' (CPU). */
  net: AnalysisSource | null;
}

export interface LiveInput {
  snapshot: LiveSnapshot;
  /** Read when an engine is first needed, never earlier. */
  sources: () => LiveSources;
}

/** The link target of the Explain card: a topic and a section of the Info part. */
export interface ExplainTarget {
  topic: 'classical' | 'inference';
  section: string;
}
