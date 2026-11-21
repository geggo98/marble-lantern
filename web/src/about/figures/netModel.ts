// V9a: layers, shapes and parameter counts of the shipped models and the gather table, read from net-shape.json and forward-mid.json.
// Nothing is typed in: the shapes follow from the metadata of the safetensors headers (inputPlanes, channels, players) and the table.
import shape from '../data/net-shape.json';
import forwardMid from '../data/forward-mid.json';

export type LayerId = 'input' | 'conv_in' | 'block0' | 'block1' | 'block2' | 'block3' | 'head_policy' | 'head_value' | 'priors';
export const LAYER_IDS: readonly LayerId[] = ['input', 'conv_in', 'block0', 'block1', 'block2', 'block3', 'head_policy', 'head_value', 'priors'];
export const BLOCK_IDS: readonly LayerId[] = ['block0', 'block1', 'block2', 'block3'];

export type ModelId = 'expert' | 'shared';
const MODEL_FILE: Record<ModelId, string> = { expert: 'small-2p-adjacent-expert', shared: 'small-shared-all' };

export interface ModelRow {
  id: string;
  inputPlanes: number;
  channels: number;
  blocks: number;
  players: number;
  params: number;
  layers: { id: string; params: number }[];
}

export const modelRow = (model: ModelId): ModelRow => {
  const row = (shape.models as ModelRow[]).find((m) => m.id === MODEL_FILE[model]);
  if (!row) throw new Error(`net-shape.json has no model ${MODEL_FILE[model]}`);
  return row;
};

export const GATHER = shape.gather as number[][];
export const PADDING_ROW = shape.meta.paddingRow;
export const PRIORS = { beta: forwardMid.meta.beta, k: forwardMid.meta.k };

export interface LayerInfo {
  id: LayerId;
  params: number;
  /** `[122][13]` and so on, written like the arrays of the code. */
  shape: string;
}

export function layerInfo(model: ModelId, id: LayerId): LayerInfo {
  const m = modelRow(model);
  const rows = PADDING_ROW + 1;
  const params = id === 'input' ? 0 : (m.layers.find((l) => l.id === id)?.params ?? 0);
  const shapes: Record<LayerId, string> = {
    input: `[${rows}][${m.inputPlanes}]`,
    conv_in: `[${rows}][${m.channels}]`,
    block0: `[${rows}][${m.channels}]`,
    block1: `[${rows}][${m.channels}]`,
    block2: `[${rows}][${m.channels}]`,
    block3: `[${rows}][${m.channels}]`,
    head_policy: `[${GATHER.length}][2]`,
    head_value: `[${m.players}]`,
    priors: '2 × [1]',
  };
  return { id, params, shape: shapes[id] };
}

/** The neighbours of a cell on the board (the padding entries of its gather row are left out). */
export const neighboursOf = (cell: number): number[] => (GATHER[cell] as number[]).slice(1).filter((g) => g !== PADDING_ROW);
