# Framework: Panels, Bo's build (0.2.0-dev.2.bo.5)

A modified build of **Framework: Panels** (`panel-management-framework`) 0.2.0-dev.2 by shaboogen, which ships with the Patchbay mod loader (BML). The original is MIT-licensed (`LICENSE`, unchanged); this build is under the same licence.

## What it adds

A view can sit on the **browser side**, the tab group of the Browser, Project, Studio I/O and Mappings panels, instead of only in the main area and its stacks.

| Call | What it does |
|---|---|
| `View.side("browser")` | The view is a tab of the browser side, after Bitwig's own tabs. Its button sits with theirs in the bottom bar. |
| `View.sideFirst(true)` | Its tab, and button, come before Bitwig's own. |
| `View.sideColumn(BooleanSupplier)` | Asked when Bitwig builds a display profile. When it says true, the view is a full-height column of its own between the main area and the browser side, with its own width (saved with the project) and its own button just before the keyboard's. |

Everything else behaves as in the original. Mods that don't use these calls see no difference.

## Files changed from 0.2.0-dev.2

| File | Change |
|---|---|
| `Registry.java` | The three builder calls, and their fields on a declared view. |
| `View.java` | Their documentation in the public `View` API. |
| `Placement.java` | Places side views: tabs of the browser side's group, or columns beside it. |
| `ViewNode.java` | A side column's own "is shown" and its button next to the keyboard's. |
| `Layouts.java`, `PanelActions.java` | Side views are kept out of stacks. |
| `PanelHooks.java` | The side columns' size and buttons. |
| `mod.json` | The version (`0.2.0-dev.2.bo.5`), the description, and one more method target (`akQ.MRY`, the bottom bar's row). |
| `README.md` | A section on the browser side. |

All other files are the original's.
