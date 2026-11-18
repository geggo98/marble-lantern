// The committed calibration (calibration.json), written by scripts/web/calibrate-skill.zsh --aggregate.
import data from './calibration.json';
import type { Calibration } from './calibration';

export const CALIBRATION = data as unknown as Calibration;
