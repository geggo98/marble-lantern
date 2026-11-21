// V1, V2 and V3: the box-diagram figures of topic 1 (offline architecture, online architecture, training pipeline).
// One component, three scenarios: the data file, the regions and the steps differ (arch-model.ts), the controls differ a little
// (V1 has the undo variant, V2 the permit slider, V3 the CPU or GPU switch).
import { useMemo, useState } from 'react';
import {
  BoxDiagram,
  Checkbox,
  Figure,
  FigureDetail,
  Legend,
  LegendItem,
  Manipulate,
  Segmented,
  Slider,
  Stepper,
  boxDiagramLabels,
  stepperLabels,
  useStepper,
} from '../kit';
import type { Flow } from '../kit';
import { Icon } from '../kit/icons';
import { Markup } from '../kit/markup';
import { sourceUrl } from '../kit/SourceChip';
import { Chip } from '../kit';
import { hasKey } from '../i18n/catalog';
import offline from '../data/arch-offline.json';
import online from '../data/arch-online.json';
import pipeline from '../data/arch-training.json';
import limits from '../data/online-limits.json';
import trainingTime from '../data/training-time.json';
import { FigFoot, figLabel, useFigText } from './common';
import type { Catalog } from './common';
import { KINDS, OFFLINE_CANCEL_STEPS, PREFIX, STEPS, WIDE_BANDS, arrange, resolveStep, semaphoreState } from './arch-model';
import type { ArchKind, Device, RawArch } from './arch-model';

const RAW: Record<ArchKind, RawArch> = { offline: offline as RawArch, online: online as RawArch, pipeline: pipeline as RawArch };
const DATA_FILE: Record<ArchKind, string> = { offline: 'arch-offline.json', online: 'arch-online.json', pipeline: 'arch-training.json' };
const SOURCES: Record<ArchKind, readonly string[]> = {
  offline: ['web/src/engines/client.ts', 'web/src/engines/protocol.ts', 'web/src/app/session.ts'],
  online: ['app/src/main/java/sternhalma/app/Engines.java', 'app/src/main/java/sternhalma/app/GameEndpoint.java'],
  pipeline: ['selfplay/src/main/java/sternhalma/selfplay/Loop.java', 'scripts/models/pack-model.py'],
};

export interface ArchFigureProps {
  catalog: Catalog;
  kind: ArchKind;
  /** Number of the topic and of the figure in it: "Fig. 1.2". */
  number: readonly [number, number];
}

/** The row of permits of V2: a filled square per running request, a hollow square with a clock per waiting one. */
function Permits({ permits, requests, labels }: { permits: number; requests: number; labels: { title: string; slot: (n: number) => string; running: string; waiting: string; busy: string; note: React.ReactNode } }): React.JSX.Element {
  const { running, waiting } = semaphoreState(requests, permits);
  return (
    <div className="ml-fig-permits" data-running={running} data-waiting={waiting}>
      <p className="ml-fig-permits__title">{labels.title}</p>
      <ul className="ml-fig-permits__row">
        {Array.from({ length: permits }, (_, i) => (
          <li key={`p${i}`} className="ml-fig-permits__slot" data-state={i < running ? 'held' : 'free'}>
            <span className="ml-visually-hidden">{labels.slot(i + 1)}</span>
          </li>
        ))}
        {waiting > 0 && <li className="ml-fig-permits__divider" aria-hidden="true" />}
        {Array.from({ length: waiting }, (_, i) => (
          <li key={`w${i}`} className="ml-fig-permits__token" data-state="waiting">
            <Icon name="clock" />
          </li>
        ))}
      </ul>
      <p className="ml-fig-permits__counts">
        <span>{labels.running}</span>
        <span>{labels.waiting}</span>
      </p>
      {waiting > 0 && <p className="ml-fig-permits__busy">{labels.busy}</p>}
      <p className="ml-fig-permits__note">{labels.note}</p>
    </div>
  );
}

