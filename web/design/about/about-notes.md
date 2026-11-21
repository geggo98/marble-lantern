# Design notes: the Info part of marble-lantern (hub, topic pages, figure kit)

**TL;DR:** This directory holds the design of the extended Info part: the hub with six topic cards, the layout of a topic page, and a kit of figure components in a notebook style (a framed figure with a control panel above a plot).
`about-mockup.html` shows every component as static HTML at 320, 390, 844, 820 and 1280 px width; its `<style>` block is the reference for the component styles.
`tokens-about.css` adds the `--ml-fig-*` tokens to the existing `tokens.css` and changes none of them.
`keys-requested.md` lists the catalog keys that the design needs beyond the plan.
All text in the mock is a catalog key in square brackets; nobody has written the user-facing copy yet.
The mock was checked in headless Chromium at 320 px and 1280 px frame width, light and dark. It was not checked in Firefox or Safari, on a real phone, with a screen reader, in forced-colours mode or with a colour-vision simulator.

Date: 2026-11-21. Author: the design agent (Claude Fable 5.2), design task D1 of the "Info part" workflow.
The text of this file has had no independent review. The `technical-writing` skill of the owner asks that Opus drafts documents for people and that a fresh reader checks them; this session could start no other agent, so both steps are still open (section 13).

These notes extend `web/design/design-notes.md` (the design notes of the app, one directory up). Read that file first: it defines the visual language, the base tokens, the board and the existing components. Section numbers of the form "app notes, section 6" refer to it.

## Contents

