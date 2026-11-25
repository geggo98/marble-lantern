// Fig. 4.4: the marks inside the plot. They use `usePlot()` of the kit (as the ring of J5 does) and draw, per view: the dominated region and the front
// step line, the level of SMALL generation 9, the complete runs with their ladder whiskers, the direct-match triangle, the CPU lower-bound lines,
// the power band and the CPU-against-GPU paths, the ring of the selected row, the labels and the invisible hit circles. All positions come from
// the pure model; nothing is typed here that is not derived from data/energy.json. The rows are also reachable by the table buttons (the complete,
// accessible way); the hit circles are for the pointer and carry no focus and no name.
import { PlotBand, usePlot } from '../kit';
import type { FigText } from './shared';
import { frontPath, frontPoints, hitRadii, markKind, matchOf, rowById, rowCode, strengthY, viewSpec, xOf } from './energy-model';
import type { Basis, EnergyData, EnergyRow, View } from './energy-model';

export interface MarksProps {
  data: EnergyData;
  view: View;
  basis: Basis;
  selected: string;
  onSelect: (id: string) => void;
  fig: FigText;
}

/** The vertical whisker with caps of 10 units. */
const whisker = (cx: number, y0: number, y1: number): string => `M${cx} ${y0}V${y1}M${cx - 5} ${y0}H${cx + 5}M${cx - 5} ${y1}H${cx + 5}`;

/** A mark of a net: a circle for SMALL, a square for LARGE; solid when measured, hollow for an estimate or a value the ladder cannot rank. */
function Shape({ net, cx, cy, hollow, r = 5 }: { net: EnergyRow['net']; cx: number; cy: number; hollow?: boolean; r?: number }): React.JSX.Element {
  const fill = hollow ? 'hollow' : 'solid';
  return net === 'SMALL' ? (
    <circle className="ml-fig-energy__mark" data-fill={fill} cx={cx} cy={cy} r={r} />
  ) : (
    <rect className="ml-fig-energy__mark" data-fill={fill} x={cx - r} y={cy - r} width={2 * r} height={2 * r} />
  );
}

/** Breaks a label into lines of at most `max` characters at spaces. */
export function wrapLabel(text: string, max: number): string[] {
  const lines: string[] = [];
  let line = '';
  for (const word of text.split(' ')) {
    if (line && (line + ' ' + word).length > max) {
      lines.push(line);
      line = word;
    } else line = line ? `${line} ${word}` : word;
  }
  if (line) lines.push(line);
  return lines;
}

const TRI_UP = '0,-7 6,5 -6,5';
const TRI_DOWN = '0,7 6,-5 -6,-5';

