package com.bo.mods.saveincrementally;

import bitwig.obf.dmX; // an action
import bitwig.obf.dpN; // the application (its action registry)
import bitwig.obf.dsg; // a menu section's look
import bitwig.obf.dsm; // a menu section
import bitwig.obf.dsp; // a menu model (the window's File menu)
import bitwig.obf.duc; // Bitwig's File actions (UNj: Save as)

/**
 * The hook's bridge (named as the manifest's helper class). The window's File menu is built in
 * aCp.H_() as sections: "Project" (New, Open, Save, ..., Save as, Save as template, Close),
 * "Export", "Global". The hook replaces the call that adds the first section and adds Save
 * Incrementally right after Save as.
 */
public final class SaveIncrementallyHooks {
    private SaveIncrementallyHooks() {}

    public static dsm projectSection(dsp menu, String title, dsg look, dmX[] items) {
        try {
            dmX mine = dpN.TVZ().tp2(SaveIncrementally.ACTION); // tp2(id): the registered action with this ID
            if (mine != null && items != null) {
                int at = -1;
                for (int i = 0; i < items.length; i++) if (items[i] == duc.UNj) at = i;   // Save as
                dmX[] more = new dmX[items.length + 1];
                int n = 0;
                for (int i = 0; i < items.length; i++) {
                    more[n++] = items[i];
                    if (i == at) more[n++] = mine;
                }
                if (at < 0) more[n++] = mine;
                items = more;
            } else {
                SaveIncrementally.MOD.log("File menu: Save Incrementally is not registered (yet)");
            }
        } catch (Throwable failure) {
            SaveIncrementally.MOD.log("File menu: could not add Save Incrementally", failure);
        }
        return menu.MRY(title, look, items);
    }
}