export function ArchFigure({ catalog, kind, number }: ArchFigureProps): React.JSX.Element {
  const f = useFigText(catalog);
  const { t, kit, n } = f;
  const prefix = `fig.${PREFIX[kind]}`;
  const [flow, setFlow] = useState<Flow>('both');
  const [cancel, setCancel] = useState(false);
  const [device, setDevice] = useState<Device>('cpu');
  const [requests, setRequests] = useState(1);
  const [openId, setOpenId] = useState<string | null>(null);
  const permits = limits.examplePermits;

  const arranged = useMemo(() => arrange(kind, RAW[kind], device), [kind, device]);
  const steps = kind === 'offline' && cancel ? OFFLINE_CANCEL_STEPS : STEPS[kind];
  const stepper = useStepper(steps.length);
  const { step } = stepper.state;
  const stepDef = step > 0 ? steps[step - 1] : undefined;
  const current = stepDef ? resolveStep(stepDef, arranged.data) : null;

  const boxLabels: Record<string, string> = {};
  const shortBoxLabels: Record<string, string> = {};
  for (const b of arranged.data.boxes) {
    const long = `${prefix}.node.${b.id}.label`;
    const short = `${long}.short`;
    boxLabels[b.id] = t(long);
    if (hasKey(catalog, short)) shortBoxLabels[b.id] = t(short);
  }
  const edgeLabels: Record<number, string> = {};
  arranged.data.edges.forEach((e, i) => {
    edgeLabels[i] = t(`${prefix}.edge.${e.from}-${e.to}`);
  });
  const groupIds = [...new Set(arranged.data.boxes.map((b) => b.runsIn ?? ''))];
  const groupLabels = Object.fromEntries(groupIds.map((g) => [g, t(`${prefix}.group.${g}`)]));

  const idleText = t(`${prefix}.idle`);
  const stepKey = kind === 'offline' && cancel ? `${prefix}.cancel.step.${step}` : `${prefix}.step.${step}`;
  const status = { ...(step > 0 ? { step: f.kit('kit.stepper.step', { k: step, n: steps.length }) } : {}), text: step > 0 ? f.plain(stepKey) : idleText.replace(/`/g, '') };

  const open = openId === null ? null : arranged.boxes.get(openId);
  const kindOf = (id: string): 'component' | 'store' | 'external' => KINDS[kind][id] ?? 'component';
  const detailId = `${prefix}-detail`;
  const sourcePaths = (open?.source?.paths ?? []).filter(() => open?.source?.generated !== true);

  const panel = (
    <Manipulate>
      <Segmented
        label={kit('kit.flow.label')}
        value={flow}
        onChange={setFlow}
        options={[
          { value: 'data', label: kit('kit.flow.data') },
          { value: 'control', label: kit('kit.flow.control') },
          { value: 'both', label: kit('kit.flow.both') },
        ]}
      />
      {kind === 'pipeline' && (
        <Segmented
          label={t('fig.pipeline.device.label')}
          value={device}
          onChange={setDevice}
          options={[
            { value: 'cpu', label: t('fig.pipeline.device.cpu') },
            { value: 'gpu', label: t('fig.pipeline.device.gpu') },
          ]}
        />
      )}
      {kind === 'offline' && (
        <div className="ml-fig-ctl ml-fig-ctl--wide">
          <div className="ml-fig-ctl__input">
            <Checkbox
              label={t('fig.offline.cancel.toggle')}
              checked={cancel}
              onChange={(value) => {
                setCancel(value);
                stepper.dispatch({ type: 'reset' });
              }}
            />
          </div>
        </div>
      )}
      {kind === 'online' && (
        <Slider
          label={t('fig.online.slider.requests')}
          min={1}
          max={2 * permits}
          value={requests}
          onChange={setRequests}
          valueText={t('fig.online.slider.valuetext', { k: requests, p: permits })}
          out={n(requests)}
        />
      )}
      <Stepper state={stepper.state} dispatch={stepper.dispatch} labels={stepperLabels(f.kitT, t(`${prefix}.stepper`))} />
    </Manipulate>
  );

  const legendMark = (cls: string, dash?: string): React.JSX.Element => <line className={cls} x1={1} x2={31} y1={8} y2={8} strokeDasharray={dash} />;
  const legendData = hasKey(catalog, `${prefix}.legend.data`) ? t(`${prefix}.legend.data`) : kit('kit.edge.data');
  const legendControl = hasKey(catalog, `${prefix}.legend.control`) ? t(`${prefix}.legend.control`) : kit('kit.edge.control');

  const time = device === 'cpu' ? `${n(trainingTime.cpuMinutes)} min` : `${n(trainingTime.gpuSeconds)} s`;

  const detailNode =
    open ? (
          <FigureDetail
            id={detailId}
            kicker={kit(`kit.node.kind.${kindOf(openId!)}`)}
            title={t(`${prefix}.node.${openId}.label`)}
            closeLabel={kit('kit.detail.close')}
            onClose={() => setOpenId(null)}
            chips={sourcePaths.map((p) => (
              <Chip key={p} href={sourceUrl(p)} prefix={kit('kit.chip.src')}>
                {p}
              </Chip>
            ))}
          >
            <Markup text={t(`${prefix}.node.${openId}.detail`)} />
            {kind === 'pipeline' && openId === 'runDirectory' && (
              <ul className="ml-fig-files">
                {pipeline.files.map((file) => (
                  <li key={file.id}>
                    <code>{file.name}</code> <span>{t(`fig.pipeline.file.${file.id}`)}</span>
                  </li>
                ))}
              </ul>
            )}
          </FigureDetail>
        ) : null;

  return (
    <Figure
      id={`fig-${PREFIX[kind]}`}
      labelWord={figLabel(kit, number[0], number[1])}
      title={t(`${prefix}.title`)}
      panel={panel}
      status={status}
      caption={<Markup text={t(`${prefix}.caption`)} />}
      foot={<FigFoot kit={kit} paths={SOURCES[kind]} data={DATA_FILE[kind]} />}
    >
      <div className="ml-fig-arch">
        <BoxDiagram
          data={arranged.data}
          labels={boxDiagramLabels(f.kitT, f.plain(`${prefix}.title`), t(`${prefix}.keylist`))}
          groupLabels={groupLabels}
          boxLabels={boxLabels}
          shortBoxLabels={shortBoxLabels}
          bands={WIDE_BANDS[kind]}
          detail={detailNode}
          edgeLabels={edgeLabels}
          kinds={Object.fromEntries(arranged.data.boxes.map((b) => [b.id, kindOf(b.id)]))}
          flow={flow}
          {...(current ? { activeBoxes: current.boxes, activeEdges: current.edges } : {})}
          openId={openId}
          onOpen={setOpenId}
          detailId={detailId}
        />
      </div>
      <Legend>
        <LegendItem mark={legendMark('ml-fig-edge')}>{legendData}</LegendItem>
        <LegendItem mark={legendMark('ml-fig-edge ml-fig-edge--control', '6 4')}>{legendControl}</LegendItem>
        <LegendItem mark={<line className="ml-fig-edge" data-state="active" x1={1} x2={31} y1={8} y2={8} />}>{kit('kit.legend.active')}</LegendItem>
      </Legend>
      {kind === 'online' && (
        <Permits
          permits={permits}
          requests={requests}
          labels={{
            title: t('fig.online.semaphore.title'),
            slot: (slot) => t('fig.online.semaphore.slot', { n: slot }),
            running: t('fig.online.semaphore.running', { n: semaphoreState(requests, permits).running }),
            waiting: t('fig.online.semaphore.waiting', { n: semaphoreState(requests, permits).waiting }),
            busy: f.plain('fig.online.semaphore.busy'),
            note: <Markup text={t('fig.online.semaphore.note')} />,
          }}
        />
      )}
      {kind === 'pipeline' && (
        <div className="ml-fig-timeline">
          <p className="ml-fig-timeline__value">{t('fig.pipeline.time.readout', { time })}</p>
          <p className="ml-fig-timeline__note">
            <Markup text={t('fig.pipeline.time.note')} />
          </p>
        </div>
      )}
    </Figure>
  );
}
