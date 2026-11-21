# Catalog keys that the Info-part design needs in addition to the plan

**TL;DR:** This file lists every i18n key that the design of the Info part uses and that the text packages of the plan do not list.
There are two kinds: about 60 shared keys for the figure kit and the page chrome (prefix `kit.`), and additions per figure V1 to V14 (prefix `fig.<figure-id>.`).
The text writer provides them in German and English; the designer wrote no text.

Date: 2026-11-21. Author: the design agent (Claude Fable 5.2), design task D1.

The plan is the approved plan of the "Info part" workflow. It is not in the repository yet; during the workflow it is the file `info/plan.md` in the workflow's scratch directory. Its section 3 holds one brief per figure with a list "Label keys", and its section 4 holds the text packages T0 to T8. "Listed" below means: named in one of those two places.

The mock `about-mockup.html` in this directory shows a key as text in square brackets, for example `[kit.toc.title]`.

## Conventions

- **Placeholders** are written `{name}` inside a text, as in the plan.
- **Accessible name** means a text that only assistive technology reads (`aria-label`, `<title>`, visually hidden text). It still needs both languages.
- **Where the keys live:** the plan foresees one catalog `figures.json` per language for labels shared across topics. The `kit.*` keys belong there. The `fig.*` keys belong in the topic catalog of their figure.
- **Short variants:** a label that does not fit at 320 px gets a second key with the suffix `.short` (plan, risk 5). None is requested yet; the implementers request them when the German text exists.

## Hub (main catalogs, package T0)

The plan lists the group `about_learn_*` without names. The mock uses these names:

| Key | Use |
|---|---|
| `about_learn_kicker` | small label above the section title |
| `about_learn_title` | section title "How it works" |
| `about_learn_<topic>_kicker` | small label of a card; `<topic>` is `architecture`, `classical`, `inference`, `training`, `tuning`, `glossary` |
| `about_learn_<topic>_title` | card title; also used by the previous/next links of a topic page |
| `about_learn_<topic>_teaser` | one sentence, at most 20 words |
| `about_learn_minutes` | reading time, with `{min}` |
| `about_learn_terms` | size of the glossary, with `{n}` |

## Shared keys of the kit and the page chrome (`kit.*`)

### Topic page

| Key | Use |
|---|---|
| `kit.crumb.back` | breadcrumb link back to the hub |
| `kit.topic.kicker` | label before "2 / 5" above the title |
| `kit.toc.title` | heading of the "On this page" list |
| `kit.sources.label` | label before the source chips of a section |
| `kit.reading.title` | heading "Further reading" |
| `kit.reading.level.intro`, `kit.reading.level.advanced` | level tag of a reading entry |
| `kit.reading.paywalled` | tag of an entry that may need a subscription |
| `kit.pager.label` | accessible name of the previous/next navigation |
| `kit.pager.prev`, `kit.pager.next` | small labels of the two links |
| `kit.chunk.loading` | text while a topic loads |
| `kit.chunk.error.title`, `kit.chunk.error.text`, `kit.chunk.retry` | alert when a topic cannot load, and its button |

### Glossary term, chips

| Key | Use |
|---|---|
| `kit.term.more` | link "more in the glossary" in the popover |
| `kit.term.close` | accessible name of the close button of the popover |
| `kit.chip.src` | accessible prefix of a source chip, read before the path |
| `kit.chip.data` | accessible prefix of a data chip, read before the file name |

### Figure frame and controls

| Key | Use |
|---|---|
| `kit.fig.label` | the word before the figure number ("Fig." in English) |
| `kit.stepper.reset`, `kit.stepper.back`, `kit.stepper.play`, `kit.stepper.pause`, `kit.stepper.forward` | accessible names of the stepper buttons |
| `kit.stepper.position` | accessible prefix of the position "3 / 6" |
| `kit.stepper.step` | start of each narration in the live region, with `{k}` and `{n}` |
| `kit.presets.label` | label of a row of preset buttons |
| `kit.flow.label` | label of the switch data flow / control flow / both |
| `kit.flow.data`, `kit.flow.control`, `kit.flow.both` | options of that switch in V2, V3, V11 and V12 (V1 has its own keys in the plan) |
| `kit.mode.label` | label of a switch between two views of one figure |
| `kit.detail.close` | accessible name of the close button of a detail panel |
| `kit.node.kind.component`, `kit.node.kind.store`, `kit.node.kind.external` | small label in the detail panel that names the kind of box |
| `kit.disclosure.show`, `kit.disclosure.hide` | generic "show more" and "show less" of a disclosure inside a figure |

