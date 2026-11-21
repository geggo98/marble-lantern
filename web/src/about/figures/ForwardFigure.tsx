// V9b: one forward pass on a real position, layer by layer (Fig. 3.2), and the backends of the forward pass (Fig. 3.3).
// Layout notes: web/design/about/about-notes.md, "V9b". Data: forward-<position>.json (3 files) and backends.json.
import { useEffect, useId, useState } from 'react';
import { cellName, isStep, notation } from '../../ui/board-model';
import { useT } from '../../ui/texts';
import { Figure, HeatBoard, Manipulate, MiniBoard, Segmented, Select, Stepper, stepperLabels, useStepper } from '../kit';
import type { HeatCell } from '../kit';
import { formatCompact, logScale, logTicks } from '../kit/plot-scale';
import { Markup } from '../kit/markup';
import { sourceUrl } from '../kit/SourceChip';
import { inferenceCatalog } from './catalogs';
import { POSITIONS, STEP_IDS, activationCells, arrowWidth, backendPoints, forwardData, piecesOf, planeCells, topMoves } from './forward';
import type { PositionId } from './forward';
import { HeatLegend } from './HeatLegend';
import { FootChips, useCompact, useFigText } from './shared';

const moveText = (from: number, to: number): string => notation({ from, to, jump: !isStep(from, to) });

