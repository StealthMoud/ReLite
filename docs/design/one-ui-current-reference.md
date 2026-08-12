# One UI current reference (v0.5.0)

Working reference for the v0.5.0 "feels immediately familiar to a modern
Samsung Galaxy / One UI user" redesign (master plan Phase G, sections
63-65). ReLite Home is **not** Samsung software and copies no Samsung
assets (icons, fonts, wallpapers, extracted resource values) — see
`docs/design/one-ui-parity-matrix.md`'s "Intentional differences" and
`README.md`. This document records the *behavior* being reproduced and,
for every claim, where it came from.

No physical Samsung Galaxy device or Samsung account was available in this
environment. Every claim below is sourced to a citation (official Samsung
support page, Samsung Community, or another named outlet) rather than
recalled from general knowledge, and each is dated to when it was fetched
so it can be re-verified if Samsung's current behavior moves again. Where a
claim's source is a third party rather than samsung.com, that's noted
explicitly.

## Home screen grid

- One UI 7 (Android 15) supports exactly two Home screen grids: **4×6**
  and **5×6**. Earlier One UI versions (6 and before) additionally offered
  4×5 and 5×5; those are no longer offered as of One UI 7, and devices
  upgrading from a 4×5/5×5 layout have it remapped to the nearest
  supported grid automatically, which can leave visible gaps.
  Source: [Samsung Caribbean — "Changes to the Home screen on the Samsung
  Galaxy devices"](https://www.samsung.com/latin_en/support/mobile-devices/changes-to-the-home-screen-on-the-samsung-galaxy-devices/),
  fetched 2026-08-10.
- Good Lock's "Home Up" module is the documented way to get additional
  grid choices back — out of scope here (third-party Samsung app, not
  base One UI Home).

**ReLite decision:** workspace schema v3 offers exactly 4×6 and 5×6 as the
two Home grid presets (section 57), migrating any existing 4×5 layout via
`GridReflowPlanner` rather than silently truncating items that no longer
fit.

## Apps screen sort mode

- The Apps screen's own menu (three-dot / "More options") has a **Sort**
  entry offering **Custom order** and **Alphabetical order**.
  Source: [how2shout — "How to sort apps alphabetically or in a custom
  order in Samsung One UI"](https://www.how2shout.com/how-to/how-to-sort-apps-alphabetically-or-in-a-custom-order-in-samsung-one-ui.html),
  fetched 2026-08-10 (third-party outlet, not samsung.com; the menu
  entry names match Samsung's own on-device strings as commonly
  screenshotted, but this specific page is not an official Samsung
  source).
- Users report that Custom order and Alphabetical order are tied to
  different scroll axes and that current One UI versions do not offer
  a horizontal-alphabetical or vertical-custom combination.
  Source: [Samsung Community thread, "How can i sort apps name
  alphabetical in Home Screen Only mode"](https://eu.community.samsung.com/t5/galaxy-a-series/how-can-i-sort-apps-name-alphabetical-in-quot-home-screen-only/td-p/8715470),
  fetched 2026-08-10 (community forum, not an authoritative Samsung
  statement, but consistent across multiple threads found).

**ReLite decision:** `AppsSortMode.CUSTOM` pages horizontally with a
persisted drag-reordered sequence; `AppsSortMode.ALPHABETICAL` scrolls
vertically and is always alphabetically derived (not reorderable), matching
the reported axis coupling. Default is `CUSTOM` per the master plan.

## Apps screen search position

- One UI 7 moved the Apps screen search bar from the top to the **bottom**
  of the screen, explicitly for easier one-handed reach; Samsung
  confirmed this is the current intended design (not a bug) in response
  to user feedback asking to move it back.
  Source: [SamMobile — "Check out One UI 7 app drawer's new bottom search
  bar"](https://www.sammobile.com/news/samsung-one-ui-7-app-drawer-bottom-search-bar-pictured/),
  and [samfw.com — "One UI 7.0 moves the Search bar on the Apps screen
  down"](https://samfw.com/blog/one-ui-7-0-moves-the-search-bar-on-the-apps-screen-down),
  fetched 2026-08-10.

**ReLite decision:** Apps screen search moves to a bottom-anchored bar,
inset above the IME/gesture-nav region — matches ReLite's existing
one-handed-reach rationale for dock placement.

## Home screen edit mode

- Long-pressing empty Home space opens a "home screen customizer" /ist of
  actions rather than a plain dialog. The documented primary actions are:
  **Wallpaper and style** (wallpaper picker + color palette / dynamic
  theming), **Widgets** (opens the widget picker), and a **Settings**
  affordance in the lower-right corner that opens One UI Home's own
  settings page. Page management (add/remove/reorder Home pages, set
  default page) is reached from the page-overview strip shown in this same
  mode.
  Source: [androidpolice.com — "Samsung One UI Home: Everything you need
  to know"](https://www.androidpolice.com/samsung-one-ui-home-guide/),
  fetched 2026-08-10 (independent outlet, cross-checked against the
  general shape described in multiple other guides returned by the same
  search — no single official Samsung page enumerating all edit-mode
  actions was found in this pass).

**ReLite decision:** replace the current "long-press empty Home → plain
AlertDialog list" with a real edit-mode surface: the workspace scales down
slightly, a page-overview strip appears, and bottom actions are
**Wallpaper and style** (launches the platform wallpaper/style picker,
same as v0.4.1's existing wallpaper support), **Widgets** (opens
`WidgetPickerActivity`), and **Home screen settings** (opens
`HomeSettingsActivity`), plus page add/remove/reorder/default-page
controls on the same strip.

## Folders

The master plan's "compact preview + enlarged 2×2 folder" description
could not be independently re-confirmed against a current, citable Samsung
source in this pass (search results returned general "how to use folders"
guides, not a source documenting the exact expanded-folder grid span).
This is recorded honestly as **not independently re-verified this pass**
rather than asserted as confirmed. ReLite's existing compact 2×2 preview
(`FolderPreview`, shipped in v0.4.1) and this version's expanded 2×2
folder view are implemented from the master plan's description with
`LOW_CONFIDENCE` on the exact span, pending a citable source or direct
device comparison.

## Visual specifications (added in the v0.5.0 visual pass)

The earlier revision of this document recorded that no pixel-level values
were available. That turned out to be **wrong** — not because a device
appeared, but because Samsung publishes them. The **"One UI Design
Guidelines"** PDF, distributed openly on Samsung's design site for
third-party developers, contains concrete figures. Fetched 2026-08-12
from
<https://design.samsung.com/global/contents/one-ui/download/oneui_design_guide_eng.pdf>.

Everything below is quoted from that document, with its page number.

### Margins and keylines (Architecture 04, p.14)

> One UI recommends to allow at minimum **24dp margins on each side** and
> keep placing components to display information within safe area if
> touch input is required.

The stated purpose is that on rounded-corner and edge displays, on-screen
elements must sit before the start of the curve so a finger doesn't slip
off them and so nothing is obscured.

**ReLite decision:** `screen_margin_horizontal = 24dp`, applied as the
Home grid's horizontal padding and the dock's horizontal margin.

### Thumbnail radius (Visual Design 04, p.67)

> When using a rounded-corner rectangle for a focus block or image
> thumbnail, it's recommended that you use the following thumbnail radius
> value according to the screen grid and target: **26dp / 26dp / 20dp /
> 12dp**.

**ReLite decision:** a three-step scale — `radius_large` 26dp (dock,
folder — the largest focus blocks), `radius_medium` 20dp (context-menu
card), `radius_small` 12dp (app icons). The document gives the values but
not a rule mapping each to a specific component class, so the assignment
of step to surface is ReLite's judgement; only the *scale* is sourced.

### Color (Visual Design 02, p.62-63)

The guide names three color roles and their uses:

| Role | Applies to | Light | Dark |
|---|---|---|---|
| Primary | App icons, floating action buttons, input fields, focused items | `#0381fe` | `#0381fe` |
| Primary dark | App bar text, text buttons, dialog buttons | `#0072de` | `#3e91ff` |
| Color control activated | Checkboxes, radio buttons, switches | `#3e91ff` | `#3e91ff` |

**ReLite decision:** adopted as the accent palette, including the
light/dark split on "Primary dark" and `colorControlActivated` for
switches specifically — before this, AppCompat's own default accent was
tinting the Settings toggles, visibly off-palette.

Backgrounds are **not** specified numerically anywhere in the guide — it
describes them only as "simple, calm colors" and "monotone in both Light
and Dark mode". ReLite's neutrals therefore remain its own and are not
claimed as parity.

### Typography (Visual Design 03, p.66)

> The default font used in One UI is **Roboto**.

**ReLite decision:** nothing to do — Roboto is Android's own system
default, which this app already uses (it bundles no font). This is the
one typography claim that can be matched exactly without shipping
anything of Samsung's.

The same section states a capitalization convention ("capitalize the
first letter in every word and sentence used for components"), but the
wording is ambiguous between Title Case and sentence case and the
clarifying before/after examples are images, not text. Observed One UI
menus use sentence case, which ReLite already follows, so this was left
alone rather than changed on a guess.

## What this reference still does not include

Motion is described only qualitatively in the guide (its "Natural",
"Seamless", "Tangible" principles, p.72-77) — no durations, no easing
curves, no numeric values of any kind. Haptic patterns likewise. Icon
sizing, dock height, page-indicator geometry and cell padding are also
unspecified. Those remain ReLite's own measurements and are graded
accordingly in `docs/design/one-ui-parity-matrix.md`.
