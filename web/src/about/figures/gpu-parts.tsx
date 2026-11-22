// Parts of figure J4 that carry no state of their own: the depth mark of a level, the small SVG fields (units, warps, instruction line, chain
// of the kernel grid), the pipe of level 1, the distance ladder. SVGs hold marks only, never prose; their texts are literals from the data
// or `aria-hidden` (the count stands in the value line next to them).
import type { ReactNode } from 'react';
import type { LadderRow, PartState } from './gpu-model';
import { WARP_SIZE } from './gpu-model';

/** Four nested squares, level k draws k of them in ink; the grid level has a grid mark. Level 4 adds a dot (one thread). */
export function DepthMark({ level }: { level: number }): React.JSX.Element {
  return (
    <svg className="ml-fig-zoom__depth" viewBox="0 0 22 22" aria-hidden="true" focusable="false">
      {level === 5 ? (
        <path className="on" d="M3 3h16v16H3zM3 8.3h16M3 13.6h16M11 3v16" />
      ) : (
        <>
          {[0, 1, 2, 3].map((i) => (
            <rect key={i} className={i < level ? 'on' : ''} x={1.5 + i * 2.6} y={1.5 + i * 2.6} width={19 - i * 5.2} height={19 - i * 5.2} rx="1.5" />
          ))}
          {level === 4 && <circle className="dot" cx="11" cy="11" r="1.6" />}
        </>
      )}
    </svg>
  );
}

/** One square per SM or GPU core (level 2); one is the marked unit that level 3 shows. 170 squares fit in 34 columns. */
export function UnitField({ units }: { units: number }): React.JSX.Element {
  const cols = units > 60 ? 34 : 20;
  const rows = Math.ceil(units / cols);
  const pitch = 244 / cols;
  const side = pitch - (units > 60 ? 1.4 : 2.4);
  const height = 40;
  const y0 = (height - rows * pitch) / 2 + 1;
  const marked = units > 60 ? 71 : 23;
  return (
    <svg viewBox={`0 0 248 ${height}`} aria-hidden="true" focusable="false">
      {Array.from({ length: units }, (_, i) => (
        <rect key={i} className={i === marked ? 'gp-unit gp-unit--cur' : 'gp-unit'} x={2 + (i % cols) * pitch} y={y0 + Math.floor(i / cols) * pitch} width={side} height={side} rx="1" />
      ))}
    </svg>
  );
}

/** 32 bars, one per warp that a unit runs at a time (level 3). Dashed when the maker publishes no limit. */
export function WarpBars({ known }: { known: boolean }): React.JSX.Element {
  return (
    <svg viewBox="0 0 248 52" aria-hidden="true" focusable="false">
      {Array.from({ length: 32 }, (_, i) => (
        <rect key={i} className={i === 10 ? 'gp-unit gp-unit--cur' : known ? 'gp-unit' : 'gp-unit gp-unit--unk'} x={2 + (i % 8) * 30.6} y={3 + Math.floor(i / 8) * 12} width="27" height="8" rx="1.5" />
      ))}
    </svg>
  );
}

/** One instruction line over 32 thread marks (level 4); the line is teal at the step "warp". One thread is marked and taller. */
export function SimtField({ lineActive }: { lineActive: boolean }): React.JSX.Element {
  let comb = 'M5 8H243';
  const ticks: React.JSX.Element[] = [];
  for (let i = 0; i < WARP_SIZE; i++) {
    const x = 2 + i * 7.66;
    comb += `M${x + 3} 8v7`;
    ticks.push(<rect key={i} className={i === 13 ? 'gp-unit gp-unit--cur' : 'gp-unit'} x={x} y="16" width="6" height={i === 13 ? 18 : 14} rx="1" />);
  }
  return (
    <svg viewBox="0 0 248 38" aria-hidden="true" focusable="false">
      <path className={lineActive ? 'gp-line gp-line--acc' : 'gp-line'} d={comb} />
      <path className="gp-fillacc" d="M0 4l6 4-6 4z" />
      {ticks}
    </svg>
  );
}

