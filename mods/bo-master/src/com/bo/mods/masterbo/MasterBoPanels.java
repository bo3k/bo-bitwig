package com.bo.mods.masterbo;

import bml.api.Bml;
import bml.panels.PanelProvider;
import bml.panels.Panels;

/**
 * Panel: Master Bo's registration with Framework: Panels: one view, "Master Bo", a column of its
 * own between the arranger and the browser side (the Browser, Project, Studio I/O and Mappings
 * panels), with its icon (its button next to the keyboard's), its content (the master strip) and a
 * scale (listed by the framework in Settings > User Interface > Panel Scale).
 */
public final class MasterBoPanels implements PanelProvider {
    static final String MOD_ID = "master-bo";
    static final Bml.Mod MOD = Bml.mod(MOD_ID);
    static final String VIEW_ID = "master_bo";

    @Override
    public void declare(Panels panels) {
        panels.view(VIEW_ID, "bo Master")
              .icon("master_bo_icon")          // the mod's own SVG (mod.json resources)
              .content(MasterBoWidget::create) // built for each window and each place
              .side("browser")                 // a tab of the browser side (Browser, Project, Studio I/O, Mappings)
              .sideFirst(true)                 // first: its button before the Browser's
              .sideColumn(() -> true)          // a column of its own, left of the browser side; its button (next to the keyboard's) shows and hides it
              .scale(true);
    }
}