### Legends

| Key | Use |
|---|---|
| `kit.edge.data`, `kit.edge.control` | accessible prefix of an entry in the numbered edge list, and legend entries of V2, V3, V11, V12 |
| `kit.legend.active` | legend entry of the highlighted step |
| `kit.legend.cursor` | legend entry of the marker that follows a slider |
| `kit.legend.interval` | legend entry of dot and whisker ("95 % interval") |
| `kit.legend.heat` | legend of the sequential heat ramp |
| `kit.legend.low`, `kit.legend.high` | ends of the sequential ramp |
| `kit.legend.signed` | legend of the signed ramp |
| `kit.legend.negative`, `kit.legend.zero`, `kit.legend.positive` | parts of the signed ramp |
| `kit.legend.visited`, `kit.legend.activenode`, `kit.legend.skipped`, `kit.legend.todo` | tree cells: searched, current, cut off, not reached yet |
| `kit.legend.jump`, `kit.legend.step` | meaning of `»` and `›` in a move |
| `kit.board.orientation` | sentence that states which seat is at the top, with `{top}` and `{bottom}` |
| `kit.board.cell` | accessible name of a focusable board cell, with `{cell}` and `{content}` |

## Additions per figure

Every figure also needs `fig.<id>.title`, `.desc` and `.caption`; the plan lists these.

### V1 Offline architecture (`fig.offline.*`)

| Key | Use |
|---|---|
| `.group.main`, `.group.workers`, `.group.sw`, `.group.host` | labels of the four regions |
| `.stepper` | label of the stepper "one engine move" |
| `.idle` | narration before the first step |
| `.keylist` | accessible name of the numbered edge list of the compact layout |

### V2 Online architecture (`fig.online.*`)

| Key | Use |
|---|---|
| `.group.browser`, `.group.server` | labels of the two halves |
| `.stepper` | label of the stepper "one server move" |
| `.idle` | narration before the first step |
| `.semaphore.title` | heading of the permit row |
| `.semaphore.slot` | accessible name of one permit, with `{n}` |
| `.semaphore.busy` | label of the outcome after the wait time |
| `.keylist` | accessible name of the numbered edge list |

### V3 Training pipeline (`fig.pipeline.*`)

| Key | Use |
|---|---|
| `.group.jobs`, `.group.run`, `.group.ship` | labels of the regions: JVM jobs, the run directory, the packing exit |
| `.edge.<id>` | edge labels, about 12; the plan lists none for this figure |
| `.stepper` | label of the stepper "one generation" |
| `.idle` | narration before the first step |
| `.device.label` | label of the CPU / GPU switch |
| `.time.readout` | training time per generation, with `{time}` |
| `.keylist` | accessible name of the numbered edge list |

### V4 Source tree explorer (`fig.tree.*`)

| Key | Use |
|---|---|
| `.tree.label` | accessible name of the tree |
| `.filter.label` | label of the filter |
| `.filter.all` | option "everything" |
| `.deps.title` | heading of the dependency picture |
| `.deps.none` | text for a module without dependencies |
| `.deps.usedby` | label of the reverse list in the compact layout |

### V5 Alpha-beta tree (`fig.ab.*`)