/** The chain picture of level 5: the grid as tiles, the tile of one work group, its 4 warps of 32 threads, one thread. */
export function ChainPicture({ active, id, title, desc }: { active: boolean; id: string; title: string; desc: string }): React.JSX.Element {
  const tiles: React.JSX.Element[] = [];
  for (let r = 0; r < 9; r++) for (let c = 0; c < 2; c++) tiles.push(<rect key={`${r}-${c}`} className={r === 3 && c === 1 ? 'gp-unit gp-unit--cur' : 'gp-unit'} x={3 + c * 26} y={6 + r * 9.5} width="24" height="7.5" />);
  const rows: React.JSX.Element[] = [];
  for (let r = 0; r < 4; r++) {
    rows.push(<rect key={`w${r}`} className={r === 1 && active ? 'gp-line gp-line--acc' : 'gp-line'} x="94" y={10 + r * 19} width="151" height="15" rx="2" />);
    for (let i = 0; i < 32; i++) rows.push(<rect key={`${r}-${i}`} className={r === 1 && i === 13 ? 'gp-fillacc' : 'gp-unit'} x={96.5 + i * 4.6} y={12.5 + r * 19} width="3" height="10" />);
  }
  return (
    <svg viewBox="0 0 248 96" role="img" aria-labelledby={`${id}-t ${id}-d`}>
      <title id={`${id}-t`}>{title}</title>
      <desc id={`${id}-d`}>{desc}</desc>
      {tiles}
      <path className="gp-zoom" d="M53 34.5L94 10M53 42L94 82" />
      {rows}
    </svg>
  );
}

/** The pipe between host memory and device memory. Its thickness is an ordinal cue (thin, thick), not a scale. One head points to the GPU or to the host; two when idle. */
export function Pipe({ thickness, dir, state }: { thickness: number; dir: 'in' | 'out' | undefined; state: PartState }): React.JSX.Element {
  return (
    <div className="gp-sys__pipe" data-pipe="" {...(state ? { 'data-state': state } : {})}>
      <svg viewBox="0 0 28 28" preserveAspectRatio="none" aria-hidden="true" focusable="false">
        <rect className="gp-pipe" x="0" y={14 - thickness / 2} width="28" height={thickness} />
        <path className="gp-pipe" d={dir === 'in' ? 'M28 14l-10 -9v18z' : dir === 'out' ? 'M0 14l10 -9v18z' : 'M28 14l-8 -7v14zM0 14l8 -7v14z'} />
      </svg>
    </div>
  );
}

/** The double arrow between the Java arrays and the Metal buffers inside the one memory of the M3 Max. */
export function CopyArrow({ state }: { state: PartState }): React.JSX.Element {
  return (
    <svg viewBox="0 0 22 12" aria-hidden="true" focusable="false" {...(state ? { 'data-state': state } : {})}>
      <path className="gp-pipe" d="M0 6l5 -5v3.5h12V1l5 5-5 5V7.5H5V11z" />
    </svg>
  );
}

export interface LadderProps {
  titleId: string;
  rows: readonly LadderRow[];
  reserved: number;
  active: boolean;
  label: (part: LadderRow['part']) => string;
  value: (row: LadderRow) => ReactNode;
  /** A part label stands for the hidden reserve rows; they are `aria-hidden`. */
  reserveLabel: string;
  dash: string;
}

/** The distance ladder (level 4): memory levels from the nearest to the farthest. The bar length gives the order, the number gives the size. */
export function DistanceLadder({ titleId, rows, reserved, active, label, value, reserveLabel, dash }: LadderProps): React.JSX.Element {
  return (
    <ol className="gp-ladder" aria-labelledby={titleId}>
      {rows.map((r) => (
        <li key={r.part} data-row={r.part} {...(active ? { 'data-state': 'active' } : {})}>
          <span>{label(r.part)}</span>
          <span className="gp-ladder__v">{value(r)}</span>
          <span className="gp-ladder__bar" style={{ width: `${r.bar}%` }} aria-hidden="true" />
        </li>
      ))}
      {Array.from({ length: reserved }, (_, i) => (
        <li key={`reserve-${i}`} data-off="" aria-hidden="true">
          <span>{reserveLabel}</span>
          <span className="gp-ladder__v">{dash}</span>
          <span className="gp-ladder__bar" />
        </li>
      ))}
    </ol>
  );
}
