// V9a: the layers of the network (a stack of buttons with shape and parameter count) and what one cell gathers for a hex convolution
// (a board with a cell picker). Fig. 3.1. Layout notes: web/design/about/about-notes.md, "V9a".
import { createElement, useId, useRef, useState } from 'react';
import { CELLS } from '../../board/cells';
import { cellName } from '../../ui/board-model';
import { Chip, Figure, FigureDetail, HeatBoard, Legend, LegendItem, Manipulate, Readout, Segmented, Select } from '../kit';
import type { HeatCell } from '../kit';
import { Markup } from '../kit/markup';
import { sourceUrl } from '../kit/SourceChip';
import { inferenceCatalog } from './catalogs';
import { BLOCK_IDS, LAYER_IDS, PRIORS, layerInfo, modelRow, neighboursOf } from './netModel';
import type { LayerId, ModelId } from './netModel';
import { FootChips, useCompact, useFigText } from './shared';

const SOURCE: Record<LayerId, string> = {
  input: 'nn/src/main/java/sternhalma/nn/Encoder.java',
  conv_in: 'nn/src/main/java/sternhalma/nn/Network.java',
  block0: 'nn/src/main/java/sternhalma/nn/Network.java',
  block1: 'nn/src/main/java/sternhalma/nn/Network.java',
  block2: 'nn/src/main/java/sternhalma/nn/Network.java',
  block3: 'nn/src/main/java/sternhalma/nn/Network.java',
  head_policy: 'nn/src/main/java/sternhalma/nn/Network.java',
  head_value: 'nn/src/main/java/sternhalma/nn/Network.java',
  priors: 'nn/src/main/java/sternhalma/nn/ResidualPriors.java',
};

const m = (tag: string, props: object | null, ...kids: React.ReactNode[]): React.ReactElement => createElement(tag, props, ...kids);

/** The formula of the gather, as MathML in three rows (notation, not copy): out[c][o] = b[o] + sum over k and ch of W[o][k*Cin+ch] * in[gather[c][k]][ch]. */
function GatherFormula(): React.JSX.Element {
  const i = (t: string): React.ReactElement => m('mi', null, t);
  const o = (t: string): React.ReactElement => m('mo', null, t);
  const row1 = m('mtr', null, m('mtd', null, m('mrow', null, i('out'), o('['), i('c'), o(']'), o('['), i('o'), o(']'))), m('mtd', null, m('mrow', null, o('='), i('b'), o('['), i('o'), o(']'))));
  const gather = m('mrow', null, i('in'), o('['), i('gather'), o('['), i('c'), o(']'), o('['), i('k'), o(']'), o(']'), o('['), i('ch'), o(']'));
  const weight = m('mrow', null, i('W'), o('['), i('o'), o(']'), o('['), i('k'), o('\u00b7'), m('msub', null, i('C'), i('in')), o('+'), i('ch'), o(']'));
  const row2 = m('mtr', null, m('mtd', null), m('mtd', null, m('mrow', null, o('+'), m('munder', null, o('\u2211'), m('mrow', null, i('k'), o(','), i('ch'))), weight)));
  const row3 = m('mtr', null, m('mtd', null), m('mtd', null, m('mrow', null, o('\u00b7'), gather)));
  return <div className="ml-fig-math">{m('math', { display: 'block' }, m('mtable', { columnalign: 'right left', columnspacing: '0.3em' }, row1, row2, row3))}</div>;
}