export function ForwardFigure(): React.JSX.Element {
  const fig = useFigText(inferenceCatalog);
  const { t: ui } = useT();
  const { ref, compact } = useCompact();
  const [position, setPosition] = useState<PositionId>('mid');
  const [plane, setPlane] = useState(0);
  const [priors, setPriors] = useState<'on' | 'off'>('on');
  const { state, dispatch } = useStepper(STEP_IDS.length);
  // The idle step 0 shows the input like step 1; the figure starts on step 1.
  useEffect(() => dispatch({ type: 'set', step: 1 }), [dispatch]);
  const step = Math.max(1, state.step);
  const id = STEP_IDS[step - 1] as (typeof STEP_IDS)[number];

  const d = forwardData(position);
  const withPriors = priors === 'on';
  const top = topMoves(d, withPriors);
  const pieces = piecesOf(d);
  const mover = d.meta.sideToMove;
  const values = withPriors ? d.values : d.valuesNetOnly;
  const stepName = fig.plain(`fig.forward.stepname.${id}`);

  let board: React.JSX.Element;
  const common = { title: fig.plain('fig.forward.heat.title', { layer: stepName }) };
  if (id === 'input' || (id !== 'policy' && id !== 'value')) {
    const cells: (HeatCell | null)[] = id === 'input' ? planeCells(d.planes[plane] as number[]) : activationCells(d, id);
    board = <HeatBoard {...common} desc={fig.plain('fig.forward.heat.desc', { layer: stepName })} cells={cells} />;
  } else {
    const topPrior = top[0]?.prior ?? 0;
    board = (
      <MiniBoard
        {...common}
        desc={fig.plain(`fig.forward.step.${id}`, { moves: d.meta.legalMoves, value: fig.fmt.signed(values[mover] as number, 2) })}
        pieces={pieces}
        {...(id === 'policy'
          ? { arrows: top.map((m) => ({ from: m.from, to: m.to, width: arrowWidth(m.prior, topPrior) })), fromRings: top.map((m) => m.from) }
          : {})}
      />
    );
  }

  const narration = fig.plain(`fig.forward.step.${id}`, {
    plane: fig.plain(`fig.forward.plane.${plane}`),
    moves: d.meta.legalMoves,
    value: fig.fmt.signed(values[mover] as number, 2),
  });

  const tracks = [
    { key: 0, label: fig.t('fig.forward.value.player.0'), v: values[mover] as number },
    { key: 1, label: fig.t('fig.forward.value.player.1'), v: values[1 - mover] as number },
  ];

  return (
    <Figure
      id="fig-forward"
      labelWord={`${fig.kit('kit.fig.label')} 3.2`}
      title={fig.plain('fig.forward.title')}
      split
      panel={
        <Manipulate>
          <Select
            label={fig.t('fig.forward.position')}
            value={position}
            onChange={(v) => setPosition(v as PositionId)}
            options={POSITIONS.map((p) => ({ value: p, label: fig.plain(`fig.forward.position.${p}`) }))}
          />
          <Stepper state={state} dispatch={dispatch} labels={stepperLabels(fig.kit, fig.t('fig.forward.layer'))} />
          <Select
            label={fig.t('fig.forward.plane')}
            value={String(plane)}
            disabled={id !== 'input'}
            onChange={(v) => setPlane(Number(v))}
            options={d.meta.planeNames.map((_, i) => ({ value: String(i), label: fig.plain(`fig.forward.plane.${i}`) }))}
          />
          <Segmented
            label={fig.t('fig.forward.priors.label')}
            value={priors}
            onChange={setPriors}
            options={[
              { value: 'on', label: fig.t('fig.forward.priors.on') },
              { value: 'off', label: fig.t('fig.forward.priors.off') },
            ]}
          />
        </Manipulate>
      }
      status={{ step: fig.kit('kit.stepper.step', { k: step, n: STEP_IDS.length }), text: narration }}
      caption={<Markup text={fig.t('fig.forward.caption')} />}
      foot={<FootChips fig={fig} sources={['nn/src/main/java/sternhalma/nn/Network.java', 'nn/src/main/java/sternhalma/nn/ResidualPriors.java']} data={[`forward-${position}.json`]} />}
    >
      <div ref={ref} className="ml-fig-forward__board">
        {board}
        {id !== 'policy' && id !== 'value' && <HeatLegend label={fig.kit('kit.legend.heat')} low={fig.kit('kit.legend.low')} high={fig.kit('kit.legend.high')} />}
        <p className="ml-fig-note">{fig.kit('kit.board.orientation', { top: ui('side_a'), bottom: ui('side_b') })}</p>
      </div>
      <div className="ml-fig-forward__side">
        <h4 className="ml-fig-subtitle">{fig.t('fig.forward.policy.title')}</h4>
        <ol className="ml-fig-policy">
          {top.map((m, i) => (
            <li key={i}>
              <span className="ml-visually-hidden">{fig.plain('fig.forward.policy.row', { from: cellName(m.from), to: cellName(m.to), prior: fig.fmt.fixed(m.prior, 3) })}</span>
              <span className="ml-fig-policy__move" aria-hidden="true">{moveText(m.from, m.to)}</span>
              <span className="ml-fig-policy__bar" aria-hidden="true">
                <span style={{ width: `${Math.max(1, m.prior * 100)}%` }} />
              </span>
              <span className="ml-fig-policy__num" aria-hidden="true">{fig.fmt.fixed(m.prior, 3)}</span>
            </li>
          ))}
        </ol>
        <h4 className="ml-fig-subtitle">{fig.t('fig.forward.value.title')}</h4>
        <ul className="ml-fig-value">
          {tracks.map((tr) => (
            <li key={tr.key}>
              <span className="ml-fig-value__label">{tr.label}</span>
              <span className="ml-fig-value__track" aria-hidden="true">
                <span className="ml-fig-value__zero" />
                <span
                  className="ml-fig-value__bar"
                  style={{ left: `${(50 + Math.min(0, tr.v) * 50).toFixed(2)}%`, width: `${(Math.abs(tr.v) * 50).toFixed(2)}%` }}
                />
              </span>
              <span className="ml-fig-value__num">{fig.fmt.signed(tr.v, 2)}</span>
            </li>
          ))}
        </ul>
        <p className="ml-fig-note">{fig.t('fig.forward.value.axis')}</p>
      </div>
    </Figure>
  );
}

