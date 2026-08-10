# One UI parity matrix (v0.5.0)

Grades are `HIGH` / `MEDIUM` / `LOW` (how close the implemented behavior
is to the current One UI reference) plus a confidence qualifier —
`HIGH_CONFIDENCE` / `MEDIUM_CONFIDENCE` / `LOW_CONFIDENCE` — reflecting how
well-sourced the reference itself is (see
`docs/design/one-ui-current-reference.md`). No physical Samsung Galaxy
device or real One UI screenshots were available in this environment;
grades are based on documented/cited behavior plus ReLite's own measured
implementation, not a pixel-level side-by-side photo comparison. Where
that distinction matters it's called out explicitly.

| Surface | Parity | Confidence | Evidence |
|---|---|---|---|
| Home grid (4×6 / 5×6) | HIGH | HIGH_CONFIDENCE | Sourced official Samsung page confirms exactly these two grids in current One UI; ReLite implements both, selectable in Settings, migrates existing layouts without loss (`WorkspaceControllerTest`, live device). |
| Home geometry (margins/spacing) | LOW | LOW_CONFIDENCE | No measured Samsung reference available; ReLite uses its own pre-existing spacing constants, not measured One UI values. |
| Icon size control | LOW (not implemented) | — | `IconCache` is a single fixed-size shared cache created once at process start; a runtime-adjustable size needs a real architecture change not attempted this pass. |
| App labels toggle | HIGH | MEDIUM_CONFIDENCE | Real, wired toggle in Settings, applied in `HomePageFragment`; verified live via `HomeSettingsInstrumentationTest`. Documented behavior (labels exist and are togglable), not a measured Samsung typography match. |
| Widget labels toggle | LOW (not implemented) | — | No widget label UI exists in ReLite to toggle at all. |
| Dock | MEDIUM | LOW_CONFIDENCE | Existing rounded pill dock, unchanged visually this pass; Apps-button now optionally hidden (Settings toggle), default kept on since the Home↔Apps swipe substitute isn't implemented. |
| Page indicator | MEDIUM | LOW_CONFIDENCE | Existing dot indicator, unchanged geometry this pass. |
| Page management (add/remove/reorder/default) | HIGH (add/remove/default) / LOW (reorder) | MEDIUM_CONFIDENCE | Real Home edit-mode page strip: add, remove-if-empty, set-default all live and tested. Page *reordering* is not implemented — pages can only be appended/removed, not dragged into a new order. |
| Home edit mode | MEDIUM | MEDIUM_CONFIDENCE | Real overlay (scale-down + page strip + Wallpaper and style/Widgets/Home Settings), replacing the old AlertDialog — verified live with screenshots and `EditModeInstrumentationTest`. Page thumbnails are numbered chips, not rendered page content. |
| Apps screen Custom order | HIGH | MEDIUM_CONFIDENCE | Horizontal paged grid, drag-reorderable, persisted — matches the sourced axis description. Verified live with a screenshot and JVM tests (`AppSearchTest.customOrder`). |
| Apps screen Alphabetical order | HIGH | HIGH_CONFIDENCE | Vertical grid, always alphabetical — verified live with a screenshot. |
| Apps screen bottom search | HIGH | HIGH_CONFIDENCE | Sourced official reporting confirms One UI 7 moved search to the bottom; ReLite's search bar is now bottom-anchored with real gesture-nav inset handling (a real bug — Espresso couldn't focus the field until this was fixed — caught and fixed live on-device). |
| Sort chooser / More menu | HIGH | MEDIUM_CONFIDENCE | Real Sort/Home-Settings menu, reachable from both the Apps screen and Settings; verified live with a screenshot. |
| Apps folders (Custom mode) | LOW (not implemented) | — | No folder support inside the Apps screen's Custom order — only Home folders exist. |
| Folder compact preview | HIGH | LOW_CONFIDENCE | Live 2×2 icon collage (`FolderPreview`), shipped in v0.4.1, unchanged. Exact Samsung visual match not independently verified. |
| Expanded folder | LOW (not implemented) | — | No separate expanded/enlarged folder view — `FolderSheetDialog`'s existing full editor remains the only way to view/edit a folder's members. |
| Drag-app-onto-app folder creation | LOW (not implemented) | — | Folder creation is menu-driven only (`FolderPicker`); no drag-onto-app gesture. |
| Context menus | HIGH | MEDIUM_CONFIDENCE | Real `LauncherContextMenu` (rounded card, data-driven `LauncherAction`) replaces the system `PopupMenu` at every long-press site — verified live with a screenshot. |
| Widget picker | LOW | LOW_CONFIDENCE | Still a plain list (populated correctly — the v0.4.1 fix — but no preview images/grouping by app, no explicit Preview→Add step). |
| Widget resize | MEDIUM | MEDIUM_CONFIDENCE | Real measured (non-square) `GridMetrics` now drive both initial span and resize notification — a real correctness fix this pass — but the resize UI itself is still +/- buttons, not drag handles. |
| Widget drag/edit mode | LOW (not implemented) | — | Widgets still move only via "Move to page…"; no in-place drag or dedicated edit wrapper. |
| Portable widget descriptors/rebind | LOW (not implemented) | — | Export still drops widgets entirely (as in v0.4.1); no provider+position+span portable descriptor or rebind-on-import flow. |
| Light theme | MEDIUM | LOW_CONFIDENCE | Existing System/Light/Dark picker, unchanged palette this pass. |
| Dark theme | MEDIUM | LOW_CONFIDENCE | Same as above. |
| Motion | LOW | LOW_CONFIDENCE | Edit-mode enter/exit uses a simple 200ms scale animation; no shared motion-token system, no gesture-following Home↔Apps transition. |
| Haptics | LOW (not implemented) | — | No haptic feedback wired into any gesture this pass. |
| Accessibility | MEDIUM | MEDIUM_CONFIDENCE | Existing 48dp targets and non-drag alternatives preserved; new surfaces (edit mode, context menu, Apps sort) were not run through a dedicated TalkBack/font-scale pass this session. |

## Honest summary

Primary surfaces that reached **HIGH** parity this pass: the Home grid
system, both Apps screen sort modes, bottom search, and the new context
menu — all verified against real sourced Samsung behavior *and* live on
the physical RMX5303, not just compiled. Everything graded **LOW** above is
explicitly not implemented, not partially faked — see CHANGELOG's Known
gaps for the full list and why each was deferred.