export function NetFigure(): React.JSX.Element {
  const fig = useFigText(inferenceCatalog);
  const { ref, compact } = useCompact();
  const [model, setModel] = useState<ModelId>('expert');
  const [selected, setSelected] = useState<LayerId | null>(null);
  const [cell, setCell] = useState(60);
  const [blocksOpen, setBlocksOpen] = useState(false);
  const buttons = useRef<Partial<Record<LayerId, HTMLButtonElement | null>>>({});
  const detailId = useId();
  const blocksId = useId();

  const row = modelRow(model);
  const info = (id: LayerId) => layerInfo(model, id);
  const blocksParams = BLOCK_IDS.reduce((sum, id) => sum + info(id).params, 0);
  const foldedBlocks = compact && !blocksOpen;
  const visible = selected !== null && !(foldedBlocks && BLOCK_IDS.includes(selected)) ? selected : null;

  const neighbours = neighboursOf(cell);
  const cells: HeatCell[] = [];
  neighbours.forEach((c) => (cells[c] = { level: 2 }));
  cells[cell] = { level: 5 };

  const close = (): void => {
    const was = selected;
    setSelected(null);
    queueMicrotask(() => was && buttons.current[was]?.focus());
  };

  const layerButton = (id: LayerId): React.JSX.Element => {
    const i = info(id);
    return (
      <li key={id} className="ml-fig-stack__item">
        <button
          type="button"
          ref={(el) => {
            buttons.current[id] = el;
          }}
          className="ml-fig-stack__layer"
          data-layer={id}
          aria-expanded={visible === id}
          aria-controls={detailId}
          onClick={() => setSelected(visible === id ? null : id)}
        >
          <span className="ml-fig-stack__name">{fig.t(`fig.net.layer.${id}.label`)}</span>
          <span className="ml-fig-stack__shape">
            <span className="ml-visually-hidden">{fig.t('fig.net.shape', { shape: '' })}</span>
            {i.shape}
          </span>
          <span className="ml-fig-stack__params">{fig.t('fig.net.params', { n: fig.fmt.n(i.params) })}</span>
        </button>
      </li>
    );
  };

  const detailText = (id: LayerId): string =>
    fig.t(`fig.net.layer.${id}.detail`, { planes: row.inputPlanes, players: row.players });

  return (
    <Figure
      id="fig-net"
      labelWord={`${fig.kit('kit.fig.label')} 3.1`}
      title={fig.plain('fig.net.title')}
      split
      panel={
        <Manipulate>
          <Segmented
            label={fig.t('fig.net.model.label')}
            value={model}
            onChange={(v) => setModel(v)}
            options={[
              { value: 'expert', label: fig.pick('fig.net.model.expert', compact) },
              { value: 'shared', label: fig.pick('fig.net.model.shared', compact) },
            ]}
          />
        </Manipulate>
      }
      status={{ text: fig.plain('fig.net.gather.readout', { cell: cellName(cell), neighbours: neighbours.length }) }}
      detail={
        visible && (
          <FigureDetail
            id={detailId}
            title={fig.t(`fig.net.layer.${visible}.label`)}
            kicker={`${fig.t('fig.net.shape', { shape: info(visible).shape })} · ${fig.t('fig.net.params', { n: fig.fmt.n(info(visible).params) })}`}
            closeLabel={fig.kit('kit.detail.close')}
            onClose={close}
            chips={
              <Chip prefix={fig.kit('kit.chip.src')} href={sourceUrl(SOURCE[visible])}>
                {SOURCE[visible]}
              </Chip>
            }
          >
            <p>
              <Markup text={detailText(visible)} />
            </p>
            {visible === 'priors' && (
              <ul>
                <li>{fig.t('fig.net.prior.beta', { value: fig.fmt.fixed(PRIORS.beta, 4) })}</li>
                <li>{fig.t('fig.net.prior.k', { value: fig.fmt.fixed(PRIORS.k, 3) })}</li>
              </ul>
            )}
          </FigureDetail>
        )
      }
      caption={<Markup text={fig.t('fig.net.caption')} />}
      foot={<FootChips fig={fig} sources={[...new Set(LAYER_IDS.map((id) => SOURCE[id]))]} data={['net-shape.json', 'forward-mid.json']} />}
    >
      <div ref={ref}>
        <ol className="ml-fig-stack" aria-label={fig.t('fig.net.stack.label')}>
          {layerButton('input')}
          {layerButton('conv_in')}
          <li className="ml-fig-stack__item ml-fig-stack__blocks-item">
            {compact && (
              <button
                type="button"
                className="ml-fig-stack__layer ml-fig-stack__fold"
                aria-expanded={blocksOpen}
                aria-controls={blocksId}
                onClick={() => setBlocksOpen(!blocksOpen)}
              >
                <span className="ml-fig-stack__name">{fig.t('fig.net.blocks', { n: row.blocks })}</span>
                <span className="ml-fig-stack__shape">{info('block0').shape}</span>
                <span className="ml-fig-stack__params">{fig.t('fig.net.params', { n: fig.fmt.n(blocksParams) })}</span>
              </button>
            )}
            {!foldedBlocks && (
              <div id={blocksId} className="ml-fig-stack__blocks">
                <ol className="ml-fig-stack__inner">{BLOCK_IDS.map(layerButton)}</ol>
                <p className="ml-fig-stack__skip">{fig.t('fig.net.skip')}</p>
              </div>
            )}
          </li>
          <li className="ml-fig-stack__item ml-fig-stack__heads-item">
            <ol className="ml-fig-stack__heads">
              {layerButton('head_policy')}
              {layerButton('head_value')}
            </ol>
          </li>
          {layerButton('priors')}
          <li className="ml-fig-stack__total">
            <span>{fig.t('fig.net.total')}</span>
            <span>{fig.t('fig.net.params', { n: fig.fmt.n(row.params) })}</span>
          </li>
        </ol>
      </div>
      <div className="ml-fig-gather">
        <h4 className="ml-fig-subtitle">{fig.t('fig.net.gather.title')}</h4>
        <HeatBoard
          title={fig.plain('fig.net.gather.title')}
          desc={fig.plain('fig.net.gather.desc')}
          cells={cells}
          rings={neighbours}
          picker={{
            value: cell,
            onChange: setCell,
            label: fig.t('fig.net.gather.picker'),
            cellLabel: (c) =>
              fig.kit('kit.board.cell', { cell: cellName(c), content: `${neighboursOf(c).length} ${fig.t('fig.net.gather.neighbours')}` }),
          }}
        />
        {/* the board cells are about 21 px wide on a phone; this select reaches every cell with a 44 px control */}
        <Select
          label={fig.t('fig.net.gather.picker')}
          value={String(cell)}
          onChange={(v) => setCell(Number(v))}
          options={CELLS.map((c) => ({ value: String(c.id), label: cellName(c.id) }))}
        />
        <Legend>
          <LegendItem mark={<circle cx={16} cy={8} r={6} className="ml-fig-heat__v--5" />}>{fig.t('fig.net.gather.cell')}</LegendItem>
          <LegendItem mark={<circle cx={16} cy={8} r={6.5} className="ml-fig-board__nb" />}>{fig.t('fig.net.gather.neighbours')}</LegendItem>
        </Legend>
        <Readout
          cells={[
            { label: fig.t('fig.net.gather.cell'), value: cellName(cell) },
            { label: fig.t('fig.net.gather.neighbours'), value: neighbours.length },
          ]}
        />
        {neighbours.length < 6 && (
          <p className="ml-fig-note">{`${fig.t('fig.net.gather.padding')} (×${6 - neighbours.length})`}</p>
        )}
        <GatherFormula />
      </div>
    </Figure>
  );
}
