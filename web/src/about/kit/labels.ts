// The shared keys of the figure kit (`kit.*`, web/design/about/keys-requested.md) as a typed stub. The kit components take label strings
// as props; this file names the keys and gives English working texts, so that a figure can build its props before the text writer's
// `figures.json` exists. The German texts and the final English texts belong to the text package, not here.
import type { BoxDiagramLabels } from './BoxDiagram';
import type { StepperLabels } from './Manipulate';

export const KIT_EN = {
  'kit.fig.label': 'Fig.',
  'kit.stepper.reset': 'Back to the start',
  'kit.stepper.back': 'One step back',
  'kit.stepper.play': 'Play',
  'kit.stepper.pause': 'Pause',
  'kit.stepper.forward': 'One step forward',
  'kit.stepper.position': 'Step',
  'kit.stepper.step': 'Step {k} of {n}',
  'kit.presets.label': 'Presets',
  'kit.flow.label': 'Show',
  'kit.flow.data': 'Data flow',
  'kit.flow.control': 'Control flow',
  'kit.flow.both': 'Both',
  'kit.mode.label': 'View',
  'kit.detail.close': 'Close details',
  'kit.node.kind.component': 'Component',
  'kit.node.kind.store': 'Store',
  'kit.node.kind.external': 'External',
  'kit.disclosure.show': 'Show more',
  'kit.disclosure.hide': 'Show less',
  'kit.edge.data': 'Data',
  'kit.edge.control': 'Control',
  'kit.legend.active': 'Current step',
  'kit.legend.cursor': 'Chosen value',
  'kit.legend.interval': '95 % interval',
  'kit.legend.heat': 'Strength',
  'kit.legend.low': 'low',
  'kit.legend.high': 'high',
  'kit.legend.signed': 'Sign',
  'kit.legend.negative': 'negative',
  'kit.legend.zero': 'zero',
  'kit.legend.positive': 'positive',
  'kit.legend.visited': 'searched',
  'kit.legend.activenode': 'current',
  'kit.legend.skipped': 'cut off',
  'kit.legend.todo': 'not reached yet',
  'kit.legend.jump': 'jump',
  'kit.legend.step': 'step',
  'kit.board.orientation': 'The {top} seat is at the top, the {bottom} seat at the bottom.',
  'kit.board.cell': 'Cell {cell}, {content}',
  'kit.reading.title': 'Further reading',
  'kit.reading.level.intro': 'Introduction',
  'kit.reading.level.advanced': 'Advanced',
  'kit.reading.paywalled': 'may need a subscription',
  'kit.chip.src': 'Source file',
  'kit.chip.data': 'Data file',
} as const;

export type KitKey = keyof typeof KIT_EN;
export type KitT = (key: KitKey, values?: Record<string, string | number>) => string;

/** English fallback lookup with `{name}` substitution. */
export const kitEn: KitT = (key, values) => {
  const text: string = KIT_EN[key];
  return values ? text.replace(/\{(\w+)\}/g, (whole, name: string) => (name in values ? String(values[name]) : whole)) : text;
};

/** The labels of a Stepper from a lookup; `group` is the figure's own `fig.<id>.stepper`. */
export const stepperLabels = (t: KitT, group: string): StepperLabels => ({
  group,
  reset: t('kit.stepper.reset'),
  back: t('kit.stepper.back'),
  play: t('kit.stepper.play'),
  pause: t('kit.stepper.pause'),
  forward: t('kit.stepper.forward'),
  position: t('kit.stepper.position'),
});

/** The labels of a BoxDiagram from a lookup; `diagram` and `keylist` are the figure's own keys. */
export const boxDiagramLabels = (t: KitT, diagram: string, keylist: string): BoxDiagramLabels => ({
  diagram,
  keylist,
  data: t('kit.edge.data'),
  control: t('kit.edge.control'),
});
