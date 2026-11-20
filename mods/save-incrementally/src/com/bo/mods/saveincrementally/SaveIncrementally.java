package com.bo.mods.saveincrementally;

import bml.api.Bml;
import bitwig.obf.aBF;  // a window's project (its document, selections)
import bitwig.obf.aBW;  // a window's project view (Dah(): Save, asking for a file when there is none)
import bitwig.obf.aCp;  // a project window (what window-wide actions run on)
import com.bitwig.flt.app.TL;  // the application (saving a project to a file)
import com.bitwig.flt.document.core.iface.pHd; // a project (wZs(): its "location", the .bwproject path)

import java.io.File;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** What Save Incrementally does. */
final class SaveIncrementally {
    static final Bml.Mod MOD = Bml.mod("save-incrementally");
    /** The action's ID, from BO Tools, the mod it was first in: stored with the user's shortcuts. */
    static final String ACTION = "bo_tools_save_incrementally";

    private SaveIncrementally() {}

    /**
     * Saves the project under the next free name: the number at the end of its name plus one, with
     * the same number of digits (Mix_009 -> Mix_010), or "_001" appended (Mix -> Mix_001), in the same
     * folder. Saving as Bitwig's Save As does: the project continues under the new file. A project
     * never saved runs Bitwig's Save (which asks for a file).
     */
    static void saveIncrementally(Object widget) {
        try {
            aCp window = window(widget);
            if (window == null) { MOD.log("save: no project window (" + widget + ")"); return; }
            aBF project = window.iAj();          // iAj(): the window's project
            if (project == null) return;
            pHd document = project.hUR();        // hUR(): its document
            String location = document != null ? document.wZs() : null; // wZs(): "location"
            if (location == null || location.isBlank()) {
                aBW view = window.Dah();         // Dah(): the window's project view
                if (view != null) view.Dah();    // Dah(): Save (asks for a file)
                MOD.log("save: project never saved, Save run");
                return;
            }
            File next = next(new File(location));
            MOD.log("save: " + location + " -> " + next);
            TL.hIO().cfM(project, next, false, false, null); // as Save As does with the chosen file
        } catch (Throwable failure) {
            MOD.log("save incrementally failed", failure);
        }
    }

    private static final Pattern NUMBER = Pattern.compile("^(.*?)(\\d+)$");

    static File next(File current) {
        File folder = current.getAbsoluteFile().getParentFile();
        String name = current.getName();
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) { ext = name.substring(dot); name = name.substring(0, dot); }
        String stem;
        long number;
        int width;
        Matcher m = NUMBER.matcher(name);
        if (m.matches()) {
            stem = m.group(1);
            width = m.group(2).length();
            number = Long.parseLong(m.group(2)) + 1;
        } else {
            stem = name + "_";
            width = 3;
            number = 1;
        }
        File f;
        do {
            f = new File(folder, stem + String.format("%0" + width + "d", number) + ext);
            number++;
        } while (f.exists());
        return f;
    }

    private static aCp window(Object widget) {
        return widget instanceof aCp w ? w : null;
    }

}
