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

**This matrix was re-synced after the v0.5.0 completion passes.** Several
rows below previously read `LOW (not implemented)` and had genuinely been
implemented since; several others are newly closed by the final pass
(widget drag/resize edit mode, portable widget descriptors + rebind,
shared motion/haptics, the accessibility sweep). The ceiling section at
the end states plainly what parity here can and cannot mean.

| Surface | Parity | Confidence | Evidence |
|---|---|---|---|
| Home grid (4×6 / 5×6) | HIGH | HIGH_CONFIDENCE | Sourced official Samsung page confirms exactly these two grids in current One UI; ReLite implements both, selectable in Settings, migrates existing layouts without loss (`WorkspaceControllerTest`, live device). |
| Home geometry (side margins) | HIGH | HIGH_CONFIDENCE | Samsung's published One UI Design Guidelines specify a **24dp minimum safe-area margin per side** (Architecture 04, p.14); applied as `screen_margin_horizontal` to the Home grid padding and dock margin. Sourced figure, not an approximation. |
| Home geometry (icon size, cell padding, dock height, indicator) | LOW | LOW_CONFIDENCE | The guide publishes no values for any of these; they remain ReLite's own constants and are not claimed as parity. |
| First-run home layout | HIGH | MEDIUM_CONFIDENCE | `DefaultLayout` seeds a filled dock (Phone/Messaging/Browser/Camera) and a populated page one on first run, resolved by intent role rather than package name. Previously Home opened completely empty, which no amount of correct geometry could make read as a phone home screen. Samsung publishes no default-layout spec, so the *contents* are ReLite's judgement; that a home screen ships populated is not in question. |
| Home label legibility | HIGH | HIGH_CONFIDENCE | Fixed white + drop shadow over the wallpaper, replacing theme-coloured labels that washed out entirely against a bright wallpaper on the RMX5303. |
| Page indicator | HIGH | MEDIUM_CONFIDENCE | Elongated active pill plus wallpaper-safe fixed colours, and now present on the Apps screen too (previously absent there entirely). Shape matches One UI; exact geometry unpublished. |
| App icon shape | HIGH | MEDIUM_CONFIDENCE | Every icon is clipped to one shared **squircle** (superellipse, cubic-Bézier), adaptive icons drawn from their own layers rather than through the system mask, legacy icons composited on a neutral plate. The guide describes the shape — "square backgrounds with smooth rounded corners" (Visual Design, Iconography) — but publishes no corner geometry, so the shape is sourced and its exact proportions are ReLite's. This was the largest single contributor to the launcher not reading as One UI. |
| Apps screen grid density | HIGH | HIGH_CONFIDENCE | A Custom-order page is a dense 4×6 (or 5×6). Previously the horizontal grid's span count was passed the *column* count, which in horizontal orientation sets rows — giving 4 sparse rows per page instead of 6. |
| Corner radius scale | HIGH | HIGH_CONFIDENCE | The guide's thumbnail-radius scale — **26/20/12dp** (Visual Design 04, p.67) — implemented as `radius_large`/`radius_medium`/`radius_small` and applied to dock+folder / menu card / icons. The scale is sourced; which step maps to which surface is ReLite's judgement, as the guide gives no such rule. |
| Icon size control | HIGH | MEDIUM_CONFIDENCE | `IconSizePreference` (Small/Default/Large) plus a size-aware `IconCache` keyed on `"$pkg/$activity@$sizePx"` and an `IconNormalizer`. Covered by `IconSizePreferenceTest`/`IconNormalizerTest`. |
| App labels toggle | HIGH | MEDIUM_CONFIDENCE | Real, wired toggle in Settings, applied in `HomePageFragment`; verified live via `HomeSettingsInstrumentationTest`. Documented behavior, not a measured Samsung typography match. |
| Widget labels toggle | HIGH | LOW_CONFIDENCE | Opt-in "Show widget labels" overlay (off by default), wired and persisted; `HomeSettingsInstrumentationTest.theWidgetLabelsToggleReflectsAndPersistsThePreference`. Exact Samsung presentation not independently sourced. |
| Dock | MEDIUM | LOW_CONFIDENCE | Existing rounded pill dock; Apps-button optionally hidden (Settings toggle). Geometry not measured against a Samsung reference. |
| Page indicator | MEDIUM | LOW_CONFIDENCE | Existing dot indicator, unchanged geometry. |
| Page management (add/remove/reorder/default) | HIGH | MEDIUM_CONFIDENCE | Edit-mode page strip: add, remove-if-empty, set-default, and drag reorder (`WorkspaceController.reorderPages`) all live, with an accessible "Move page left/right" menu fallback alongside the drag. |
| Home edit mode | HIGH | MEDIUM_CONFIDENCE | Real overlay (scale-down + page strip + Wallpaper and style/Widgets/Home Settings), with page thumbnails rendering actual page content (`EditModePageThumbnailView`), not numbered chips. Enter/exit now runs on the shared motion tokens with a coordinated overlay fade and a long-press haptic. `EditModeInstrumentationTest`. |
| Apps screen Custom order | HIGH | MEDIUM_CONFIDENCE | Horizontal paged grid, drag-reorderable, persisted — matches the sourced axis description. `AppSearchTest.customOrder` + live screenshot. |
| Apps screen Alphabetical order | HIGH | HIGH_CONFIDENCE | Vertical grid, always alphabetical — verified live. |
| Apps screen bottom search | HIGH | HIGH_CONFIDENCE | Sourced official reporting confirms One UI 7 moved search to the bottom; ReLite's search bar is bottom-anchored with real gesture-nav inset handling (a real bug caught and fixed live on-device). |
| Sort chooser / More menu | HIGH | MEDIUM_CONFIDENCE | Real Sort/Home-Settings menu, reachable from both the Apps screen and Settings. |
| Apps folders (Custom mode) | HIGH | LOW_CONFIDENCE | `DrawerFolder` persisted in `AppsPreference`, occupying a slot in Custom order, created via a menu-driven "Add to Apps folder" flow. Menu-driven rather than drag-merge, since Custom order's own long-press already starts a reorder drag. |
| Folder compact preview | HIGH | LOW_CONFIDENCE | Live 2×2 icon collage (`FolderPreview`). Exact Samsung visual match not independently verified. |
| Expanded folder | HIGH | LOW_CONFIDENCE | Real expanded 2×2 folder with member icons and direct launch; Enlarge/Shrink context-menu actions. `ExpandedFolderInstrumentationTest`. Exact span is `LOW_CONFIDENCE` — no citable Samsung source found for it (see the reference doc's Folders section). |
| Drag-app-onto-app folder creation | HIGH | MEDIUM_CONFIDENCE | Implemented with drag-hover scale feedback; `HomeDragToFolderInstrumentationTest`. |
| Dialogs | HIGH | HIGH_CONFIDENCE | All 16 `AlertDialog`s bottom-anchored at full width on a rounded surface via a shared `showOneUi()`, per Component 07 (p.37 "provide a dialog pop-up, which requires a user action, at the bottom"; p.40 phone min width 100%). The guide's centred carve-out is for non-actionable "Loading…" dialogs, which this app has none of. |
| Settings focus blocks | HIGH | HIGH_CONFIDENCE | Each settings group is a rounded card contrasting against the background, per Architecture 01 (p.8), with its header outside and above it. |
| Button/label capitalization | HIGH | HIGH_CONFIDENCE | `textAllCaps=false` across settings rows — Android's default borderless button shouts in caps, while Visual Design 03 (p.65) specifies capitalizing only the first letter of words/sentences. |
| Edit-mode scrim behaviour | HIGH | HIGH_CONFIDENCE | Tapping the dimmed area exits edit mode, as Component 15 (p.53) requires ("touching the darker (dimmed) area should function the same way as the Back key"). Verified against the guide; was already compliant. |
| Toast usage | HIGH | HIGH_CONFIDENCE | No toast carries a dismiss-style action button, which Component 13 (p.50) explicitly forbids ("do not provide Action buttons to close pop-ups, such as Dismiss, Close, Done, OK"). Verified; already compliant. |
| Context menus | HIGH | HIGH_CONFIDENCE | Component 07 (p.39) specifies a contextual menu on tap-and-hold of a list/grid item, "as a dropdown menu without a title" — exactly `LauncherContextMenu`'s shape (rounded card, data-driven `LauncherAction`, anchor-pivoted grow-and-fade, long-press haptic). Now also used for the Apps screen's click-opened More/Sort menus, which were still system dialogs, and edge-clamped so it never opens off-screen. |
| Widget picker | HIGH | MEDIUM_CONFIDENCE | Grouped by source app with real preview-image cards (provider preview API + icon/dimension fallback), over the full allocate → bind → configure → place pipeline with a complete cancellation matrix. |
| Widget resize | HIGH | MEDIUM_CONFIDENCE | Real drag-to-resize via a corner handle on `WidgetEditOverlayView`, grid-snapped against measured non-square `GridMetrics`, with a per-snap haptic tick. The +/- dialog remains as the accessible non-drag path. |
| Widget drag/edit mode | HIGH | MEDIUM_CONFIDENCE | Long-press → "Edit" opens a real edit overlay with live drag-to-move, drag-to-resize, Done and Remove. Implemented as a snapshot overlay because an `AppWidgetHostView` owns its own touch input — see `WidgetEditOverlayView`'s kdoc. A real `StackOverflowError` (re-entrant `ACTION_CANCEL` on self-removal) was found and fixed live on the RMX5303. |
| Portable widget descriptors/rebind | MEDIUM | HIGH_CONFIDENCE | Export now carries `PortableWidget` descriptors (provider + position + span) instead of dropping widgets; import rebinds via `WidgetRestorer` — silent where permitted, otherwise a per-widget system consent queue — and reports every outcome itemised. Graded MEDIUM, not HIGH, for two reasons that are platform limits rather than gaps: a rebind necessarily allocates a fresh id, so **configured widgets return unconfigured**, and `BIND_APPWIDGET` is a `signature`/`privileged` permission, so a third-party launcher can never restore silently the way Samsung's own privileged Smart Switch path does. Covered by `WidgetRestoreInstrumentationTest` (7 live tests) + `WorkspaceRepositoryTest`. |
| Accent color | HIGH | HIGH_CONFIDENCE | The guide's published palette — Primary `#0381fe`, Primary dark `#0072de` light / `#3e91ff` dark, Color control activated `#3e91ff` (Visual Design 02, p.62-63) — adopted including the light/dark split, and wired to `colorControlActivated` so switches tint correctly (they were previously drawing in AppCompat's default accent). |
| Light theme (neutrals) | MEDIUM | LOW_CONFIDENCE | Accent is now sourced, but the guide gives backgrounds only as "simple, calm colors" with no hex values, so ReLite's neutrals remain its own and aren't claimed as a match. |
| Dark theme (neutrals) | MEDIUM | LOW_CONFIDENCE | Same as above. |
| Typography | HIGH | HIGH_CONFIDENCE | The guide states One UI's default font is **Roboto** (Visual Design 03, p.66), which is Android's own system default and what this app already renders in — it bundles no font. Exact match with nothing of Samsung's shipped. The guide's capitalization rule is ambiguously worded and its examples are images; ReLite's existing sentence case matches observed One UI menus and was left alone rather than changed on a guess. |
| Motion | MEDIUM | LOW_CONFIDENCE | `MotionTokens` (durations + standard/emphasized curves + a shared `popIn`) now drives the Home↔Apps swipe settle, folder drag-hover, edit-mode enter/exit, context-menu appear, and folder open. Curves are ReLite's own approximation of publicly described motion shapes, not extracted Samsung values — so the *system* is real but the *match* is unverified. |
| Haptics | MEDIUM | LOW_CONFIDENCE | `LONG_PRESS` on drag pickup, context-menu open and edit-mode entry; `KEYBOARD_TAP` per grid snap during widget move/resize. No sourced Samsung haptic reference to grade the match against. |
| Accessibility | MEDIUM | MEDIUM_CONFIDENCE | 48dp targets and a non-drag alternative for every drag gesture. This pass converted fixed-height text rows to `minHeight` (context-menu rows, drawer search field, picker empty-state button) so they grow with the system font scale instead of clipping, and labelled/hid the widget-edit overlay's nodes for TalkBack. Not yet run through a full TalkBack navigation audit of every surface. |

