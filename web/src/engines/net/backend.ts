// The interface between the search and a network inference backend (interface contract IC1 of the browser net engine).
// A backend runs the forward pass of the hex-convolution network of docs/network.md on batches of encoded positions.

/** The raw heads of a batch, as the Java Network returns them before the legal-move mask and the softmax. */
export interface NetOutputs {
  /** batch * 121 "from" logits per cell. The logit of a move is policyFrom[from] + policyTo[to]. Includes the classical distance prior. */
  policyFrom: Float32Array;
  /** batch * 121 "to" logits per cell. Includes the classical distance prior. */
  policyTo: Float32Array;
  /** batch * valueWidth values after the tanh. Includes the classical value prior. */
  values: Float32Array;
}

export interface NetBackend {
  readonly kind: 'wasm' | 'webgpu';
  /** Input planes per cell of the loaded net; 0 before load() has resolved. */
  readonly planes: number;
  /** Width of the value head (2 or 3); 0 before load() has resolved. */
  readonly valueWidth: number;
  /** Takes the bytes of an F32 safetensors file (what loadModel returns). Replaces a net loaded before. */
  load(weights: Uint8Array): Promise<void>;
  /**
   * inputs holds batch * 122 * planes floats: the rows of the Java Encoder in canonical orientation, row 121 is the padding
   * row. The array must not change before the returned promise has settled.
   */
  evaluate(inputs: Float32Array, batch: number): Promise<NetOutputs>;
  /** Releases the GPU or wasm resources. Pending evaluate calls reject with a BackendError of code 'disposed'. */
  dispose(): void;
}

export type BackendErrorCode =
  | 'unavailable' // no WebGPU, no adapter or no device
  | 'device-lost' // the GPU device was lost (driver reset, GPU process crash)
  | 'unsupported-model' // the net does not fit the limits of the device or the shapes the kernels support
  | 'invalid-weights' // the file is not a readable net
  | 'not-loaded' // evaluate before load
  | 'disposed' // the backend was disposed
  | 'invalid-input'; // batch or input length wrong

/** A typed error of a backend; callers branch on `code`. */
export class BackendError extends Error {
  readonly code: BackendErrorCode;
  constructor(code: BackendErrorCode, message: string, options?: { cause?: unknown }) {
    super(message, options);
    this.name = 'BackendError';
    this.code = code;
  }
}