| Key | Use |
|---|---|
| `.stepper` | label of the stepper |
| `.counter.label` | label of the cell "nodes visited" (the plan's `.counter` is its value) |
| `.narration.idle` | narration before the first step |
| `.board.title`, `.board.desc` | accessible name and description of the mini board |
| `.root` | accessible name of the root bar |

### V6 Evaluation features (`fig.eval.*`)

| Key | Use |
|---|---|
| `.board.title`, `.board.desc` | accessible name and description of the board |
| `.table.caption` | caption of the feature table |
| `.col.feature` | first column head |
| `.select` | accessible name of the button that selects a feature, with `{feature}` |
| `.slider.weight` | label of the weight slider, with `{feature}` |
| `.slider.valuetext` | spoken value of the weight slider, with `{weight}` and `{learned}` |
| `.sigmoid.title`, `.sigmoid.desc` | accessible name and description of the sigmoid plot |
| `.sigmoid.show` | disclosure "show plot" of the compact layout |
| `.overlay.none` | text when no feature is selected |

### V7 Node budget and depth (`fig.budget.*`)

| Key | Use |
|---|---|
| `.slider` | label of the slider |
| `.slider.valuetext` | spoken value, with `{nodes}` and `{depth}` |

### V8 Race table, optional (`fig.race.*`)

| Key | Use |
|---|---|
| `.stepper` | label of the stepper |
| `.board.title`, `.board.desc` | accessible name and description of the board |
| `.table.caption` | caption of the move table |
| `.chosen` | mark of the chosen move |

### V9a Network architecture and hex convolution (`fig.net.*`)

| Key | Use |
|---|---|
| `.stack.label` | accessible name of the layer stack |
| `.model.label` | label of the model switch |
| `.blocks` | label of the folded group of residual blocks, with `{n}` |
| `.gather.cell`, `.gather.neighbours` | labels of the two readout cells under the board |
| `.gather.picker` | accessible name of the cell picker |
| `.gather.padding` | label of the padding entry that stands for off-board neighbours |

### V9b Forward pass (`fig.forward.*`)

| Key | Use |
|---|---|
| `.position` | label of the position select |
| `.layer` | label of the layer stepper |
| `.plane` | label of the plane select |
| `.priors.label` | label of the switch for the residual priors |
| `.heat.title`, `.heat.desc` | accessible name and description of the heat map, with `{layer}` |
| `.policy.row` | accessible text of one policy row, with `{from}`, `{to}` and `{prior}` |
| `.value.axis` | axis label of the value bars |
| `.backends.desc`, `.backends.caption` | description and caption of the backend chart |
| `.backends.batch` | suffix for a bar measured at another batch size, with `{n}` |

The mock frame K5 also shows `fig.forward.view`, `.view.position`, `.view.activation` and `.view.signed`. They only label the specimen; the product does not need them.

### V10 MCTS (`fig.mcts.*`)

| Key | Use |
|---|---|
| `.stepper` | label of the simulation stepper |
| `.substep.label` | label of the sub-step switch |
| `.tree.title`, `.tree.desc` | accessible name and description of the tree |
| `.table.caption` | caption of the root move table |
| `.table.showall` | disclosure "show all moves" of the compact layout |
| `.slider.valuetext` | spoken value of the `c` slider, with `{c}` |
| `.leaf.net`, `.leaf.terminal`, `.leaf.race` | kind of the evaluated leaf |
| `.inset.desc`, `.inset.caption` | description and caption of the inset chart |

### V11 Self-play loop (`fig.loop.*`)

| Key | Use |
|---|---|
| `.cycle.label` | accessible name of the cycle diagram |
| `.timeline.title`, `.timeline.desc` | accessible name and description of the gate timeline |
| `.timeline.axis.x`, `.timeline.axis.y` | axis labels of the timeline |
| `.gate.threshold` | label of the line at 0.5 |
| `.slider.valuetext` | spoken value of the generation slider, with `{gen}`, `{score}` and the decision |
| `.ladder.desc` | description of the ladder chart |

### V12 Virtual threads (`fig.pooled.*`)

| Key | Use |
|---|---|
| `.stepper` | label of the stepper |
| `.mode.label` | label of the CPU / GPU switch |
| `.schematic` | the word "schematic" as a tag in the frame |
| `.batch` | label of the batch that the device computes |
| `.idle` | narration before the first step |

### V13 Learning-rate schedule (`fig.lr.*`)

| Key | Use |
|---|---|
| `.series` | legend entry of the curve |
| `.slider.valuetext` | spoken value of a rate slider, with `{mantissa}` and `{exponent}` |
| `.readout` | rate at the marked step, with `{step}` and `{rate}` |
| `.custom` | tag shown when the values match no preset |

### V14 Hyperparameter story (`fig.story.*`)

| Key | Use |
|---|---|
| `.staircase.label` | accessible name of the list of steps |
| `.axis.elo` | axis label of the dot-and-whisker chart |
| `.filter.label` | label of the filter |
| `.level.<id>.effect` | spoken effect of a step, with `{elo}` and `{ci}`; 7 keys |
| `.unmeasured` | tag of a step without a measured effect |
| `.panel.open` | accessible name of the button that opens a panel, with `{level}` |
| `.panel.elo.slider.valuetext` | spoken values of the two sliders, with `{score}`, `{pairs}`, `{elo}` and `{ci}` |
| `.panel.elo.caption`, `.panel.sweeps.caption`, `.panel.futility.caption`, `.panel.sweep1.caption` | captions of the four panels |
| `.panel.sweeps.axis.elo` | shared y-axis label of the small multiples |
| `.panel.sweep1.threshold` | label of the line at 0.5 |