## The ceiling: what "like One UI" can and cannot mean here

Two hard limits bound every grade above, and neither is a to-do item.

1. **ReLite ships no Samsung assets.** No Samsung icons, fonts,
   wallpapers, or artwork, and nothing extracted from a device or
   firmware. That remains absolute.

   What changed in the visual pass: a small set of numeric values —
   accent colors, the 24dp margin, the 26/20/12dp radius scale — *are*
   now taken from Samsung's **openly published** One UI Design
   Guidelines, a document distributed for third-party developers building
   apps that fit the One UI look. `NOTICE.md` records exactly which
   values and from which pages. The rows above graded `HIGH` on those
   points are graded that way because the figure is sourced, not
   approximated.

2. **Whatever the guide doesn't publish still cannot be matched.** It
   gives no numbers for motion durations or easing, haptic patterns, icon
   size, cell padding, dock height, page-indicator geometry, or
   background/surface colors. Those remain ReLite's own and are graded
   `LOW`/`MEDIUM` accordingly. A physical Galaxy handset would be needed
   to measure them, and none was available.

So the ceiling is no longer "nothing is measurable" — it is "everything
Samsung publishes is now implemented; the rest would need a device to
measure." Going further than the published guide, into replicating
Samsung's actual assets, is a product and legal decision rather than an
engineering one, and remains out of scope.

## Honest summary

Every surface graded `LOW (not implemented)` in the previous revision of
this matrix is now implemented and covered by tests that run on the
physical RMX5303. What remains below `HIGH` is there for one of three
stated reasons: a platform limit no third-party launcher can cross
(widget rebind consent and lost configuration), an unmeasurable reference
(geometry, motion curves, haptics), or a deliberate non-goal (copying
Samsung's palette and assets). Nothing above is graded on intent — a row
reads `HIGH` only where the behavior exists, runs, and is tested.
