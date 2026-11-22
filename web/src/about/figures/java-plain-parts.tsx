// Parts of figure J2 (plain Java): the lane of the comparison (`Compare` of the design, section 11), the memory map (one mark per 4-byte word, one
// row per cache line) and the readout with its test hooks. They are local to the figure; the design proposes both for the kit when J3 to J5 use them.
// No text of its own: every label arrives as a prop from the catalog.
import { useId } from 'react';
import type { ReactNode } from 'react';
import { WORDS_PER_LINE, mapRows } from './java-plain-model';
import type { MemoryMapData } from './java-plain-model';

/** One lane of a comparison: head with title and tag, then the picture. The lane that is the way of the project gets the solid frame. */
export function Lane({ lane, title, tag, ours, children }: { lane: 'objects' | 'primitives'; title: string; tag: string; ours?: boolean; children: ReactNode }): React.JSX.Element {
  const id = useId();
  return (
    <section className={ours ? 'ml-fig-cmp__lane ml-fig-cmp__lane--ours' : 'ml-fig-cmp__lane'} data-lane={lane} aria-labelledby={`${id}-t ${id}-g`}>
      <div className="ml-fig-cmp__head">
        <h4 className="ml-fig-cmp__title" id={`${id}-t`}>{title}</h4>
        <span className="ml-fig-cmp__tag" id={`${id}-g`}>{tag}</span>
      </div>
      {children}
    </section>
  );
}

export interface ReadoutCell {
  /** The catalog key of the label; it is also the test hook `data-value` of the value. */
  key: string;
  label: string;
  value: string;
}

/** Label and value cells of a lane. Every cell exists in every state; a value that does not exist is a dash. */
export function PlainReadout({ cells }: { cells: readonly ReadoutCell[] }): React.JSX.Element {
  return (
    <dl className="ml-fig-readout">
      {cells.map((c) => (
        <div key={c.key} className="ml-fig-readout__cell">
          <dt>{c.label}</dt>
          <dd className="ml-fig-readout__value" data-value={c.key}>
            {c.value}
          </dd>
        </div>
      ))}
    </dl>
  );
}

const CELL = 8;

/**
 * The memory map: an SVG with one mark per word, rows of one cache line (32 words of 4 bytes at 128 bytes). The marks carry no text; the accessible
 * name is `label`, and the readout under the map carries every number. View box 256 units wide, so it scales with the lane.
 */
export function MemoryMap({ data, label, title }: { data: MemoryMapData; label: string; title: string }): React.JSX.Element {
  const per = WORDS_PER_LINE;
  const rows = mapRows(data);
  const W = per * CELL;
  const H = rows * (CELL + 1) + 1;
  const xy = (i: number): [number, number] => [(i % per) * CELL, Math.floor(i / per) * (CELL + 1) + 1];
  const mark = (w: MemoryMapData['words'][number], i: number): React.JSX.Element => {
    const [x, y] = xy(i);
    const sel = w.sel ? ' is-sel' : '';
    switch (w.t) {
      case 'val':
        return <rect key={i} className={`w-val${sel}`} x={x + 0.5} y={y + 0.5} width={7} height={7} />;
      case 'hdr':
        return <rect key={i} className={`w-hdr${sel}`} x={x + 0.5} y={y + 2.5} width={7} height={3} />;
      case 'ref':
        return (
          <g key={i}>
            <rect className={`w-ref${sel}`} x={x + 1} y={y + 1} width={6} height={6} />
            <circle className={`w-refdot${sel}`} cx={x + 4} cy={y + 4} r={1.3} />
          </g>
        );
      case 'pad':
        return <circle key={i} className={`w-pad${sel}`} cx={x + 4} cy={y + 4} r={1.2} />;
      case 'free':
        return <rect key={i} className="w-free" x={x + 1} y={y + 1} width={6} height={6} />;
    }
  };
  // outline around a run of words, one rectangle per row
  const outlines: React.JSX.Element[] = [];
  for (const [first, last] of data.boxes) {
    for (let i = first; i <= last; ) {
      const rowEnd = Math.min(last, i - (i % per) + per - 1);
      const [x, y] = xy(i);
      outlines.push(<rect key={`b${i}`} className="sel-box" x={x - 0.25} y={y - 0.25} width={(rowEnd - i + 1) * CELL + 0.5} height={8.5} />);
      i = rowEnd + 1;
    }
  }
  let hop: React.JSX.Element | null = null;
  if (data.hop) {
    const [x1, y1] = xy(data.hop[0]);
    const [x2, y2] = xy(data.hop[1]);
    hop = (
      <g>
        <path className="hop" d={`M${x1 + 4} ${y1 + 8} L${x1 + 4} ${y2 - 3} L${x2 + 4} ${y2 - 3} L${x2 + 4} ${y2}`} />
        <circle className="hop-end" cx={x2 + 4} cy={y2} r={2.2} />
      </g>
    );
  }
  const dotsY = (rows - 1) * (CELL + 1) + 5;
  return (
    <svg className="ml-fig-mem" viewBox={`0 0 ${W} ${H}`} role="img" data-rows={rows} data-words={data.words.length}>
      <title>{title}</title>
      <desc>{label}</desc>
      {data.words.map(mark)}
      {outlines}
      {hop}
      {data.more && [W / 2 - 10, W / 2, W / 2 + 10].map((x) => <circle key={x} className="more" cx={x} cy={dotsY} r={1.6} />)}
    </svg>
  );
}
