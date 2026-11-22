package com.bo.mods.masterbo;

import bml.api.Bml;
import bml.panels.PanelProvider;
import bml.panels.Panels;

/**
 * bo Master's registration with Framework: Panels: one view, "bo Master", a column of its own
 * between the main area and the browser side (the Browser, Project, Studio I/O and Mappings
 * panels), with its icon (its button with the browser side's buttons), its content (the master
 * strip) and a scale (listed by the framework in Settings > User Interface > Panel Scale).
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
              .side("browser")                 // on the browser side (Browser, Project, Studio I/O, Mappings)
              .sideColumn(true)                // as a full-height column of its own beside it (Framework: Panels 0.3.0-dev.6)
              .scale(true);
    }
}
