// A short vibration as feedback for a touch. Where the browser has no vibrate (desktop, Safari on iOS) or refuses it,
// nothing happens: the feedback is an addition, no message depends on it.
export function haptic(pattern: number | number[]): void {
  try {
    navigator.vibrate?.(pattern);
  } catch {
    // blocked by the browser or by a permission policy
  }
}

export const HAPTIC_SELECT = 8;
export const HAPTIC_MOVE = 16;
export const HAPTIC_ILLEGAL = [18, 40, 18];