1. [Files](#1-files)
2. [Visual language of a figure](#2-visual-language-of-a-figure)
3. [Colour and contrast](#3-colour-and-contrast)
4. [Typography](#4-typography)
5. [Layout](#5-layout)
6. [Components](#6-components)
7. [Data edges and control edges](#7-data-edges-and-control-edges)
8. [Input: keyboard, pointer, screen reader](#8-input-keyboard-pointer-screen-reader)
9. [Motion](#9-motion)
10. [Layout notes per figure](#10-layout-notes-per-figure)
11. [Component list](#11-component-list)
12. [Placeholder keys](#12-placeholder-keys)
13. [Deviations from the plan and open points](#13-deviations-from-the-plan-and-open-points)

## Terms

| Term | Meaning |
|---|---|
| Plan | The approved plan of the "Info part" workflow. It is not in the repository; during the workflow it is the file `info/plan.md` in the workflow's scratch directory. "Brief V5" means the figure brief with that number in its section 3. |
| Hub | The screen `#/about`: the About content of today, then the topic cards. |
| Topic page | One of the five pages `#/about/<topic>`, or the glossary page. |
| Figure | A framed block on a topic page with a title, optional controls, a plot or diagram, a caption and chips. |
| Kit | The set of figure components in section 6. |
| Figure breakpoint | 40rem (640 px) of *figure* width. Below it a figure uses its compact layout, from it the wide layout. |
| App breakpoint | 60rem (960 px) of app width, the one breakpoint of the app (app notes, section 5). |
| V1 to V14 | The figures of the plan. V8 is optional; there are V9a and V9b. |
| Catalog | An i18n file with the texts of one language. |

## 1. Files

| File | Content | How to use it |
|---|---|---|
| `about-mockup.html` | One self-contained page, no external requests. Sections: 01 hub, 02 topic page skeleton, K1 to K7 kit components, K8 control states, 09 light and dark side by side. | Open it in a browser. The bar at the top switches the theme; `?theme=dark` does the same. Classes with the prefix `ml-fig-` are the product; classes with the prefix `mock-` are only the frame of the mock. |
| `tokens-about.css` | The `--ml-fig-*` tokens: light on `:root`, dark under `prefers-color-scheme: dark` and under `:root[data-theme="dark"]`. | Load it after `tokens.css`. The two dark blocks must stay equal. |
| `about-notes.md` | This file. | |
| `keys-requested.md` | Catalog keys that the plan does not list. | Input for the text writer. |

The component styles sit in the mock's `<style>` block under the comment `about component styles`. Copy that block to `web/src/about/kit/figure.css`. One part, marked `hub`, belongs to the main stylesheet `web/src/ui/ui.css`, because the hub is part of the main bundle and the kit loads later.

Each mock frame carries a tag such as `K6-320`. The dashed violet line in a frame marks the lower edge of the viewport (for example 844 × 390). Frames with the mock class `mock-reduced` show the state under `prefers-reduced-motion: reduce`.

## 2. Visual language of a figure

The app notes say: colour belongs to the pieces, the interface is ink on paper. Figures keep that rule and add one more voice.

- **A figure is a notebook cell.** It has a thin frame and no shadow. From top to bottom: label and title, control panel on a sunken band, one narration line, the plot or diagram, caption, chips. The order never changes, so a reader learns it once.
- **Plots are bare.** A thin frame, ticks that point outward, no grid by default, numbers in the monospace face. The label of the y-axis stands horizontally above the top left corner of the frame, as in a notebook plot. Rationale: a rotated label collides with wide tick labels at 320 px (seen in the first draft of the mock) and is slower to read.
- **Teal is the voice of the figure.** One accent, `--ml-fig-accent`, marks what is current: the active step, the marker that follows a slider, the second data series. A plum colour, `--ml-fig-alt`, is the third series and the negative side of signed values.
- **Amber and blue stay with the two sides.** No figure uses them for anything else. Violet stays with the focus ring, red with errors.
- **Every distinction has a second cue.** Section 3 lists them.

## 3. Colour and contrast

All ratios are WCAG 2 contrast ratios, computed on 2026-11-21 from the hex values in `tokens.css` and `tokens-about.css` and rounded down to one decimal. Text needs 4.5:1, graphical parts need 3:1 (WCAG 2.2, level AA). "Resolved" gives the hex values behind the tokens, light then dark.

### Tokens

| Token | Light | Dark | Role |
|---|---|---|---|
| `--ml-fig-bg` | alias of `--ml-color-surface` | | figure frame, plot area, diagram boxes |
| `--ml-fig-panel-bg` | alias of `--ml-color-surface-sunken` | | control panel, diagram regions, chips |
| `--ml-fig-border`, `--ml-fig-frame`, `--ml-fig-dim` | alias of `--ml-color-line-strong` | | figure frame; plot frame and ticks; dimmed edges and outlines |
| `--ml-fig-grid` | alias of `--ml-color-line` | | optional grid lines, decorative |
| `--ml-fig-axis` | alias of `--ml-color-ink-muted` | | tick labels, axis labels |
| `--ml-fig-mark` | alias of `--ml-color-ink` | | series 1, edges, box outlines |
| `--ml-fig-board-bg` | alias of `--ml-board-star` | | plate of the small boards, flat |
| `--ml-fig-accent` | `#0b6660` | `#62cfc2` | active step, marker, series 2 |
| `--ml-fig-accent-bg` | `#d9ece8` | `#12332f` | fill of the active box or row |
| `--ml-fig-band` | `#cfe6e1` | `#17403b` | interval band |
| `--ml-fig-alt` | `#8a2a6b` | `#e58fc8` | series 3 |
| `--ml-fig-heat-1` to `-5` | `#4b837b` `#2f746d` `#1a625c` `#0c4c48` `#052f2e` | `#3f8a82` `#4fa59b` `#62cfc2` `#93e3d8` `#d2f7f1` | sequential ramp, 1 = low |
| `--ml-fig-neg-2`, `-neg-1` | `#7a1f5c` `#a8548c` | `#f0a6d6` `#c273a6` | negative values, 2 = strong |
| `--ml-fig-zero` | `#6e6557` | `#a59a88` | value near zero |
| `--ml-fig-pos-1`, `-pos-2` | `#2f746d` `#0c4c48` | `#4fa59b` `#93e3d8` | positive values, 2 = strong |
| `--ml-fig-pad`, `--ml-fig-pad-wide` | 0.75rem, 1rem | | inner padding below and from the figure breakpoint |
| `--ml-fig-radius` | alias of `--ml-radius-md` | | corner radius of the frame |
| `--ml-fig-stroke`, `-stroke-thin`, `-stroke-active` | 2, 1.5, 3.5 | | line widths: series and data edge; control edge and whisker; active edge |
| `--ml-fig-breakpoint` | 40rem | | documentation only |

An alias takes the light and dark value of its base token; it has no dark value of its own. Rationale: boxes and plots must stay the same ink and paper as the rest of the interface when a base token changes.
Limit: an alias resolves on `:root`. A component that forces a theme on a subtree must declare the aliases again on that subtree; the mock does this for its two forced-theme frames.

### Contrast table

| Pair | Resolved, light | Light | Resolved, dark | Dark | Needed |
|---|---|---|---|---|---|
| `--ml-color-ink` on `--ml-fig-bg` | #1f1b16 on #fffdf8 | 16.8 | #f2ebdf on #1e1a16 | 14.5 | 4.5 |
| `--ml-color-ink-muted` on `--ml-fig-bg` | #5a5246 on #fffdf8 | 7.5 | #b8ad9c on #1e1a16 | 7.8 | 4.5 |
| `--ml-color-ink` on `--ml-fig-panel-bg` | #1f1b16 on #ece5d6 | 13.6 | #f2ebdf on #0f0d0b | 16.3 | 4.5 |
| `--ml-color-ink-muted` on `--ml-fig-panel-bg` | #5a5246 on #ece5d6 | 6.1 | #b8ad9c on #0f0d0b | 8.7 | 4.5 |
| `--ml-fig-axis` on `--ml-fig-bg` | #5a5246 on #fffdf8 | 7.5 | #b8ad9c on #1e1a16 | 7.8 | 4.5 |
| `--ml-color-ink` on `--ml-fig-accent-bg` | #1f1b16 on #d9ece8 | 13.9 | #f2ebdf on #12332f | 11.5 | 4.5 |
| `--ml-color-ink-muted` on `--ml-fig-accent-bg` | #5a5246 on #d9ece8 | 6.2 | #b8ad9c on #12332f | 6.1 | 4.5 |
| `--ml-fig-bg` on `--ml-fig-accent` (number on an active marker) | #fffdf8 on #0b6660 | 6.6 | #1e1a16 on #62cfc2 | 9.2 | 4.5 |
| `--ml-color-bg` on `--ml-fig-mark` (number on a marker) | #f5f0e6 on #1f1b16 | 15.0 | #14110e on #f2ebdf | 15.8 | 4.5 |
| `--ml-fig-border` on `--ml-color-bg` | #857a68 on #f5f0e6 | 3.7 | #7d7364 on #14110e | 4.0 | 3 |
| `--ml-fig-frame` on `--ml-fig-bg` | #857a68 on #fffdf8 | 4.1 | #7d7364 on #1e1a16 | 3.7 | 3 |
| `--ml-fig-mark` on `--ml-fig-bg` | #1f1b16 on #fffdf8 | 16.8 | #f2ebdf on #1e1a16 | 14.5 | 3 |
| `--ml-fig-mark` on `--ml-fig-panel-bg` | #1f1b16 on #ece5d6 | 13.6 | #f2ebdf on #0f0d0b | 16.3 | 3 |
| `--ml-fig-dim` on `--ml-fig-bg` | #857a68 on #fffdf8 | 4.1 | #7d7364 on #1e1a16 | 3.7 | 3 |
| `--ml-fig-dim` on `--ml-fig-panel-bg` | #857a68 on #ece5d6 | 3.3 | #7d7364 on #0f0d0b | 4.1 | 3 |
| `--ml-fig-accent` on `--ml-fig-bg` | #0b6660 on #fffdf8 | 6.6 | #62cfc2 on #1e1a16 | 9.2 | 3 |
| `--ml-fig-accent` on `--ml-fig-panel-bg` | #0b6660 on #ece5d6 | 5.4 | #62cfc2 on #0f0d0b | 10.3 | 3 |
| `--ml-fig-accent` on `--ml-fig-accent-bg` | #0b6660 on #d9ece8 | 5.5 | #62cfc2 on #12332f | 7.2 | 3 |
| `--ml-fig-accent` on `--ml-fig-band` | #0b6660 on #cfe6e1 | 5.2 | #62cfc2 on #17403b | 6.1 | 3 |
| `--ml-fig-mark` on `--ml-fig-band` | #1f1b16 on #cfe6e1 | 13.0 | #f2ebdf on #17403b | 9.6 | 3 |
| `--ml-fig-alt` on `--ml-fig-bg` | #8a2a6b on #fffdf8 | 7.8 | #e58fc8 on #1e1a16 | 7.4 | 3 |
| `--ml-fig-accent` on `--ml-fig-board-bg` | #0b6660 on #e7dcc6 | 5.0 | #62cfc2 on #25201b | 8.6 | 3 |
| `--ml-fig-mark` on `--ml-fig-board-bg` | #1f1b16 on #e7dcc6 | 12.5 | #f2ebdf on #25201b | 13.6 | 3 |
| `--ml-board-socket-edge` on `--ml-fig-board-bg` | #84765c on #e7dcc6 | 3.2 | #7a6d5a on #25201b | 3.1 | 3 |
| `--ml-fig-heat-1` on `--ml-fig-board-bg` | #4b837b on #e7dcc6 | 3.1 | #3f8a82 on #25201b | 3.9 | 3 |
| `--ml-fig-heat-2` on `--ml-fig-board-bg` | #2f746d on #e7dcc6 | 4.0 | #4fa59b on #25201b | 5.5 | 3 |
| `--ml-fig-heat-3` on `--ml-fig-board-bg` | #1a625c on #e7dcc6 | 5.2 | #62cfc2 on #25201b | 8.6 | 3 |
| `--ml-fig-heat-4` on `--ml-fig-board-bg` | #0c4c48 on #e7dcc6 | 7.2 | #93e3d8 on #25201b | 10.9 | 3 |
| `--ml-fig-heat-5` on `--ml-fig-board-bg` | #052f2e on #e7dcc6 | 10.6 | #d2f7f1 on #25201b | 14.0 | 3 |
| `--ml-fig-neg-2` on `--ml-fig-board-bg` | #7a1f5c on #e7dcc6 | 7.0 | #f0a6d6 on #25201b | 8.5 | 3 |
| `--ml-fig-neg-1` on `--ml-fig-board-bg` | #a8548c on #e7dcc6 | 3.5 | #c273a6 on #25201b | 4.8 | 3 |
| `--ml-fig-zero` on `--ml-fig-board-bg` | #6e6557 on #e7dcc6 | 4.2 | #a59a88 on #25201b | 5.8 | 3 |
| `--ml-fig-pos-1` on `--ml-fig-board-bg` | #2f746d on #e7dcc6 | 4.0 | #4fa59b on #25201b | 5.5 | 3 |
| `--ml-fig-pos-2` on `--ml-fig-board-bg` | #0c4c48 on #e7dcc6 | 7.2 | #93e3d8 on #25201b | 10.9 | 3 |
| `--ml-fig-heat-1` on `--ml-fig-bg` | #4b837b on #fffdf8 | 4.2 | #3f8a82 on #1e1a16 | 4.2 | 3 |
| `--ml-fig-neg-1` on `--ml-fig-bg` | #a8548c on #fffdf8 | 4.7 | #c273a6 on #1e1a16 | 5.1 | 3 |
| `--ml-fig-zero` on `--ml-fig-bg` | #6e6557 on #fffdf8 | 5.6 | #a59a88 on #1e1a16 | 6.2 | 3 |
| `--ml-color-focus` on `--ml-fig-bg` | #5b2bd9 on #fffdf8 | 7.3 | #c2adff on #1e1a16 | 8.8 | 3 |
| `--ml-color-focus` on `--ml-fig-panel-bg` | #5b2bd9 on #ece5d6 | 5.9 | #c2adff on #0f0d0b | 9.8 | 3 |
| `--ml-color-focus` on `--ml-fig-accent-bg` | #5b2bd9 on #d9ece8 | 6.1 | #c2adff on #12332f | 6.9 | 3 |
| `--ml-color-focus` on `--ml-fig-board-bg` | #5b2bd9 on #e7dcc6 | 5.5 | #c2adff on #25201b | 8.2 | 3 |

All 41 pairs reach their threshold in both themes. The lowest values are 3.1 (`--ml-fig-heat-1` on the light plate, the socket edge on the dark plate). The plan asks for a unit test that recomputes this table (plan, section 6.5, "Contrast"); the test must resolve the aliases against `tokens.css`.

Two fills are below 3:1 on purpose, as decoration: `--ml-fig-band` and `--ml-fig-accent-bg` against the figure background. Each has an outline in `--ml-fig-accent` that carries the shape. Do not draw either without its outline.

### The second cue for every colour distinction

| Distinction | Colour | Second cue |
|---|---|---|
| Series 1, 2, 3 of a plot | ink, teal, plum | solid line with filled round points; long dashes with hollow square points; dots with filled diamond points |
| Heat value 1 to 5 | ramp light to dark (light theme), dark to light (dark theme) | the radius of the disc in the cell grows with the value: 4, 6, 8, 10, 11.5 units in a socket of 11.5 |
| Negative, zero, positive | plum, grey, teal | diamond, small dot, round disc; the size carries the magnitude |
| Active step | teal | thicker line, larger arrowhead, bold label, bold narration row |
| Dimmed part | grey outline | muted text; the active parts are bold |
| Data edge, control edge | none, both are ink | section 7 |
| Selected option, pressed preset | inverted fill | bold text; a pressed preset also shows a check mark |
| Visited, current, cut off, not reached (tree cells) | grey fill, teal fill, none, none | filled; filled with a dark outline; dashed outline; thin solid outline. A thick line marks the place of the cut. |
| Jump, step (a move in the tree) | none | the glyphs `»` and `›` of the move notation (app notes, section 6) |
| Promoted, rejected (V11) | none | filled dot above the line, hollow dot below, and a word in the readout |

Rule: a dimmed part changes its colour token, not its opacity.
Origin: brief V1 of the plan asks to dim the other parts "to 40 %". Ink at 40 % opacity on the figure background gives about 2.5:1, below the 3:1 that graphics need and far below the 4.5:1 that the labels in dimmed boxes need.
Rationale: with `--ml-fig-dim` for lines (at least 3.3:1) and `--ml-color-ink-muted` for text (at least 6.1:1), a dimmed diagram stays readable, and the table above covers it.
Limit: the rule is for parts that still carry information. A purely decorative layer may use opacity.

The ramps were chosen by reasoning and by the computed ratios. Nobody has run them through a colour-vision simulator.

## 4. Typography

The faces and sizes of the app apply (app notes, section 4). Additions:

| Part | Face and size |
|---|---|
| Title of a topic page (`h1`) | `--ml-font-display`, `--ml-text-2xl` (28 px) |
| Lead | `--ml-font-ui`, `--ml-text-lg` (18 px), muted |
| Section title (`h2`) | `--ml-font-display`, `--ml-text-xl` (22 px) |
| Figure title (`h3`) | `--ml-font-display`, `--ml-text-lg` (18 px) |
| Figure label ("Fig. 2.1") | `--ml-font-data`, `--ml-text-xs` (12 px), muted |
| Control label | `--ml-font-ui`, `--ml-text-sm` (14 px), medium |
| Readout of a control, position of a stepper | `--ml-font-data`, `--ml-text-sm`, tabular figures |
| Tick labels, values in a plot | `--ml-font-data`, 12 units of the SVG |
| Axis labels | `--ml-font-ui`, 12 units of the SVG |
| Box label in a diagram | `--ml-font-ui`, 12 px below the figure breakpoint, 14 px from it; the second line in `--ml-font-data`, 12 px |
| Caption | `--ml-font-ui`, `--ml-text-sm`, muted |
| Chip | `--ml-font-data`, `--ml-text-xs` |
| Formula | MathML in `--ml-font-display`, 16 px |

Rule: text inside an SVG must be at least 12 px on the screen.
Rationale: an SVG scales with its width, and its text scales with it. Each plot therefore has two view boxes (section 6, Plot), one per layout, so that one SVG unit is close to one CSS pixel in both. At 320 px the plot area is 262 px wide and the compact view box is 260 units wide, which gives 12.1 px.
Limit: the small boards are the exception. A board is 468 units wide and shrinks to 262 px, so a cell cannot hold readable text. A board carries no text; numbers stand in a readout or a table next to it.

A formula that is wider than about 250 px must be written in two rows that align at the equals sign (`<mtable>`), as the mock shows for the PUCT formula. Rationale: a long row would force the page to scroll sideways at 320 px.

## 5. Layout

### Hub

The About content of today keeps its place and order. Below it follows the section "How it works": a kicker, an `h2`, and a list of six cards. The list has one column below 34rem of app width, two from 34rem and three from 60rem.

A card is one link. It holds a pictogram of 48 × 48 px, a kicker with the topic number, the title, a teaser of one sentence, and a line with the reading time and a chevron. The glossary card shows the number of terms instead of a reading time. The six pictograms are stroke drawings in the text colour (stroke width 1.8 on a 48 unit grid) and are decorative (`aria-hidden`); the mock shows one per card: three boxes with connectors (architecture), a small tree with a cut branch (classical), a cell with layer bars (inference), a loop arrow around a marble (training), three rising whiskers (tuning), an open book (glossary).

### Topic page

The order in the DOM is fixed: breadcrumb, head (kicker with "2 / 5", `h1`, lead, reading time), "On this page", body (sections, "Further reading", previous/next).

| App width | Layout |
|---|---|
| below 60rem | One column with the 16 px gutter. "On this page" is a `<details>` element, open by default. |
| from 60rem | Two columns: a rail of 14rem and a text column of at most 46rem (736 px), with a gap of 2rem. The breadcrumb spans both. "On this page" is a `<nav>` with a heading in the rail; it sticks 1rem below the top edge. The head and the body are in the text column. |

Nothing is reordered against the DOM in a way that changes the reading order: the rail is to the left of the head and comes after it in the tab order only because the head holds no control.
Paragraphs keep the 42rem measure of `.ml-prose`. A figure spans the whole text column.

Widths of a figure at the frames of the mock:

| Viewport | App layout | Figure width | Figure layout |
|---|---|---|---|
| 320 px | one column | 288 px (plot area 262 px) | compact |
| 390 px | one column | 358 px | compact |
| 844 × 390 | one column | 812 px | wide |
| 820 × 1180 | one column | 788 px | wide |
| 1280 px | rail and text column | 736 px | wide |

Rule: a figure chooses its layout from its own width, with a container query `@container ml-fig (min-width: 40rem)`, not from the app breakpoint.
Origin: the plan names "compact layout below 60rem" for the box diagram.
Rationale: at 820 px and at 844 px the app is still in the one-column layout, yet the figure is wider than in the desktop layout. The compact layout would waste that width.
Limit: SVG parts with two view boxes (Plot, IcicleTree, and the coordinates of BoxDiagram) cannot switch by CSS alone. The component measures its width (for example with a `ResizeObserver`) and renders one of the two. It must use the same threshold of 640 px.

No frame scrolls sideways. Every label may wrap; boxes and buttons grow in height.

## 6. Components

Sizes are CSS pixels unless a row says "units", which means user units of the SVG.

### Figure (`.ml-fig`)

A `<figure>` with `aria-labelledby` on its title. It is the query container `ml-fig`.

| Part | Class | Notes |
|---|---|---|
| Frame | `.ml-fig` | 1 px `--ml-fig-border`, radius 10 px, background `--ml-fig-bg`, no shadow |
| Head | `.ml-fig__head`, `.ml-fig__label`, `.ml-fig__title` | label and title on one line when they fit; the title is an `h3` |
| Control panel | `.ml-fig__panel` | optional; see Manipulate |
| Narration | `.ml-fig__status`, `.ml-fig__step` | optional; `role="status"`, `aria-live="polite"`; minimum height 3.5rem so that a second line does not move the plot |
| Stage | `.ml-fig__stage`, `.ml-fig__stage--split` | the plot, board or diagram, then legend and readouts; `--split` gives two columns (20rem and the rest) from the figure breakpoint |
| Detail panel | `.ml-fig__detail` | optional; below the stage, never an overlay; 4 px bar on the start side; kicker, title (`h4`), text, chips, close button |
| Caption | `.ml-fig__caption` | `<figcaption>`, at most 44rem wide |
| Foot | `.ml-fig__foot` | source chips, then data chips, above a hairline |

Inner padding: 12 px below the figure breakpoint, 16 px from it.

States of the whole figure: default; loading (the topic chunk is not there yet: the page shows a text line, not a skeleton of the figure); failed (the existing `.ml-alert` with a retry button replaces the topic, frame K8).

### Manipulate (`.ml-fig__panel`)

A band with the background `--ml-fig-panel-bg` and hairlines above and below. It holds control rows `.ml-fig-ctl`.

| Figure width | Row layout |
|---|---|
| compact | label and readout on the first line, the control on the second |
| wide | three columns: label (11rem), control, readout (at least 6rem). A control without a readout spans the last two columns (`.ml-fig-ctl--wide`). |

Controls:

| Control | Classes | Element | States | Size |
|---|---|---|---|---|
| Slider | `.ml-slider` (existing), `.ml-fig-scale`, `.ml-fig-ctl__out` | `input type="range"` with `<label>` and `<output>` | default, focus, disabled | height 44 px; thumb 28 px; scale labels for the two ends below |
| Stepper | `.ml-fig-stepper`, `__pos`, `__track`, `__seg` | a `role="group"` with four buttons: reset, back, play or pause, forward | per button: default, focus, disabled; play shows the pause icon while playing | buttons 44 × 44 px with 8 px gaps; four buttons need 200 px |
| Segmented switch | `.ml-fig-seg`, `.ml-fig-seg__opt` | `role="radiogroup"` of native radios, each inside its label | unchecked, checked (inverted fill, bold), focus (ring inside the option) | options at least 44 px high and 5rem wide; they wrap to a second row when they do not fit |
| Live switch | `.ml-fig-live`, `__reason`, `__actions` (`LiveSwitch`) | a segmented switch "Example" / "My game (ply N)", then the reason line and the Stop or Try again button | My game disabled (reason below, linked by `aria-describedby`), running (Stop), failed (Try again) | as the segmented switch; buttons are `.ml-btn`. The run status goes into the figure's narration line, its only live region ([web UI](../../../docs/web-ui.md#explain-this-position-and-live-figures)) |
| Presets | `.ml-fig-presets` | a `role="group"` of `.ml-btn` with `aria-pressed` | not pressed, pressed (ink border, bold, check mark), focus | 44 px high; the row wraps |
| Checkbox | `.ml-fig-check`, `.ml-fig-check__box` | a native checkbox that covers its whole label | off, on (filled box with a check mark), focus (ring around the label) | label at least 44 × 44 px; drawn box 22 px |
| Select | `.ml-fig-select` | native `<select>` with `<label>` | default, focus | 44 px high |
| Readout cells | `.ml-fig-readout`, `__cell`, `__value` | not a control: a `<dl>`-like grid of label and value | | two columns in the compact layout, one row in the wide layout |

The track of a stepper shows one segment per step when there are at most 12 steps: done segments are ink, the current one is teal and taller, the others are outlines. With more steps it is a plain progress bar (`.ml-progress`). The track is decoration (`aria-hidden`); the position "3 / 6" is the text.

Why the segmented switch is not the existing `.ml-seg`: that component is 32 px high and built from buttons. The figures need 44 px targets (plan, section 3.0), and a choice of one out of several is a radio group for a screen reader. The pattern follows `.ml-trail-card__modes` in `web/src/ui/ui.css`.

### Plot (`.ml-fig-plot`)

An inline SVG. Two view boxes:

| Layout | View box | Margins: left, right, top, bottom |
|---|---|---|
| wide | `0 0 640 280` | 52, 16, 28, 44 |
| compact | `0 0 260 180` | 40, 10, 28, 40 |

A figure brief may set another height. Element order, back to front: band, reference lines, frame, ticks and tick labels, axis labels, leaders, lines, whiskers, points, cursor, value labels.

| Part | Class | Geometry |
|---|---|---|
| Frame | `__frame` | 1 unit, `--ml-fig-frame` |
| Ticks | `__tick`, `__tick--minor` | outward, 6 units; minor 3 units. A log axis has a major tick with a label at each labelled value and minor ticks at 2, 3, 4 and 5 times each power of ten. |
| Axis labels | `__axislabel` | x: centred under the tick labels. y: horizontal, above the top left corner of the frame. |
| Grid | `__grid` | optional, 1 unit, `--ml-fig-grid`; off by default |
| Reference line | `__ref` | 1 unit, dash 8 3 2 3; for zero or a threshold such as 0.5 |
| Lines | `__line`, `__line--1` to `--3` | 2 units; solid, dash 7 4, dash 2 4 |
| Points | `__pt--1` to `--3` | filled circle r 4.5; hollow square 8; filled diamond 12 |
| Dot and whisker | `__whisker` with `__pt--1` | vertical line 1.5 units with caps of 10 units; the dot on top |
| Dots with leaders | `__leader` with `__pt--1` | a thin line from the axis to a dot; for values on a log axis |
| Bars | `__bar` | ink; only on a linear axis that starts at zero |
| Band | `__band`, `__bandedge` | fill `--ml-fig-band`, both edges as 1 unit lines in the accent |
| Cursor | `__cursor`, `__now` | the value chosen by a slider: a dashed vertical line and a ring r 8 around the point |
| Value labels | `__value`, `__best` | ink, weight 600; the best value weight 700 |

Rule: on a log axis, draw dots with leaders, not bars.
Rationale: the length of a bar encodes a value only when the axis starts at zero, and a log axis has no zero. Brief V9b asks for "horizontal bars on a log axis"; the dot keeps the reading "further right is faster" without a false length.
Limit: on a linear axis from zero, bars stay correct (value bars of V9b on the range −1 to 1 start at 0 in the middle).

In the compact layout a chart with category names (backends) puts each name on its own line above its dot instead of to the left of the axis (frame K4-320).

The legend `.ml-fig-legend` is HTML below the SVG, so its text wraps and keeps its size. Each entry repeats the mark as a small inline SVG.

Accessibility: `role="img"` with `<title>` and `<desc>`. The description states what the plot shows; exact values that matter are also in a readout or a table outside the SVG.

### MiniBoard and HeatBoard (`.ml-fig-board`)

Both use the view box and the cell formula of the game board (app notes, section 6): `viewBox="-234 -266 468 532"`, cell `(q, r)` at `x = √3 · 20 · (q + r/2)`, `y = 30 · r`, socket radius 11.5, piece radius 13.5. The plate is flat (`--ml-fig-board-bg`), without the glow. The board is at most 22rem wide and centred. At 320 px it is 262 px wide; one cell is then 13 px across.

| Layer, bottom to top | Class | Content |
|---|---|---|
| 1 | `.ml-fig-board__plate-edge`, `.ml-fig-board__plate` | the two triangles `M0 -240 207.8 120 -207.8 120z` and `M0 240 -207.8 -120 207.8 -120z` with a round stroke of 51 and 48 units |
| 2 | `.ml-socket` (existing) or `.ml-fig-heat__ring` | 121 circles; a heat board draws only the ring |
| 3 | `.ml-fig-heat__v--1` to `--5`, `.ml-fig-heat__s--…` | heat board: one disc per cell, see section 3; negative values are diamonds |
| 4 | `.ml-fig-board__nb` | marked cells, for example the neighbours of a cell: ring r 13, 4 units, accent |
| 5 | `<use href="#ml-piece-a">`, `#ml-piece-b` | MiniBoard: the marbles of the board design |
| 6 | `.ml-fig-board__from`, `__arrow`, `__arrowhead` | a move: dashed ring on the origin, a line of 5 units that ends 26 units before the target centre, a triangle head |
| 7 | `.ml-cellfocus`, `.ml-cellfocus__gap` (existing) | keyboard focus of a cell picker |

Rules for boards in figures:

- The figure never rotates the board without saying so. The caption states which seat is at the top (key `kit.board.orientation`). In engine coordinates the first player starts in the top corner; the mock draws the start position that way.
- A board holds no text (section 4).
- A static board is `role="img"` with `<title>` and `<desc>`. A board with a cell picker is `role="group"` with `aria-labelledby`.

The legend of a heat board shows the five discs in rising size (`kit.legend.heat`), with the words for the two ends.

### BoxDiagram (`.ml-fig-box`)

HTML boxes over an SVG edge layer. Both use one coordinate system.

- The wrapper `.ml-fig-box` has `aspect-ratio: W / H` of the layout.
- The edge layer `.ml-fig-box__edges` is an SVG with `viewBox="0 0 W H"` that fills the wrapper. It is `aria-hidden`.
- Regions, boxes, edge labels and markers are HTML elements with `position: absolute` and `left`, `top`, `width`, `height` in per cent of W and H.

Rationale: a box is then a real `<button>` whose label wraps and keeps its font size, which SVG text cannot do, and German labels are longer. The edges still scale exactly with the boxes, with no measuring at run time.
Limit: the height of a box is a share of the wrapper, so a label that needs more than two lines (three in the narrow boxes of the compact layout) overflows. Such a label needs the `.short` variant of its key.

| Layout | Coordinates | Box size | Font |
|---|---|---|---|
| wide | 720 units wide; V1 is 720 × 452 | 144 × 60 units, 32 units apart in a row | 14 px, second line 12 px monospace |
| compact | 264 units wide; V1 is 264 × 658 | full-width boxes 240 × 46; three in a row 76 × 50 | 12 px, no second line |

At 320 px one unit of the compact layout is 0.99 px, so the boxes are 45 px and 49 px high and keep the 44 px target.

| Part | Class | Look |
|---|---|---|
| Region | `.ml-fig-box__group`, `__grouplabel` | sunken fill, hairline, radius 10 px; an upper-case label in the top left corner. A region needs 26 units of head room for its label. |
| Box, component | `.ml-fig-box__node` | 1.5 px ink outline, radius 6 px, fill `--ml-fig-bg` |
| Box, store | `.ml-fig-box__node--store` | double line of 5 px above and below, square corners (a data store in the usual notation) |
| Box, external | `.ml-fig-box__node--external` | dashed outline, no fill |
| Edge | `.ml-fig-edge`, `.ml-fig-edge--control`, `.ml-fig-edge__head--data`, `--control` | section 7; routed at right angles; the head is its own path, 9 units long and 9 wide (11 and 11 when active) |
| Edge label, wide | `.ml-fig-box__edgelabel` | monospace 12 px on a patch of the background, centred on a segment of the edge |
| Edge marker, compact | `.ml-fig-num`, `.ml-fig-box__num` | a number in a 22 px mark on the edge; the same mark starts the matching row of the list `.ml-fig-box__key` below the diagram |
| Legend | `.ml-fig-legend` | data edge, control edge, active |

States of a box (`data-state`, `aria-expanded`):

| State | Look |
|---|---|
| default | as above |
| hover | sunken fill |
| focus | the violet focus ring |
| `active` (part of the current step) | 3 px accent outline, fill `--ml-fig-accent-bg`, bold |
| `dim` (not part of the current step) | outline `--ml-fig-dim`, muted text |
| detail open (`aria-expanded="true"`) | a second outline in ink, 2 px outside a 2 px gap |

States of an edge, its head, its label and its marker (`data-state`): default; `active` (accent, 3.5 units, larger head, label bold on `--ml-fig-accent-bg` with an accent outline); `dim` (`--ml-fig-dim`, label muted, marker hollow with a muted number).

With no step selected (idle), nothing has a `data-state`: everything is drawn in ink.

Compact layout: the plan asks that arrow labels become numbered markers that the narration explains. The list under the diagram gives every number its text, so the diagram can be read without stepping through it. The rows of the list are in step order; the row of the active step is bold.

### IcicleTree (`.ml-fig-ice`)

An SVG that draws a search tree as stacked bars: the root as one bar across the top, one column per root move below it, and in each column one small cell per reply.

| Layout | View box (14 columns, 14 replies) | Column | Root and move bars | Reply cell |
|---|---|---|---|---|
| wide | `0 0 640 218` | 42 units wide, 4 apart | 24 units high | 9 units high, 2 apart |
| compact | `0 0 260 204` | 16.7 units wide, 2 apart | 24 units high | 8 units high, 2 apart |

Cell states: `--visited`, `--active`, `--path` (an ancestor of the active cell: accent outline, light accent fill), `--skipped` (cut off), `--todo` (not reached yet). `.ml-fig-ice__cut` is a line of 2 units across the column at the place where the search stopped. A move bar shows `»` for a jump and `›` for a step.

The cells are too small to be targets, so the tree is `role="img"`. The stepper and a select "jump to root move" reach every state (brief V5).

The numbers in the mock frame K7 are a layout sample: the first column is searched in full and each later column stops after two replies, which gives the 55 nodes of the plan. The real counts per column come from `ab-tree-start.json`.

### Glossary term and popover (`.ml-fig-term`, `.ml-fig-pop`)

A term in running text is a `<button>` with a dotted underline of 2 px in the muted colour; hover and the open state make it solid ink. It has `aria-expanded` and `aria-controls`.

The popover is a card 20rem wide, at most the viewport width minus 2rem: the term in the display face, in the German catalog the English term below it in small monospace, the short definition, the link "more in the glossary" (44 px high), and a close button (44 × 44 px) in the top corner. It opens below the line of the term and aligns with the start edge of the text column, so it never leaves the column at 320 px.

Rule: the term button has `padding: 0.625rem` and `margin: -0.625rem`.
Rationale: the end-to-end test of the plan requires 44 × 44 px for every button. The padding enlarges the hit area to at least 44 px in both directions; as the button is an inline element, the padding does not change the line height, and the negative margin cancels the horizontal shift.
Limit: the hit areas of two terms in neighbouring lines overlap by up to 10 px. Authors should not put two terms directly above one another or next to one another; the markup test could warn about two `{term:}` tokens with fewer than three words between them.

### Source chip and data chip (`.ml-fig-chip`)

A link. The visible pill (`.ml-fig-chip__pill`) is 28 px high, in monospace 12 px on the sunken fill, with a code icon; the link itself is 44 px high. A path may break anywhere.
The data chip (`.ml-fig-chip--data`) has a dashed outline, no fill and a braces icon; it names the JSON file of a figure.
Inside a sentence the chip uses `.ml-fig-chip--inline`: no icon, the height of the line.
Each chip starts with visually hidden text (`kit.chip.src`, `kit.chip.data`), so a screen reader says what the path is.

### Other page parts

| Part | Classes | Notes |
|---|---|---|
| Topic card | `.ml-fig-hub`, `__title`, `__grid`, `__card`, `__picto`, `__teaser`, `__meta` | built on `.ml-card`; the whole card is one link; hover shows a border |
| Topic page | `.ml-fig-topic`, `__head`, `__title`, `__lead`, `__meta`, `__body` | section 5 |
| Breadcrumb | `.ml-fig-crumb` | a link with a left arrow, 44 px high |
| On this page | `.ml-fig-toc`, `__summary`, `__heading`, `__list`, `__link` | rows 44 px high with a number; the section in view has `aria-current="location"`: a 3 px ink bar at the start, a tinted row and bold text |
| Section | `.ml-fig-section`, `__title`, `__sources` | the title is an `h2` with `tabindex="-1"`, because the router moves focus to it |
| Formula | `.ml-fig-math` | MathML with a 3 px bar at the start |
| Inline code | `.ml-fig-code` | monospace on the sunken fill |
| Reading list | `.ml-fig-reading`, `__list`, `__item`, `__link`, `__meta`, `__note` | an ordered list in one card; per entry: title link with the external icon (44 px high), source and year, a level tag, a "paywalled" tag where it applies, one note line |
| Previous and next | `.ml-fig-pager`, `__link`, `__link--next`, `__dir`, `__name` | two cards side by side at every width, each at least 64 px high; the next link is in the second column also when there is no previous one |

New icons, 24 unit grid, stroke 1.8, in the `<defs>` of the mock with the id prefix `i-`: `pause`, `step-back`, `step-fwd`, `reset`, `code`, `data`, `clock`, `left`, `right`, `chev`, `down`.

## 7. Data edges and control edges

A data edge carries data from one box to another. A control edge means that one box starts, triggers or stops another. The two differ in four ways, none of which is colour:

| | Data edge | Control edge |
|---|---|---|
| Line | solid, 2 units | dashed 6 4, 1.5 units |
| Head | filled triangle | open angle of two strokes |
| Marker in the compact layout | number in a filled disc | number in a hollow diamond |
| Word | `kit.edge.data` | `kit.edge.control` |

The word is in the legend, and as visually hidden text before each row of the numbered list.
Both kinds use the same ink. In the active state both turn teal and thicker, and the control edge stays dashed (dash 9 5).

Rule: a dash pattern on an edge always means "control". The active state never adds or removes a dash.
Origin: the plan asks that under reduced motion "the active arrow is drawn thicker with a static dash".
Rationale: a dashed active data edge would look like a control edge, in the one moment in which the reader looks at it.
Limit: this is about edges of a box diagram. Lines of a plot use dash patterns to tell series apart; a plot has no control edges.

The switch "data flow / control flow / both" sets the edges of the other kind to `dim`; it does not remove them, so the boxes do not seem to lose connections.

## 8. Input: keyboard, pointer, screen reader

### Tab order

The tab order follows the DOM: breadcrumb, "On this page", then per section the terms and chips in the text, then per figure the controls of the panel from top to bottom, the boxes or cells of the stage, the close button of an open detail panel, the chips of the foot. Then the reading list and the previous/next links.

### Keys

| Control | Key | Action |
|---|---|---|
| Any button, link | Enter; Space on buttons | activate |
| Slider | Left, Down / Right, Up | one step down / up |
| | Page Down / Page Up | a tenth of the range, or one stop when the slider has fewer than ten stops |
| | Home / End | first / last value |
| Stepper (focus on any of its buttons) | Left / Right | one step back / forward; the focus stays on the button |
| | Home / End | first / last step |
| | Space | on the play button: play or pause. On another button of the stepper: that button's own action. |
| | Enter | the action of the focused button |
| Segmented switch | Tab | one stop for the group, on the checked option |
| | Left, Up / Right, Down | previous / next option, checked at once; the ends wrap |
| | Space | check the focused option |
| Presets | Tab | each preset is one stop |
| | Enter, Space | apply the preset; `aria-pressed` becomes true. When a slider changes afterwards, no preset is pressed. |
| Checkbox | Space | toggle |
| Select | the keys of the native element | |
| Box of a diagram | Enter, Space | open the detail panel of this box; if it is open, close it |
| | Escape | close the open detail panel; focus returns to its box |
| Close button of a detail panel | Enter, Space, Escape | close; focus returns to the box |
| Cell picker of a board (one tab stop, roving `tabindex`) | Left, Right | next cell in the row |
| | Up, Down | nearest cell in the row above or below; the board remembers the x position where vertical movement started |
| | Home, End | first and last cell of the row |
| Glossary term | Enter, Space | open the popover; focus stays on the term |
| | Tab | with the popover open: into the popover (link, close button), then on to the text |
| | Escape | close the popover; focus returns to the term |
| "On this page" below 60rem | Enter, Space on the summary | open or close the list |
| Source tree of V4 | the keys of the WAI-ARIA tree pattern | Up, Down move; Right opens or goes to the first child; Left closes or goes to the parent; Home, End; Enter selects |
| Disclosure inside a figure | Enter, Space | open or close |

The keys of the cell picker are those of the game board (app notes, section 7), without Page Up, Page Down and Enter, because a figure selects the focused cell at once.

While a stepper plays, any key that changes the step stops the playback first. Playback stops at the last step and does not loop.

Arrow keys on a stepper must not scroll the page: the handler calls `preventDefault` for Left, Right, Home and End only while focus is inside the stepper group.

### Focus rings

Every control shows the ring of the app: 3 px solid `--ml-color-focus` with a 2 px offset. Exceptions, all shown in frame K8:

| Control | Ring |
|---|---|
| Option of a segmented switch | on the label, offset −3 px, so the ring is inside the option and the frame of the group does not clip it |
| Checkbox | on the whole label, offset 2 px |
| Glossary term | offset −0.5rem, so the ring hugs the word instead of the enlarged hit area |
| Board cell | the existing `.ml-cellfocus` ring with a gap in the plate colour |

The ring has at least 5.5:1 against every background of a figure (section 3).

### Targets

Every button, input and select is at least 44 × 44 px. Links that stand alone (breadcrumb, rows of "On this page", chips, reading titles, previous/next, the link in the popover) are at least 44 px high. The inline chip inside a sentence is the one exception; it follows the line height, and the same path appears as a full-size chip under the section.
A scripted check of all mock frames found no control of the kit below 44 px in either direction. The language switch in the header is an existing component (`.ml-seg`, 32 px) and was left out of the check.

Pointer: a hover on a box, a preset or a row of "On this page" changes the fill. Nothing in a figure needs hover: a figure that shows something on hover (brief V5: the line to a node) shows the same for the step that the stepper or a select has chosen.

### Screen reader

- A figure is a `<figure>` named by its title; the caption is its `<figcaption>`.
- The narration line is the only live region of a figure. Each step writes one sentence into it, starting with "Step k of n" (`kit.stepper.step`). A slider does not write into it; its `aria-valuetext` carries the meaning (for example "20,000 nodes, depth 6").
- A picture is `role="img"` with `<title>` and `<desc>`. A diagram with boxes is `role="group"` with a label; its edge layer is hidden, and the numbered list (compact) or a visually hidden list of the same rows (wide) names every edge with its kind, source and target.
- A box has `aria-expanded` and `aria-controls` for its detail panel. The detail panel is a `<section>` named by its title.
- Tables (root moves of V10, features of V6, the futility grid of V14) are real `<table>` elements with a `<caption>`.
- The popover is a `role="dialog"` without `aria-modal`, named by the term. The native `popover` attribute gives light dismiss and Escape where the browser supports it.

## 9. Motion

| What | Duration token | Behaviour |
|---|---|---|
| Hover on a card, a box, a button | `--ml-dur-fast` | fill or border changes |
| Step of a stepper, a moved slider | `--ml-dur-base` | parts change state with a fade of colour and line width; nothing moves |
| Playback | 1.2 s per step | the stepper advances by itself; the narration follows |
| Active edge | `--ml-dur-think` per cycle | a small dot (`.ml-fig-edge__dot`, r 4.5) travels along the edge from source to target (`offset-path`) |
| Detail panel, popover, disclosure | `--ml-dur-base` | fade in |
| Scroll to a section | browser | `scrollIntoView` with `behavior: smooth` |

With `prefers-reduced-motion: reduce`:

- The duration tokens are 0 (`tokens.css`), so every state change is instant.
- The play button is not rendered. The stepper keeps reset, back and forward, and Space has no playback to toggle.
- The travelling dot is not rendered. The active edge is recognisable by colour, width, the larger head and the bold label or marker, all of them static.
- Scrolling to a section uses `behavior: auto`.
- Bars, curves and heat maps change without a transition.

No information exists only as motion: the dot repeats the direction that the arrowhead already shows.

The dot needs `offset-path` on an SVG element. The style sheet shows it only inside `@supports (offset-path: path("M0 0"))`; elsewhere the static look applies. The mock contains the dot, but its animation was not looked at frame by frame.

## 10. Layout notes per figure

Each note gives the view box or coordinate system, the order of the elements from back to front, the focusable parts in tab order after the controls of the panel, and the compact variant at 320 px. "Plot wide" and "plot compact" mean the two view boxes of section 6. All figures use the Figure frame of section 6.

### V1 Offline architecture

- **Kind:** BoxDiagram. Shown in the mock, frames K6-320 and K6-1280.
- **Wide:** 720 × 452. Row 1, region "main thread" (y 12 to 124): Board, GameController, EngineSession, WorkerEngine at x 24, 200, 376, 552, y 48. Row 2 (y 164 to 276): region "service worker" with one box at x 24, region "Web Workers" with Java, C++ and net engine at x 200, 376, 552, y 200. Row 3, region "static host" (y 316 to 440): the host as an external box at x 24, the three file stores at x 200, 376, 552, y 360. Request and reply between WorkerEngine and a worker run in the 40 unit gap between rows 1 and 2 on two levels (y 136 and y 153), so they do not cross.
- **Order:** regions, edges, heads, travelling dot, boxes, edge labels.
- **Focusable:** the boxes, row by row from left to right (12).
- **Controls:** segmented switch (3 options), stepper (6 steps), checkbox "cancel".
- **Compact:** 264 × 658. One column: the four main-thread boxes stacked (240 × 46, 28 units apart), then the three workers in a row (76 × 50), the service worker (240 × 46), the three stores in a row. The host box is left out; the region label names the host. Edges are vertical; numbered markers sit in the gaps; the numbered list follows the diagram. The cancel variant replaces the list rows of steps 4 to 6 by its own three rows.

### V2 Online architecture

- **Kind:** BoxDiagram plus a permit row.
- **Wide:** 720 × 400. Two regions side by side: browser (x 12 to 250) with the page, the browser engines and the server adapter stacked; server (x 290 to 708) with GameEndpoint and Engines in a first column and the two players and the race tables (store) in a second. One horizontal HTTP edge crosses the 40 unit gap, with the reply below it.
- **Permit row:** HTML below the diagram, not SVG: a wrapping row of slots of 28 × 28 px, one per permit, each filled when a request holds it, and after a divider one token per waiting request. A running request is a filled square, a waiting one a hollow square with a clock icon; two words with counts stand next to the row (`.semaphore.running`, `.semaphore.waiting`). The row is a list for a screen reader.
- **Focusable:** the boxes (about 9).
- **Controls:** segmented switch, stepper (6 steps), slider "concurrent requests".
- **Compact:** 264 units wide. Browser region on top, server region below, one vertical HTTP edge with a marker between them; inside each region the boxes are stacked. The permit row wraps.

### V3 Training pipeline

- **Kind:** BoxDiagram with file stores.
- **Wide:** 720 × 440. The loop is a ring of five boxes around the run directory: self-play top left, replay buffer top right, trainer right, gate bottom right, promoted network bottom left; the edges run clockwise. The run directory `data/<run>/` is a region in the centre whose content is a list of file stores (real file names, monospace). Teacher games enter from the left edge. The packing exit is a row along the bottom: chosen candidate, `pack-model.py`, `web/models/`.
- **Order:** as V1.
- **Focusable:** the boxes, then the files (about 12 and 8).
- **Controls:** stepper (5 steps), segmented switch CPU / GPU. The switch changes the second line of the trainer and self-play boxes and a readout cell with the measured time.
- **Compact:** 264 units wide. A vertical pipeline of full-width boxes. The return edge of the loop runs up the right side in a 20 unit lane, so the boxes are 220 wide here. The files are a list inside the detail panel of the run-directory box, not in the diagram. The packing exit is its own region below.

### V4 Source tree explorer

- **Kind:** HTML tree plus a small dependency graph.
- **Tree:** a nested list with `role="tree"`; rows 44 px high; indentation 20 px per level; a chevron that turns for an open directory; the path in monospace; tags `.ml-tag` for generated, ignored and LFS; one note line in muted text.
- **Graph, wide:** an SVG next to the tree (`.ml-fig__stage--split`, graph in the second column), view box `0 0 360 360`: the ten Gradle modules as boxes of 96 × 32 units in four ranks from `engine` at the bottom to `app` and `selfplay` at the top; edges "depends on" point down. Selecting a module sets its edges and neighbours to `active` and the rest to `dim`. `role="img"`; the same facts are in a text line under the graph.
- **Focusable:** the tree (one tab stop), nothing in the graph.
- **Controls:** radio group for the filter (segmented switch, 4 options plus "everything").
- **Compact:** no graph. Under the tree, two text lines for the selected module: "depends on" and "used by".

### V5 Alpha-beta tree of the start position

- **Kind:** readout cells, IcicleTree, MiniBoard. The tree is in the mock, frames K7-320 and K7-1280.
- **Wide:** `.ml-fig__stage--split`: the mini board in the first column (20rem), readouts and tree in the second. Tree `0 0 640 218`, scaled to the column.
- **Order in the tree:** root bar, move bars with glyphs, reply cells, cut lines, the active cell last.
- **Board:** the position, the move of the active root move as an arrow, and the reply as a second arrow with a dashed line.
- **Focusable:** nothing in the stage.
- **Controls:** segmented switch (minimax / alpha-beta), stepper (55 steps, plain progress bar), select "jump to root move" (14 options).
- **Readouts:** alpha, beta, score, nodes visited.
- **Minimax view:** all 196 reply cells are `--visited`; no cut lines; the counter shows 210.
- **Compact:** readouts in two columns, tree `0 0 260 204`, then the board at full width.

### V6 Evaluation features on the board

- **Kind:** MiniBoard with an overlay, an HTML feature table, a small Plot.
- **Board overlay:** depends on the feature. Marked cells use `.ml-fig-board__nb`; pairs (touching pieces) are joined by a line of 4 units in the accent; jumps are arrows. Distance numbers do not fit in a cell (section 4): the overlay shows the distance of a piece as a heat disc under the marble's ring, and the exact numbers stand in the table row of the feature.
- **Table:** a real `<table>`: checkbox "include", feature name (a button that selects the feature), value, weight, contribution as a bar. The bar is HTML: a track with a zero line in the middle; teal to the right for a positive contribution, plum to the left for a negative one; the signed number next to it. The selected row has the fill `--ml-fig-accent-bg` and `aria-pressed` on its button.
- **Sigmoid plot:** plot compact (260 × 180) also in the wide layout, in the second column; the curve as series 1, the current score as cursor, a reference line at 0.5.
- **Focusable:** per row the checkbox and the feature button (13 rows), then the weight slider and its reset button below the table.
- **Wide:** board in the first column; table, slider and plot in the second.
- **Compact:** board, then two readout cells (score, win probability), then the table as rows (name and bar on the first line, the numbers on the second), then a disclosure "show plot".

### V7 Node budget and depth

- **Kind:** Plot with a log x-axis. In the mock, frames K2-320 and K2-1280.
- **Plot:** x from 700 to 700,000 with major ticks at the five budgets (labels 1k, 5k, 20k, 100k, 500k); y from 0 to 11 with ticks every 2. Series 1 "browser" with five points, series 2 "plain" with two points. The cursor marks the chosen budget on both series; the depth stands next to each marked point.
- **Focusable:** nothing in the stage.
- **Controls:** slider with five stops; readout with the node count; a line under the plot with depth and move.
- **Compact:** plot compact, height 180; the legend below it in one column.

### V8 Race table (optional)

- **Kind:** MiniBoard and an HTML table.
- **Board:** the passed position; the legal moves of the side to move as thin arrows, the chosen move as a thick arrow in the accent.
- **Table:** move and table count; the chosen row bold with a tag.
- **Controls:** stepper through the race; the same split and compact layout as V6.

### V9a Network architecture and hex convolution

- **Kind:** an HTML layer stack and a board with a cell picker. The picker is in the mock, frame K5.
- **Stack:** a vertical list of buttons, one per layer, in the order input to output; each button shows the name, the shape in monospace and the parameter count at the end. Between two layers a short data edge (an SVG of 24 units height). The four residual blocks are one region with a skip connection drawn as a bracket on the end side that leaves above the first convolution and re-enters below the second, with a plus mark. The two heads sit side by side below the trunk in the wide layout. A total row closes the stack.
- **Board:** all sockets empty; the focused cell as a heat disc of level 5 with the focus ring; its neighbours with the ring `.ml-fig-board__nb` and a small disc. Two readout cells below: the cell name and the number of neighbours. The formula stands under the readouts as MathML in two rows.
- **Focusable:** the layers in order, then the board (one tab stop).
- **Controls:** segmented switch for the model.
- **Wide:** stack in the first column, board in the second.
- **Compact:** the stack with the residual blocks folded into one button "× 4" (a disclosure), then the board.

### V9b A forward pass on a real position

- **Kind:** HeatBoard, an HTML list for policy and value, and a Plot for the backends. Boards and the backend chart are in the mock, frames K5 and K4.
- **Board:** at the input step, the chosen plane: a plane of 0 and 1 draws level 5 or nothing; a scaled plane uses the five levels. At later steps the activation strength in five levels, with the bounds of the levels fixed per position over all layers, so that layers can be compared. At the last step the board shows the position as a MiniBoard with the five strongest policy moves as arrows whose width is 2 to 6 units by prior.
- **Policy list:** five rows: move in notation, a bar, the prior as a number.
- **Value:** per player one row with the marble swatch, a track from −1 to 1 with a zero line, a bar from zero, the number.
- **Backend chart:** dots with leaders on a log axis from 50 to 100,000; one row per backend; the value next to the dot; a row measured at another batch size says so in its name.
- **Focusable:** nothing in the stage.
- **Controls:** select for the position, stepper for the layer (8 steps, segmented track), select for the plane (enabled only at the input step), checkbox for the residual priors.
- **Wide:** board in the first column, policy and value in the second; the backend chart is a second figure below.
- **Compact:** one board at full width, then policy and value, then the backend chart with the names above the dots.

### V10 MCTS, one simulation at a time

- **Kind:** IcicleTree, an HTML table, a small Plot. The inset chart is in the mock, frame K3.
- **Tree:** the root bar, one column per root move sorted by prior, below each column its children as cells, two levels deep in every layout. The width of a column is equal; the visit count of a root move is a number in the table, not a width, because 54 columns of unequal width cannot be compared at 320 px. Path of the current simulation: `--path`; the new leaf: `--active`; expanded earlier: `--visited`; not expanded: `--todo`.
- **Table:** move, prior P, visits N, mean value Q, PUCT score. The row of the next selection has the fill `--ml-fig-accent-bg` and a tag; the final choice is bold with a tag after step 50.
- **Inset:** dot and whisker, five points on a category axis, a reference line at 0, the best value bold.
- **Focusable:** nothing in the tree; in the compact layout the disclosure "show all".
- **Controls:** stepper over 50 simulations (progress bar), segmented switch for the four sub-steps, slider for `c`.
- **Wide:** tree at full width (`0 0 640 …`, 54 columns of 10 units with 1.8 unit gaps), then table and inset side by side.
- **Compact:** the tree shows the 14 root moves with the most visits and one summary column for the rest; the table shows 6 rows and the disclosure; the inset is below the table.

### V11 The self-play loop

- **Kind:** BoxDiagram as a cycle, and a Plot as a timeline.
- **Cycle, wide:** 360 × 300, in the first column: six stage boxes on a hexagon, clockwise from the top: self-play, samples, replay window, training, gate, decision. The edges are data edges; the edge from the decision back to self-play is a control edge when the candidate was rejected (the old network plays on) and a data edge when it was promoted.
- **Timeline:** plot wide in the second column, shortened to `0 0 360 280`: x the generation 1 to 14, y the gate score from 0.44 to 0.64, a reference line at 0.5 with a label. A promoted generation is a filled dot, a rejected one a hollow dot. With the standard error, each dot gets a whisker and the lower end of the whisker decides. The cursor marks the chosen generation. The ladder strength is a second, smaller dot-and-whisker plot below, four points.
- **Focusable:** the six stage boxes.
- **Controls:** slider for the generation, checkbox "old rule".
- **Compact:** the cycle as a vertical list of six full-width boxes with a return edge in a lane on the end side; the timeline as plot compact, 260 wide, with a tick label at every second generation.

### V12 Virtual threads and one device thread

- **Kind:** a schematic BoxDiagram with tokens.
- **Wide:** 720 × 300. Left: a grid of game tokens, 6 × 4, each a circle of 22 units; the fill pattern tells the two cohorts apart (cohort A solid, cohort B hatched) and each token carries the letter A or B. Centre: the evaluator box with two batch trays (stores). Right: the device box. Data edges: tokens to tray, tray to device, device back to tokens. The frame carries the tag "schematic".
- **CPU mode:** the trays and the device box are gone; each token has its own small evaluator mark.
- **Focusable:** nothing in the stage.
- **Controls:** stepper (6 steps), segmented switch CPU / GPU.
- **Compact:** tokens as a grid of 4 × 3 above the evaluator box, the device box below; vertical edges with markers.

### V13 Learning-rate schedule

- **Kind:** Plot with one line. In the mock, frames K1-320 and K1-1280.
- **Plot:** x the step, y the rate; series 1; the cursor shows the rate at a step that follows the pointer or, by keyboard, a fifth slider "step". With the checkbox "log y-axis" the y-axis has major ticks at powers of ten and minor ticks as in section 6.
- **Focusable:** nothing in the stage.
- **Controls:** presets (3), sliders for base rate, final rate, warm-up and steps, checkbox.
- **Compact:** the plan asks for 300 × 160; the plot area at 320 px is 262 px wide, so the view box is `0 0 260 160`. The sliders are stacked.

### V14 The hyperparameter story and the Elo ladder

- **Kind:** an HTML list with inline whiskers, and four panels that are figures of their own.
- **Staircase, wide:** a list of seven rows. Each row is a button: the level number, the level name, the example; in a second column of 280 px a shared Elo axis on which each row draws its dot and whisker, so the rows line up as one chart. The rows are indented by 12 px per level, which gives the staircase. A row without a measured effect shows a tag instead of a dot.
- **Panels:** each opens below the staircase as a full Figure. Elo curve: plot wide, x the score from 0.5 to 1, y Elo, the curve as series 1, the interval as a band, cursor at the chosen score, two reference lines at 0.82 and 0.91. Sweeps: three small plots in a row (plot compact each), dot and whisker. Futility grid: a real `<table>`; each cell has a heat disc of the sequential ramp and the number; the chosen cell has a 2 px ink outline and a tag. Sweep 1: dot and whisker on a category axis A to H, a reference line at 0.5, the winner bold, the confirmation as a second point (series 2) next to it.
- **Focusable:** the seven rows; in the open panel its controls.
- **Controls:** segmented switch "measured / all" above the staircase; two sliders in the Elo panel.
- **Compact:** the staircase is a list of full-width rows; the whisker of a row is an inline SVG of 120 × 24 under its text, all on the same scale; the scale is written once above the list. The three sweep plots stack. The futility table becomes rows (the pattern of `.ml-table` below 44rem).

## 11. Component list

All classes are in the `<style>` block of `about-mockup.html`, under the comment `about component styles`.

| Component | Classes | Specified in |
|---|---|---|
| Topic card | `.ml-fig-hub*` | section 5, section 6 "Other page parts" |
| Topic page, breadcrumb, On this page, section | `.ml-fig-topic*`, `.ml-fig-crumb`, `.ml-fig-toc*`, `.ml-fig-section*` | section 5 |
| Formula, inline code | `.ml-fig-math`, `.ml-fig-code` | section 4 |
| Glossary term, popover | `.ml-fig-term`, `.ml-fig-pop*` | section 6 |
| Source chip, data chip | `.ml-fig-chip*` | section 6 |
| Reading list, previous and next | `.ml-fig-reading*`, `.ml-fig-pager*` | section 6 |
| Figure | `.ml-fig`, `.ml-fig__*` | section 6 |
| Manipulate | `.ml-fig__panel`, `.ml-fig-ctl*`, `.ml-fig-scale`, `.ml-fig-stepper*`, `.ml-fig-seg*`, `.ml-fig-presets`, `.ml-fig-check*`, `.ml-fig-select`, `.ml-fig-readout*` | section 6 |
| Legend | `.ml-fig-legend*` | section 6, Plot |
| Plot | `.ml-fig-plot*` | section 6 |
| MiniBoard, HeatBoard | `.ml-fig-board*`, `.ml-fig-heat*` | section 6 |
| BoxDiagram | `.ml-fig-box*`, `.ml-fig-edge*`, `.ml-fig-num*` | sections 6 and 7 |
| IcicleTree | `.ml-fig-ice*` | section 6 |

Existing classes that the kit reuses unchanged: `.ml-card`, `.ml-card__title`, `.ml-kicker`, `.ml-tag`, `.ml-btn`, `.ml-icon`, `.ml-slider`, `.ml-progress`, `.ml-alert`, `.ml-prose`, `.ml-page`, `.ml-data`, `.ml-visually-hidden`, `.ml-socket`, `.ml-cellfocus`, and the piece symbols.

## 12. Placeholder keys

Text in square brackets in the mock is a catalog key, for example `[fig.ab.caption]`. The grey bars next to a key stand for the length of the running text. To list the keys:

```sh
perl -ne 'print "$1\n" while /\[((?:kit|fig|term|sec|toc|read|about_learn)[\w.\-]*|title|lead)\]/g' web/design/about/about-mockup.html | sort -u
```

The command prints 173 keys for the mock of 2026-11-21. Keys that the plan does not list are in `keys-requested.md`.

Data, not copy: names of technologies and classes (`WorkerEngine`, TeaVM), paths, file names, numbers, units, move notation and cell names. The numbers in the plots K1 to K4 are the values quoted in the briefs of the plan and its fact sheets (learning-rate preset of generation 0, depth per node budget, the `c` sweep, evaluations per second). The heat values of frame K5 and the reply counts of frame K7 are layout samples, not data. The final figures take every number from `web/src/about/data/`.

## 13. Deviations from the plan and open points

Deviations, each with its reason in the section named:

1. **Figure breakpoint at 40rem of figure width** instead of the app breakpoint of 60rem for the compact layout of the box diagram (section 5).
2. **Dimmed parts change colour tokens** instead of going to 40 % opacity (section 3).
3. **The active edge is never dashed** unless it is a control edge (section 7).
4. **Dots with leaders** instead of bars on a log axis (section 6, Plot).
5. **The y-axis label is horizontal** above the plot (section 2).
6. **Tokens in their own file** `tokens-about.css`, not as a patch to `tokens.css`, as the task for this design asked. The contrast test of the plan must read both files.
7. **Placeholders in square brackets** instead of the `{{t:key}}` form of the app mock, as the task for this design asked.
8. **The icicle is the tree form at every width** for V5 and V10, not only at 320 px. Rationale: a node-link tree of 14 or 54 children per node needs more width than the 736 px text column has.
9. **V10 columns have equal width** (section 10).

Open points:

1. **This text needs a second writer and a fresh reader.** The owner's `technical-writing` skill reserves documents for people to Opus and asks for a review by an agent without the session. Neither happened here.
2. **Class prefix of page parts.** The task asked for the prefix `ml-fig-` for every new class. The hub cards and the topic page are not figures; a prefix such as `ml-about-` would read better. Renaming is mechanical; decide before the work package that implements the hub.
3. **Per-figure frames.** The mock shows the kit. It shows V1, V5, V7, V13 and parts of V9a, V9b and V10 only as far as a kit component needed real structure. Frames for every figure at 320 px and 1280 px with the generated data are design task D2.
4. **The travelling dot** was not looked at in motion (section 9).
5. **Overlapping hit areas of glossary terms** (section 6) need a look on a real phone.
6. **Not verified:** Firefox and Safari, real touch devices, screen readers, forced-colours mode, a colour-vision simulation, MathML rendering outside Chromium, and German text lengths.