export function EnergyMarks({ data, view, basis, selected, onSelect, fig }: MarksProps): React.JSX.Element {
  const { box, area, sx, sy } = usePlot();
  const { t, fmt } = fig;
  const compact = box.name === 'compact';
  const spec = viewSpec(view, compact);
  const both = view === 'both';
  const strengthView = view !== 'both' ? view : undefined;
  const sel = rowById(data, selected);
  const hit: { id: string; x: number; y: number }[] = [];
  const code = (r: EnergyRow): string => rowCode(r);

  // ---- the strength views ----
  let strengthMarks: React.ReactNode = null;
  if (strengthView) {
    const shape = frontPath(frontPoints(strengthView, basis, data), spec.x.domain[1], 0);
    const px = (p: { x: number; y: number }): string => `${sx(p.x).toFixed(2)} ${sy(p.y).toFixed(2)}`;
    const s9 = rowById(data, 'small-gen9');
    const runs = data.rows.filter((r) => r.group === 'run' && r.strength);
    const bounds = data.rows.filter((r) => markKind(r, strengthView) === 'bound');
    runs.forEach((r) => hit.push({ id: r.id, x: sx(xOf(r, strengthView)), y: sy(strengthY(r, basis, data)) }));
    strengthMarks = (
      <>
        <path className="ml-fig-energy__region" d={`M${shape.region.map(px).join('L')}Z`} data-part="region" />
        <line className="ml-fig-plot__ref" x1={sx(xOf(s9, strengthView))} x2={area.x1} y1={sy(strengthY(s9, basis, data))} y2={sy(strengthY(s9, basis, data))} data-part="s9-level" />
        <g data-part="front">
          {shape.line.slice(1).map((v, i) => {
            const u = shape.line[i]!;
            return <path key={i} className={v.dashed ? 'ml-fig-energy__front ml-fig-energy__front--dashed' : 'ml-fig-energy__front'} d={`M${px(u)}L${px(v)}`} />;
          })}
        </g>
        {bounds.map((r, i) => {
          const x = sx(xOf(r, strengthView));
          const labelY = area.y1 - 6 - i * 15;
          return (
            <g key={r.id} data-bound={r.id}>
              <line className="ml-fig-energy__bound" x1={x} x2={x} y1={area.y1} y2={area.y0} />
              <path className="ml-fig-energy__arrow" d={`M${x} ${area.y0 + 2}L${x + 7} ${area.y0 + 6}L${x} ${area.y0 + 10}Z`} />
              <text className="ml-fig-energy__cpulabel" x={x - 6} y={labelY} textAnchor="end" data-selected={r.id === selected ? '' : undefined}>
                {compact ? code(r) : t('fig.energy.cpu.bound', { code: code(r) })}
              </text>
            </g>
          );
        })}
        {runs.map((r) => {
          const x = sx(xOf(r, strengthView));
          const ladder = r.strength!;
          const m = matchOf(r, basis, data);
          const unranked = m !== undefined;
          return (
            <g key={r.id} data-row={r.id}>
              <path className={unranked ? 'ml-fig-plot__whisker ml-fig-energy__whisker--dashed' : 'ml-fig-plot__whisker'} d={whisker(x, sy(ladder.elo - ladder.ci), sy(ladder.elo + ladder.ci))} />
              <Shape net={r.net} cx={x} cy={sy(ladder.elo)} hollow={unranked} />
            </g>
          );
        })}
        {runs.map((r) => {
          const m = matchOf(r, basis, data);
          if (!m) return null;
          const x = sx(xOf(r, strengthView));
          const y = strengthY(r, basis, data);
          return (
            <g key={`match-${r.id}`} data-match={basis} data-row={r.id}>
              <path className="ml-fig-plot__whisker" d={whisker(x, sy(y - m.ci), sy(y + m.ci))} />
              <polygon className={basis === 'sims' ? 'ml-fig-energy__tri ml-fig-energy__tri--up' : 'ml-fig-energy__tri ml-fig-energy__tri--down'} points={basis === 'sims' ? TRI_UP : TRI_DOWN} transform={`translate(${x} ${sy(y)})`} />
            </g>
          );
        })}
        <text className="ml-fig-energy__cue" x={area.x0 + 8} y={area.y0 + 15}>
          {`↖ ${t('fig.energy.better')}`}
        </text>
      </>
    );
    // labels of the complete runs: S0 and L0 right of the mark, S9 left of it, L10 above the top of its whisker
    strengthMarks = (
      <>
        {strengthMarks}
        {runs.map((r) => {
          const x = sx(xOf(r, strengthView));
          const ladder = r.strength!;
          const side = r.id === 'small-gen9' ? 'left' : r.generation === 10 ? 'above' : 'right';
          const top = Math.min(sy(ladder.elo + ladder.ci), sy(ladder.elo));
          return (
            <text
              key={r.id}
              className="ml-fig-energy__label"
              data-selected={r.id === selected ? '' : undefined}
              x={side === 'left' ? x - 9 : side === 'above' ? x : x + 9}
              y={side === 'above' ? top - 8 : sy(ladder.elo) - 8}
              textAnchor={side === 'left' ? 'end' : side === 'above' ? 'middle' : 'start'}
            >
              {code(r)}
            </text>
          );
        })}
      </>
    );
    bounds.forEach((r) => hit.push({ id: r.id, x: sx(xOf(r, strengthView)), y: area.y0 + 6 }));
  }

  // ---- the view with both costs ----
  let costMarks: React.ReactNode = null;
  if (both) {
    const [wLo, wHi] = data.machine.trainW;
    const xTop = spec.y.domain[1] / (wHi / 1000);
    const xs = [spec.x.domain[0], xTop];
    const at = (r: EnergyRow): { x: number; y: number } => ({ x: sx(r.hours), y: sy(r.kwh) });
    data.rows.forEach((r) => hit.push({ id: r.id, ...at(r) }));
    const factor = (net: EnergyRow['net']): { time: number; energy: number } => data.factors.find((f) => f.net === net)!;
    const pair = (net: EnergyRow['net']): { gpu: EnergyRow; cpu: EnergyRow } => ({
      gpu: data.rows.find((r) => r.net === net && r.group === 'steps' && r.trainer === 'gpu')!,
      cpu: data.rows.find((r) => r.net === net && r.group === 'steps' && r.trainer === 'cpu')!,
    });
    const small = pair('SMALL');
    const large = pair('LARGE');
    // SMALL goes right, then up (below the band); LARGE goes up, then right (above the band)
    const sPath = `M${at(small.gpu).x} ${at(small.gpu).y}H${at(small.cpu).x}V${at(small.cpu).y}`;
    const lPath = `M${at(large.gpu).x} ${at(large.gpu).y}V${at(large.cpu).y}H${at(large.cpu).x}`;
    const timeLabel = (net: EnergyRow['net']): string => fig.pick('fig.energy.factor.time', compact, { x: fmt.fixed(factor(net).time, 0) });
    const energyLabel = (net: EnergyRow['net']): string => fig.pick('fig.energy.factor.energy', compact, { x: fmt.fixed(factor(net).energy, 0) });
    const lowerAt = (h: number): number => sy((wLo / 1000) * h);
    costMarks = (
      <>
        <g data-part="power-band">
          <PlotBand xs={xs} lo={xs.map((x) => (wLo / 1000) * x)} hi={xs.map((x) => (wHi / 1000) * x)} />
          <text className="ml-fig-energy__bandlabel" x={sx(xTop) - 8} y={lowerAt(xTop) + 16} textAnchor="end">
            {t('fig.energy.band', { lo: fmt.fixed(wLo, 0), hi: fmt.fixed(wHi, 0) })}
          </text>
        </g>
        <g data-part="cpu-against-gpu">
          <path className="ml-fig-energy__path" d={sPath} />
          <path className="ml-fig-energy__path" d={lPath} />
          <text className="ml-fig-energy__factor" x={(at(small.gpu).x + at(small.cpu).x) / 2} y={at(small.gpu).y + 16} textAnchor="middle">
            {timeLabel('SMALL')}
          </text>
          <text className="ml-fig-energy__factor" x={at(small.cpu).x + 7} y={(at(small.gpu).y + at(small.cpu).y) / 2 + 4} textAnchor="start">
            {energyLabel('SMALL')}
          </text>
          <text className="ml-fig-energy__factor" x={at(large.gpu).x - 7} y={(at(large.gpu).y + at(large.cpu).y) / 2 + 4} textAnchor="end">
            {energyLabel('LARGE')}
          </text>
          <text className="ml-fig-energy__factor" x={(at(large.gpu).x + at(large.cpu).x) / 2} y={at(large.cpu).y - 8} textAnchor="middle">
            {timeLabel('LARGE')}
          </text>
        </g>
        {data.rows.map((r) => {
          const { x, y } = at(r);
          const context = r.group === 'run';
          const hollow = r.status === 'estimate';
          const range = r.kwhRange;
          const place: Record<string, [number, number, 'start' | 'end' | 'middle']> = {
            'small-steps-gpu': [x, y + 20, 'middle'],
            'large-steps-gpu': [x + 9, y + 20, 'start'],
            'small-steps-cpu': [x - 9, y - 10, 'end'],
            'large-steps-cpu': [x - 9, y - 10, 'end'],
          };
          const p = place[r.id];
          return (
            <g key={r.id} data-row={r.id} data-context={context ? '' : undefined}>
              {range && <path className="ml-fig-plot__whisker" d={whisker(x, sy(range[0]), sy(range[1]))} />}
              <Shape net={r.net} cx={x} cy={y} hollow={hollow} r={context ? 3 : 5} />
              {p && (
                <text className="ml-fig-energy__label" data-selected={r.id === selected ? '' : undefined} x={p[0]} y={p[1]} textAnchor={p[2]}>
                  {code(r)}
                </text>
              )}
            </g>
          );
        })}
      </>
    );
  }

  // ---- ring of the selected row: at the deciding mark, or at the top of its bound line; none when the view does not draw the row ----
  let ring: { x: number; y: number } | null = null;
  const kind = markKind(sel, view);
  if (kind === 'point') ring = both ? { x: sx(sel.hours), y: sy(sel.kwh) } : { x: sx(xOf(sel, strengthView!)), y: sy(strengthY(sel, basis, data)) };
  else if (kind === 'bound') ring = { x: sx(xOf(sel, strengthView!)), y: area.y0 + 6 };

  // hit circles: the radius is half the distance to the nearest neighbour, between 8 and 22 units
  const pointHits = hit.filter((h) => markKind(rowById(data, h.id), view) !== 'bound');
  const radii = hitRadii(pointHits);
  return (
    <>
      {strengthMarks}
      {costMarks}
      {ring && <circle className="ml-fig-energy__ring" cx={ring.x} cy={ring.y} r={10} data-ring={sel.id} />}
      <g className="ml-fig-energy__hits" aria-hidden="true">
        {hit.map((h) =>
          markKind(rowById(data, h.id), view) === 'bound' ? (
            <rect key={h.id} className="ml-fig-energy__hit" x={h.x - 11} y={area.y0} width={22} height={area.y1 - area.y0} onClick={() => onSelect(h.id)} />
          ) : (
            <circle key={h.id} className="ml-fig-energy__hit" cx={h.x} cy={h.y} r={radii[pointHits.indexOf(h)]} onClick={() => onSelect(h.id)} />
          ),
        )}
      </g>
    </>
  );
}
