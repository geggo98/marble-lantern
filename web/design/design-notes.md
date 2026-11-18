# Design notes: marble-lantern web UI

**TL;DR:** This directory holds the visual design of the marble-lantern web UI: a two-player game on a 121-cell star board, played against a game engine in the browser.
`tokens.css` defines every colour, type, space and motion value for a light and a dark theme.
`mockup.html` shows every screen and state as static HTML; its `<style>` block is the reference for the component styles.
`board-mock.svg` is one board position as a standalone picture.
The two sides differ in colour (amber and blue) and in shape (plain marble and ringed marble), so the board stays readable without colour vision.
All texts in the mock are placeholders of the form `{{t:key}}`; nobody has written the user-facing copy yet.
The mock was checked in headless Chromium at 360, 390 and 1280 px width, light and dark. It was not checked in Firefox or Safari, on a real phone, with a screen reader, or with a colour-vision simulator.

Date: 2026-11-18. Author: the design agent (Claude Fable 5.2), on the owner's request. The text of this file has not had an independent review yet.

## Contents

1. [Files](#1-files)
2. [Visual language](#2-visual-language)
3. [Colour and contrast](#3-colour-and-contrast)
4. [Typography](#4-typography)
5. [Layout](#5-layout)
6. [The board](#6-the-board)
7. [Input: pointer, touch, keyboard, screen reader](#7-input-pointer-touch-keyboard-screen-reader)
8. [Motion](#8-motion)
9. [Screens and states](#9-screens-and-states)
10. [Component list](#10-component-list)
11. [Placeholder keys](#11-placeholder-keys)
12. [Open points](#12-open-points)

## 1. Files

| File | Content | How to use it |
|---|---|---|
| `tokens.css` | CSS custom properties with the prefix `--ml-`. Light values on `:root`; dark values under `prefers-color-scheme: dark` and under `:root[data-theme="dark"]`. | Import it unchanged. The two dark blocks must stay equal. |
| `mockup.html` | One self-contained page. Sections: 01 play screen (live, responsive), 02 play states S1 to S8, 03 errors E1 to E4, 04 engine sheet P1 and P2, 05 benchmark, 06 about, 07 specimens. | Open it in a browser. The bar at the top switches the theme; `?theme=dark` in the URL does the same. Classes with the prefix `ml-` are the product; classes with the prefix `mock-` are only the frame of the mock. |
| `board-mock.svg` | The board with a selected piece, its targets and a path preview. It carries its own colours and follows `prefers-color-scheme`. | Reference for the board layers. Also usable as a picture in documentation. |
| `design-notes.md` | This file. | |

The subdirectory `logo/` belongs to the logo work, not to this design.

## 2. Visual language

The name gives the picture: glass marbles on a plate that glows like a paper lantern.

- **Warm, quiet surfaces.** The page is warm paper in the light theme and warm near-black in the dark theme. The board plate has a soft radial light in its centre (`--ml-board-glow`). In the dark theme this light is what makes the board look like a lantern.
- **Colour belongs to the pieces.** The interface is ink on paper: buttons, selection rings, target marks and progress bars use the text colour. Amber and blue appear only where a side is meant. The only other hues are the focus ring (violet), errors (red) and the "ok" mark (green).
- **The board is drawn from the cells.** The plate is the union of the two large triangles that define the board in `docs/rules.md` (section "Board and coordinates"), drawn with a wide round stroke. Every cell is a round socket. No part of the board copies a manufacturer's artwork.
- **The technology is visible in small type.** Engine name, runtime, binary size, nodes, milliseconds and depth appear in a monospace face in small cards. They never compete with the board.
- **No game name in the brand.** The header shows the working title `marble-lantern` and a placeholder mark (a hexagon with a marble). The final mark replaces `#ml-mark` when the logo exists.

## 3. Colour and contrast

All ratios are WCAG 2 contrast ratios, computed from the hex values in `tokens.css` on 2026-11-18. Text needs 4.5:1, graphical parts need 3:1 (WCAG 2.2, level AA).

| Pair | Light | Dark | Needed |
|---|---|---|---|
| `--ml-color-ink` on `--ml-color-bg` | 15.1 | 15.9 | 4.5 |
| `--ml-color-ink-muted` on `--ml-color-bg` | 6.8 | 8.5 | 4.5 |
| `--ml-color-ink-muted` on `--ml-color-surface` | 7.6 | 7.8 | 4.5 |
| `--ml-color-ink-muted` on `--ml-color-surface-sunken` | 6.1 | 8.8 | 4.5 |
| `--ml-color-on-primary` on `--ml-color-primary` | 16.8 | 15.9 | 4.5 |
| `--ml-color-line-strong` on `--ml-color-bg` (control borders) | 3.7 | 4.0 | 3 |
| `--ml-color-focus` on `--ml-color-bg` | 6.6 | 9.6 | 3 |
| `--ml-color-focus` on `--ml-board-star` | 5.5 | 8.2 | 3 |
| `--ml-color-danger` on `--ml-color-danger-bg` | 6.3 | 7.9 | 4.5 |
| `--ml-color-ok` on `--ml-color-surface` | 7.0 | 10.0 | 4.5 |
| `--ml-board-socket-edge` on `--ml-board-star` | 3.3 | 3.2 | 3 |
| `--ml-board-select` on `--ml-board-star` | 12.6 | 13.6 | 3 |
| `--ml-piece-a-edge` on `--ml-board-star` | 6.5 | 11.9 | 3 |
| `--ml-piece-b-edge` on `--ml-board-star` | 10.4 | 10.4 | 3 |
| `--ml-piece-b-mark` on `--ml-piece-b` (the ring on the marble) | 5.6 | 3.9 | 3 |
| `--ml-piece-a-text` on `--ml-color-bg` | 6.6 | 10.5 | 4.5 |
| `--ml-piece-b-text` on `--ml-color-bg` | 7.0 | 9.1 | 4.5 |

One pair is below 3:1 on purpose: the amber body `--ml-piece-a` on the light plate has 1.7:1. The dark outline `--ml-piece-a-edge` (6.5:1) carries the shape. Do not remove the outline of a piece.

### The two sides

| | Side A | Side B |
|---|---|---|
| Colour | amber | blue |
| Shape cue | plain marble | marble with a light ring (`.ml-piece__mark`) |
| Goal sockets | coloured edge, no inner mark | coloured edge and an inner ring (`.ml-socket__mark-b`) |
| Token prefix | `--ml-piece-a` | `--ml-piece-b` |

Rule: every place that shows a side shows the marble itself (`.ml-swatch`), never a bare colour patch.
Rationale: orange and blue are the pair that stays apart under red-green colour-vision deficiency, which is the common kind; the ring keeps the sides apart with no colour vision at all and in a greyscale print. The lightness also differs: amber to blue has a contrast of 2.5:1 (light) and 2.1:1 (dark).
Limit: the palette was chosen by reasoning. Nobody has run it through a colour-vision simulator.

"Side A" and "side B" are design names. In the mock, side A is the human at the bottom and side B is the engine at the top. Which engine player each side is depends on the game setup; see section 6.

## 4. Typography

Only system fonts. No font file ships and no request leaves the page.

| Token | Stack starts with | Used for |
|---|---|---|
| `--ml-font-ui` | `system-ui` | all interface text |
| `--ml-font-display` | `ui-serif`, Iowan Old Style, Palatino, Georgia | the wordmark, card titles, screen titles, the result headline |
| `--ml-font-data` | `ui-monospace`, SF Mono, Cascadia Mono, Menlo | numbers, move notation, tags, error codes |

Sizes run from `--ml-text-xs` (12 px) to `--ml-text-2xl` (28 px). Body text is 16 px. Nothing is smaller than 12 px, and 12 px is used only for tags, column heads and units.
Numbers use `font-variant-numeric: tabular-nums` so that live figures do not jump.
Format numbers with `Intl.NumberFormat` for the active language. The mock shows the English form (`20,000`).
The small upper-case labels (`.ml-kicker`) use CSS `text-transform`. The source text stays in normal case.

## 5. Layout

There is one breakpoint: 60rem (960 px) of app width. The mock uses a container query on `.ml-app`, so the phone frames in the mock show the narrow layout on a wide screen. A media query with the same value gives the same result in the product.

**Below 60rem (phone).** One column in this order: header (brand, mode badge, language switch; the three navigation tabs in a second row), status bar, board, notice (loading, result or error), controls, engine card, figures of the last engine move, move list, footer. The board reaches 8 px into each side gutter, which leaves it 344 px at a 360 px viewport.

**From 60rem (desktop).** The header is one row. The play screen has two columns: status bar and board on the left; notice, controls, engine card, figures and move list in a 24rem column on the right. The board never grows taller than the viewport minus 11rem, so status bar and board fit on one screen without scrolling.

The order in the DOM is the same in both layouts. Nothing is reordered with CSS, so the tab order always follows the visual order.

Measured sizes of the board on a phone:

| Viewport | Board width | Distance between cell centres | Piece diameter |
|---|---|---|---|
| 360 px | 344 px | 25.5 px | 19.8 px |
| 390 px | 374 px | 27.7 px | 21.6 px |

No screen scrolls sideways at 360 px. With the placeholder `{{t:offline}}` the header tools wrap to a second row at 360 px; the real word is shorter and fits.

German texts are often about 30 % longer. The mock placeholders are longer than that, and every label may wrap (`overflow-wrap: anywhere` on `.ml-app`). Buttons grow in height; they never clip text.

## 6. The board

### Geometry

`viewBox="-234 -266 468 532"`. A cell `(q, r)` has its centre at `x = √3 · 20 · (q + r/2)`, `y = 30 · r`. This is the pointy-top layout of `docs/rules.md`, section "Screen orientation and clockwise order", with a unit of 20. A socket has radius 11.5, a piece 13.5. The mock computes its cells from the two conditions in `docs/rules.md`; the product takes them from the generated cell list.

### Orientation

The human's start corner is at the bottom, so the human moves up the screen. If the human plays the engine's top player, rotate the board by 180 degrees: negate `x` and `y` of every cell. Cell names (below) do not change when the board rotates.
In engine-versus-engine demo mode the board keeps the orientation it had.

### Cell names

Rows are lettered A to Q from the top of the unrotated board (row letter = `r + 8`). Cells in a row are numbered from 1, in rising `q`. The top tip is `A1`, the bottom tip is `Q1`.
The move list writes a single step with `›` (for example `E6›F6`) and a jump or a jump chain with `»` (for example `C3»E7`). The sample game in the mock happens to contain only jumps. The accessible name of a cell is the cell name plus its content, for example "K5, own piece, selected".
Rationale: `(q, r)` pairs with negative numbers are hard to read aloud and to compare in a list. Limit: this notation is a design proposal. If the project prefers `(q, r)`, only the formatting function changes.

### Layers, bottom to top

| Layer | Class | Content |
|---|---|---|
| 1 | `.ml-board__plate` | Two triangles, drawn twice: a wider stroke in the edge colour, then fill and stroke in the plate gradient. The round stroke gives the padded, rounded star. |
| 2 | `.ml-board__sockets` | 121 circles `.ml-socket`. Goal cells of side A add `.ml-socket--goal-a`; goal cells of side B add `.ml-socket--goal-b` and an inner ring `.ml-socket__mark-b`. The four side corners look like every other empty cell. |
| 3 | `.ml-trail` | The last move: dashed ring on the origin, a line through every landing cell, a dot on each landing between origin and destination, a ring around the destination. `.ml-trail--a` or `.ml-trail--b` colours it for the side that moved. |
| 4 | `.ml-targets` | A ring and a centre dot on every cell the selected piece can reach. |
| 5 | `.ml-preview__line` | Dotted path to the target under the pointer or the keyboard focus. |
| 6 | `.ml-halo` | Dotted ring around the pieces that fill the goal, after a win. |
| 7 | `.ml-pieces` | `<use href="#ml-piece-a">` or `#ml-piece-b`. The selected piece is scaled by 1.12. |
| 8 | `.ml-preview__ghost` | The moving piece at 55 % opacity on the previewed target. |
| 9 | `.ml-select`, `.ml-illegal`, `.ml-cellfocus` | Selection ring, illegal-click mark, keyboard focus ring. |

A goal socket shows which side must fill it: the bottom corner in the mock carries the rings of side B, because side B travels there. At the start, each side therefore sits on the other side's marked sockets.

The trail hides when a piece is selected, and returns when the selection is cleared. Rationale: trail, targets and preview together make the board hard to read (seen in the first draft of the mock).

The gradients and the two piece definitions live once per page in a zero-size `<svg>` with `<defs>`. Keep that element rendered with zero size, as the mock does. Hiding it with `display: none` can stop referenced gradients from painting in some browsers; this was not tested here.

## 7. Input: pointer, touch, keyboard, screen reader

### Pointer and touch

Cells on a phone are about 26 px apart, less than a comfortable touch target. The board therefore resolves a tap to the nearest *candidate*, not to the cell under the finger.

1. Candidates with no selection: the human's own pieces that have at least one legal move.
2. Candidates with a selection: the targets of the selected piece, and the human's other movable pieces (tapping one changes the selection).
3. A tap picks the nearest candidate whose centre is within 16 CSS px of the tap (a 32 px target). Where two candidates are closer than 32 px to each other, the nearer one wins.
4. A tap with a selection and no candidate in reach is an illegal click (state S4) if it lands on a cell, and clears the selection if it lands outside every cell. Tapping the selected piece also clears the selection.

Rationale: candidates are far fewer than cells, so a 32 px target around each fits where 121 such targets cannot.
Limit: on a pointer device with hover, the same rule applies; the preview (layer 5 and 8) shows which target a click would take.

### Keyboard

The board is one tab stop (roving `tabindex` over the cells).

| Key | Action |
|---|---|
| Left, Right | Next cell in the same row. |
| Up, Down | Nearest cell in the row above or below. The board remembers the `x` position where vertical movement started, so Up then Down returns to the same cell. |
| Home, End | First and last cell of the row. |
| Page Down, Page Up | With no selection: next and previous own movable piece. With a selection: next and previous target, ordered by row and then by `x`. |
| Enter, Space | On an own piece: select it. On a target: move. On any other cell with a selection: illegal click. |
| Escape | Clear the selection. |

The focused cell shows `.ml-cellfocus`: a violet ring with a thin gap in the plate colour, so that it stays visible on a piece of either colour. Buttons, tabs, options and the slider use the same violet as an outline with a 2 px offset.

### Screen reader

- Each cell is a button with an accessible name: cell name, content, and "target" or "selected" where it applies.
- One polite live region announces each completed move (side, origin, destination, number of jumps) and the game result. The status bar has `role="status"`.
- The illegal-click hint is also a `role="status"` text; it does not rely on the red mark.
- The engine options are a `role="radiogroup"`; an unavailable engine keeps its place, has `aria-disabled="true"` and shows its reason as text.

## 8. Motion

| What | Duration token | Behaviour |
|---|---|---|
| Hover, press | `--ml-dur-fast` (120 ms) | Background change; buttons move 1 px down while pressed. |
| Select a piece, open the sheet, fades | `--ml-dur-base` (200 ms) | Scale to 1.12; the sheet slides up from the bottom edge on a phone and fades in on desktop. |
| A move | `--ml-dur-hop` (180 ms) per hop | The piece travels along the jump path, one hop after the other, with `--ml-ease`. A chain of more than 6 hops shortens each hop so that the whole move takes at most 1.1 s. |
| Engine thinking | `--ml-dur-think` (1.4 s per cycle) | A bar runs along the lower edge of the status bar and the marble in the status bar pulses. |
| Illegal click | 240 ms, once | The mark nudges 3 px left and right, stays 1.2 s, then fades. |

With `prefers-reduced-motion: reduce`, `tokens.css` sets all four duration tokens to 0. The piece then appears on its destination at once, and the trail (layer 3) shows where it came from. The thinking bar becomes a static dashed line, the spinner a static dotted ring, and the illegal-click mark appears without the nudge. No information exists only as motion.

## 9. Screens and states

The ID is the tag on the frame in `mockup.html`.

| ID | State | What to notice |
|---|---|---|
| 01 | Play screen, human to move, piece selected | The live layout. Resize the window across 960 px. |
| S1 | Engine loading, first visit | The board is visible and inert. The loading card lists the five phases (download with bytes, inflate, verify, start engine, load table) with one overall bar. Controls are disabled. |
| S2 | Ready, human to move | Trail of the last engine move; keyboard focus on a piece. |
| S3 | Piece selected | Targets and the preview of one target. |
| S4 | Illegal click | Red mark on the cell and a one-line hint under the board. No dialog. The selection stays. |
| S5 | Engine thinking | Status bar with the running bar; board inert; figures of the previous move dimmed (`data-stale="true"`). Undo stays enabled and cancels the search. |
| S6 | Engine moved from the race table | The source tag is filled (`.ml-tag--solid`); depth shows an en dash. |
| S7 | Game over, win | Result card with the winner's marble; halo on the goal pieces; board inert. |
| S8 | Game over at the 200-ply limit | Result card with both distances. The side that is ahead has an outline and a text marker. |
| E1 | Table checksum failed | Action: retry. |
| E2 | Wasm GC not supported | Actions: use the Java JS engine, use the C++ engine. |
| E3 | Engine crashed | Action: restart the engine. |
| E4 | Server unreachable (online build) | Actions: retry, use the C++ engine. |
| P1 | Engine and strength, bottom sheet | Offline build; the Java Wasm GC engine is unavailable with its reason. |
| P2 | Engine and strength, dialog | Online build with the server engines as a second group. |
| 05 | Benchmark | A run in progress above the results of an earlier run; the "copied" confirmation next to the copy button. |
| 06 | About | Shown with the online badge, to cover both badge forms. |
| 07 | Specimens | Every board mark at 2.7 times board scale, the button variants, a forced focus ring, badges and tags. |

Rules for the states:

- **A notice has one place.** Loading card, result card and error share the slot directly under the board (phone) or at the top of the side column (desktop). An error replaces the loading card or the engine card; it is never a dialog.
- **The controls never move.** New game, undo, swap sides and demo keep their place in every state. Unavailable ones are disabled, not removed. The result card therefore has no buttons of its own.
- **Strength** is a slider with five stops: 1,000; 5,000; 20,000 (default); 100,000; 500,000 nodes per move. The readout shows the level name and the number; `aria-valuetext` carries both. The line under the slider gives the expected time per move for the chosen engine.
- **The mode badge** shows a screen icon and an outline in the offline build, and a globe icon on a filled pill in the online build. Icon and fill differ, not only the word.
- **The theme switch** (follow system, light, dark) sits in the footer of every screen. The language switch sits in the header of every screen.

## 10. Component list

All class names are in the `<style>` block of `mockup.html`, under the comment `component styles`.

| Component | Class | Notes for the implementer |
|---|---|---|
| App shell | `.ml-app`, `.ml-header`, `.ml-brand`, `.ml-nav`, `.ml-footer` | `.ml-app` is the query container. The current tab has `aria-current="page"`. |
| Mode badge | `.ml-badge`, `.ml-badge--online` | |
| Segmented switch | `.ml-seg`, `.ml-seg__opt` | Used for language and theme. `aria-pressed` marks the active option. |
| Button | `.ml-btn`, `--primary`, `--quiet`, `--icon` | Minimum height 44 px. One primary button per view. |
| Icon | `.ml-icon` with `<use href="#i-…">` | 15 stroke icons on a 24 px grid, drawn for this project, in the `<defs>` of the mock. |
| Card, kicker, tag | `.ml-card`, `.ml-kicker`, `.ml-tag`, `.ml-tag--solid` | |
| Play layout | `.ml-play`, `.ml-play__stage`, `.ml-play__side` | |
| Status bar | `.ml-status`, `.ml-status__bar`, `.ml-swatch` | `data-state="thinking"` starts bar and pulse. Shows whose turn it is and the ply count out of 200. |
| Board | `.ml-board`, `.ml-board__svg` and the layer classes of section 6 | `.is-inert` while loading, thinking and after the game. |
| Controls | `.ml-controls` | Two columns in both layouts. |
| Engine card | `.ml-engine` | Engine name, language and runtime tags, strength; the button opens the sheet. |
| Move figures | `.ml-telemetry` | Nodes, time, depth, source. Two columns; four columns between 28rem and 60rem. |
| Move list | `.ml-moves` | A `<details>` element, open by default on desktop, closed by default on a phone. The list scrolls inside 14rem and is focusable. The latest move has `aria-current="true"`. |
| Loading card | `.ml-loading`, `.ml-progress`, `.ml-phases`, `.ml-phase`, `.ml-spinner` | `data-state` is `done`, `active` or `pending`. |
| Result card | `.ml-result`, `.ml-score` | |
| Error | `.ml-alert` | `role="alert"`. Shows title, text, the error code and one or two actions. |
| Hint line | `.ml-hint` | Illegal click, strength estimate, "copied". |
| Engine sheet | `.ml-sheet`, `.ml-sheet--dialog`, `.ml-fieldset`, `.ml-option`, `.ml-strength`, `.ml-slider` | Modal: focus moves into it, Escape closes it, focus returns to the button that opened it. Apply starts a new search state; see section 12. |
| Text screens | `.ml-page`, `.ml-prose`, `.ml-techlist`, `.ml-links` | |
| Benchmark | `.ml-bench__run`, `.ml-table`, `.ml-env` | Below 44rem each table row becomes a card; each cell repeats its column head from `data-label`. |

## 11. Placeholder keys

The mock contains 112 keys. To list them:

```sh
perl -ne 'print "$1\n" while /\{\{t:([\w.]+)\}\}/g' web/design/mockup.html | sort -u
```

(`key` in that output is the explanation in the mock itself, not a text.) The keys are working names. The implementers may rename them when they define the real i18n keys. Keys that are not visible text but accessible names: `board_label`, `nav_label`, `language`, `theme`, `theme_auto`, `theme_light`, `theme_dark`, `close`, `last_engine_move`, `bench_progress`, `bench_caption`, `distance_heading`.

Names of technologies (TornadoVM, TeaVM, Emscripten, Vaadin Hilla, React, C++, Java, Wasm, Wasm GC, JS, JVM), the language codes DE and EN, units and all figures are data, not copy. All figures in the mock are sample values.

## 12. Open points

1. **Location of this directory.** The project plan names `design/` at the repository root with `spec.md` and `prototype/`. The task for this design named `web/design/` with the four files above. Someone must pick one and move the files if needed.
2. **Logo.** The mark in the header is a placeholder. Replace the content of `#ml-mark` (32 × 32 units) when the logo exists.
3. **Cell notation** (section 6) and **goal marking** (section 6, layers) are design proposals that the owner has not seen.
4. **Changing the engine during a game.** The design assumes that "apply" in the engine sheet keeps the game and uses the new engine from the next engine move. The project plan does not state this.
5. **Stop button during thinking.** The design has none; undo cancels the search. If a search at the highest strength on the slowest engine proves too long, add a stop action to the status bar.
6. **A zoom mode for the board** was considered and left out, because nearest-candidate tapping (section 7) reaches 32 px targets without it. Revisit this if tests on a real phone show wrong selections.
7. **Not verified:** Firefox and Safari rendering, real touch devices, screen readers, a colour-vision simulation, and forced-colours mode.
