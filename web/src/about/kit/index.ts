// The figure kit of the Info part. Importing this module loads the figure tokens and styles; it belongs to the lazy topic chunks only.
import '../../../design/about/tokens-about.css';
import './figure.css';

export { Figure, FigureDetail, Legend, LegendItem, Chip } from './Figure';
export type { FigureProps, FigureDetailProps } from './Figure';
export { Manipulate, Ctl, Slider, Segmented, Presets, Checkbox, Select, Readout, Stepper } from './Manipulate';
export type { StepperLabels, StepperProps, SliderProps, SegmentedProps, PresetsProps, CheckboxProps, SelectProps, ReadoutProps, CtlProps } from './Manipulate';
export { useStepper, stepperReducer, initialStepper, PLAY_INTERVAL_MS } from './useStepper';
export type { StepperState, StepperAction, UseStepper } from './useStepper';
export { Plot, PlotLine, PlotBars, PlotDots, PlotRef, PlotBand, PlotCursor, usePlot, useMeasuredWidth } from './Plot';
export type { PlotProps, NumericAxis, CategoryAxis, XAxis, Dot, Bar, Point, Series } from './Plot';
export { PLOT_WIDE, PLOT_COMPACT, PLOT_BREAKPOINT_PX, plotBoxFor, linearScale, logScale, categoryScale, linearTicks, logTicks, formatCompact } from './plot-scale';
export { MiniBoard, HeatBoard } from './Boards';
export type { MiniBoardProps, HeatBoardProps, CellPicker, HeatCell } from './Boards';
export { heatLevel, signedLevel, arrowGeometry, HEAT_RADIUS } from './board-heat';
export type { Arrow, HeatLevel, SignedLevel } from './board-heat';
export { BoxDiagram } from './BoxDiagram';
export type { BoxDiagramProps, BoxDiagramLabels, BoxKind, Flow } from './BoxDiagram';
export { layoutBoxes, METRICS } from './box-layout';
export type { ArchData, ArchBox, ArchEdge, BoxLayout, LayoutMode } from './box-layout';
export { IcicleTree, icicleGeometry } from './IcicleTree';
export type { IcicleTreeProps, IcicleColumn, CellState } from './IcicleTree';
export { ReadingList } from './ReadingList';
export type { ReadingItem, ReadingListProps } from './ReadingList';
export { KIT_EN, kitEn, stepperLabels, boxDiagramLabels } from './labels';
export type { KitKey, KitT } from './labels';
