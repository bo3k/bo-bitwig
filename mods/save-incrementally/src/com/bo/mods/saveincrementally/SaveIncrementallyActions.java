package com.bo.mods.saveincrementally;

import bml.actions.ActionProvider;
import bml.actions.Actions;

/**
 * Save Incrementally: a Bitwig action in the File category (Settings > Shortcuts, the Commander),
 * also placed in the File menu after Save as by SaveIncrementallyHooks.
 */
public final class SaveIncrementallyActions implements ActionProvider {
    @Override
    public void declare(Actions actions) {
        actions.action(SaveIncrementally.ACTION, "Save Incrementally")
               .category("file")
               .icon("save_incrementally_icon")
               .onRun(context -> SaveIncrementally.saveIncrementally(context.widget()));
    }
}