/** Fig. 3.3: dots with leaders on a log axis, one row per backend, names above the dots (the plot primitive has vertical categories only). */
export function BackendsFigure(): React.JSX.Element {
  const fig = useFigText(inferenceCatalog);
  const uid = useId();
  const { ref, compact } = useCompact();
  const points = backendPoints();
  const W = compact ? 260 : 640;
  const x0 = 12;
  const x1 = W - 12;
  const rowH = 44;
  const top = 10;
  const H = top + points.length * rowH + 46;
  const sx = logScale([50, 100000], [x0, x1]);
  const ticks = logTicks(50, 100000, [100, 1000, 10000, 100000]);
  const yAxis = top + points.length * rowH;
  const label = (id: string, batch: number): string =>
    `${fig.plain(`fig.forward.backend.${id}`)}${batch > 1 ? ` ${fig.plain('fig.forward.backends.batch', { n: batch })}` : ''}`;

  return (
    <Figure
      id="fig-backends"
      labelWord={`${fig.kit('kit.fig.label')} 3.4`}
      title={fig.plain('fig.forward.backends.title')}
      caption={<Markup text={fig.t('fig.forward.backends.caption')} />}
      foot={
        <FootChips
          fig={fig}
          sources={[...new Set(points.map((p) => p.doc))]}
          data={['backends.json']}
        />
      }
    >
      <div ref={ref}>
        <svg className="ml-fig-plot ml-fig-backends" viewBox={`0 0 ${W} ${H}`} role="img" aria-labelledby={`${uid}-t ${uid}-d`}>
          <title id={`${uid}-t`}>{fig.plain('fig.forward.backends.title')}</title>
          <desc id={`${uid}-d`}>{fig.plain('fig.forward.backends.desc')}</desc>
          <rect className="ml-fig-plot__frame" x={x0} y={top} width={x1 - x0} height={points.length * rowH} />
          {ticks.minor.map((v) => (
            <line key={`m${v}`} className="ml-fig-plot__tick ml-fig-plot__tick--minor" x1={sx(v)} x2={sx(v)} y1={yAxis} y2={yAxis + 3} />
          ))}
          {ticks.major.map((v) => (
            <g key={v}>
              <line className="ml-fig-plot__tick" x1={sx(v)} x2={sx(v)} y1={yAxis} y2={yAxis + 6} />
              <text x={sx(v)} y={yAxis + 19} textAnchor="middle">{formatCompact(v)}</text>
            </g>
          ))}
          <text className="ml-fig-plot__axislabel" x={W / 2} y={H - 6} textAnchor="middle">{fig.plain('fig.forward.backends.axis')}</text>
          {points.map((p, i) => {
            const y = top + i * rowH + 32;
            const x = sx(p.value);
            const right = x > x1 - 70;
            return (
              <g key={p.id} data-backend={p.id}>
                <text x={x0 + 6} y={top + i * rowH + 15}>{label(p.id, p.batch)}</text>
                <line className="ml-fig-plot__leader" x1={x0} x2={x} y1={y} y2={y} />
                {p.high !== undefined && (
                  <path className="ml-fig-plot__whisker" d={`M${x} ${y}H${sx(p.high)}M${sx(p.high)} ${y - 5}V${y + 5}`} />
                )}
                <circle className="ml-fig-plot__pt--1" cx={x} cy={y} r={4.5} />
                <text className={p.id === 'webgpu16' ? 'ml-fig-plot__best' : 'ml-fig-plot__value'} x={right ? x - 9 : (p.high !== undefined ? sx(p.high) : x) + 9} y={y + 4} textAnchor={right ? 'end' : 'start'}>
                  {fig.fmt.n(Math.round(p.value))}
                </text>
              </g>
            );
          })}
        </svg>
      </div>
      <table className="ml-fig-backlist">
        <caption className="ml-visually-hidden">{fig.plain('fig.forward.backends.title')}</caption>
        <tbody>
          {points.map((p) => (
            <tr key={p.id}>
              <th scope="row">{label(p.id, p.batch)}</th>
              <td className="ml-fig-backlist__num">{fig.fmt.n(Math.round(p.value))}</td>
              <td className="ml-fig-backlist__src">
                <a href={sourceUrl(p.doc)} rel="noreferrer">
                  {p.doc}
                </a>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      <p className="ml-fig-note"><Markup text={fig.t('fig.forward.backends.note')} /></p>
    </Figure>
  );
}
