package com.bo.mods.insertdevice;

import bml.api.Bml;
import bitwig.obf.aBF;  // a window's project (its document, selections)
import bitwig.obf.aCp;  // a project window (what window-wide actions run on)
import bitwig.obf.bjq;  // the window's track selection
import bitwig.obf.iCC;  // a selection with its cursor
import bitwig.obf.iKU;  // what holds a track's devices (its device selection)
import bitwig.obf.jsd;  // a device
import bitwig.obf.FAu;  // a device chain
import bitwig.obf.IkS;  // a file as content to insert
import bitwig.obf.XuI;  // how content is inserted
import bitwig.obf.pHE;  // an insertion flag (MRY: copy)
import com.bitwig.flt.app.CHR; // the application (device UUID -> file)
import com.bitwig.flt.document.core.iface.RYd; // a channel (track, effect track, master)
import com.bitwig.flt.document.core.iface.clipboard.device.DeviceChainInsertionPoint; // a position in a device chain
import com.bitwig.flt.library.metadata.reader.BZG; // the plug-in metadata readers (MRY(): VST3)

import java.io.File;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/** What the insert actions do. */
final class InsertDevice {
    static final Bml.Mod MOD = Bml.mod("insert-device-via-shortcut");
    static final UUID TOOL = UUID.fromString("e67b9c56-838d-4fba-8e3e-ae4e02cccbcb");   // Bitwig's Tool (factory table)
    static final String PRO_Q_4 = "ED57BD725C60467EA64DD2F400758B6F"; // FabFilter Pro-Q 4 (VST3 ID)
    /** The CLAP ID of FabFilter Pro-Q 4: "fabfilter" and "pro-q 4" in any spelling, not Pro-Q 3 or a later version. */
    static final Pattern PRO_Q_4_CLAP = Pattern.compile("(?i).*fabfilter.*pro[-_. ]?q[-_. ]?4(?![0-9]).*");
    static final UUID CHAIN = UUID.fromString("c86d21fb-d544-4daf-a1bf-57de22aa320c");  // Bitwig's Chain

    private InsertDevice() {}

    // ---------- Inserting devices ----------

    static void insertBitwigDevice(Object widget, UUID uuid, String name) {
        try {
            File file = CHR.Grk().cfM(uuid);     // cfM(UUID): the device's file (as the controller API's insertBitwigDevice)
            if (file == null) { MOD.log("insert: no file for " + name + " " + uuid); return; }
            insert(widget, new IkS(file), name);
        } catch (Throwable failure) {
            MOD.log("insert " + name + " failed", failure);
        }
    }

    private static File library;

    /**
     * Inserts one of Bitwig's own devices by the name of its file in Bitwig's Library/devices folder
     * (the folder Tool's file is in, as Bitwig resolves it).
     */
    static void insertLibraryDevice(Object widget, String name) {
        try {
            if (library == null) {
                File tool = CHR.Grk().cfM(TOOL);
                library = tool != null ? tool.getParentFile() : null;
            }
            if (library == null) { MOD.log("insert " + name + ": Bitwig's device folder not found"); return; }
            File file = new File(library, name + ".bwdevice");
            if (!file.isFile()) { MOD.log("insert " + name + ": no " + file); return; }
            insert(widget, new IkS(file), name);
        } catch (Throwable failure) {
            MOD.log("insert " + name + " failed", failure);
        }
    }

    /**
     * Inserts the VST3 plug-in with this ID, as the controller API's insertVST3Device does: the first
     * entry of Bitwig's VST3 index for the ID.
     */
    static void insertVst3(Object widget, String vst3Id, String name) {
        try {
            List<?> found = BZG.MRY().MRY(vst3Id);   // MRY(): the VST3 index; MRY(id): its entries for the ID
            if (found == null || found.isEmpty()) found = BZG.MRY().MRY(vst3Id.toLowerCase());
            if (found == null || found.isEmpty()) { MOD.log("insert " + name + ": no VST3 plug-in with ID " + vst3Id); return; }
            com.bitwig.base.io.z2y ref = ((com.bitwig.flt.library.metadata.master.g2L) found.get(0)).ii_(); // ii_(): its file
            if (ref == null) { MOD.log("insert " + name + ": the plug-in has no file"); return; }
            insert(widget, new IkS(ref), name);
        } catch (Throwable failure) {
            MOD.log("insert " + name + " failed", failure);
        }
    }

    private static com.bitwig.flt.library.metadata.master.tfK proQClap;

    /**
     * Inserts FabFilter Pro-Q 4 in its CLAP version. Its CLAP ID isn't published, so it is looked up
     * in Bitwig's CLAP index (what the controller API's insertCLAPDevice reads): the plug-in whose ID
     * names FabFilter and Pro-Q 4. The ID found is logged.
     */
    static void insertProQ4Clap(Object widget) {
        String name = "Pro-Q 4 (CLAP)";
        try {
            if (proQClap == null) proQClap = findClap(PRO_Q_4_CLAP);
            if (proQClap == null) { MOD.log("insert " + name + ": not in Bitwig's CLAP index (not installed or not scanned)"); return; }
            com.bitwig.base.io.z2y ref = proQClap.ii_(); // ii_(): its file
            if (ref == null) { MOD.log("insert " + name + ": the plug-in has no file"); return; }
            insert(widget, new IkS(ref), name);
        } catch (Throwable failure) {
            MOD.log("insert " + name + " failed", failure);
        }
    }

    /** The CLAP plug-in whose ID matches, from Bitwig's CLAP index: bundles (MRY()), their plug-ins (tp2()), IDs (Ucl()). */
    private static com.bitwig.flt.library.metadata.master.tfK findClap(Pattern id) {
        List<String> fabfilter = new java.util.ArrayList<>();
        for (Object bundle : BZG.Hnt().MRY()) {               // Hnt(): the CLAP index
            if (!(bundle instanceof com.bitwig.flt.library.metadata.master.BZG b)) continue;
            for (Object o : b.tp2()) {
                if (!(o instanceof com.bitwig.flt.library.metadata.master.tfK plugin)) continue;
                String pluginId = plugin.Ucl();
                if (pluginId == null) continue;
                if (id.matcher(pluginId).matches()) { MOD.log("CLAP " + pluginId + " -> " + plugin.ii_()); return plugin; }
                if (pluginId.toLowerCase().contains("fabfilter")) fabfilter.add(pluginId);
            }
        }
        MOD.log("CLAP: no plug-in matches " + id + "; FabFilter CLAP IDs: " + fabfilter);
        return null;
    }

    /**
     * Inserts the device into the current track: after its selected device (in that device's
     * chain, so inside a container when the selected device is there), else at the end of the track's
     * chain. As dropping it there: a copy of the file's content into a chain insertion point.
     */
    private static void insert(Object widget, IkS content, String name) {
        aCp window = window(widget);
        if (window == null) { MOD.log("insert: no project window (" + widget + ")"); return; }
        aBF project = window.iAj();
        RYd track = currentTrack(project);
        if (track == null) { MOD.log("insert " + name + ": no track selected"); return; }
        FAu chain = track.Ucl();                  // Ucl(): the channel's device chain
        if (chain == null) { MOD.log("insert " + name + ": the track has no device chain"); return; }
        int index = chain.NC().size();            // NC(): the chain's devices
        jsd selected = selectedDevice(track);
        if (selected != null && selected.xL() != null) {   // xL(): the chain the device is in
            FAu own = selected.xL();
            int i = own.NC().indexOf(selected);
            if (i >= 0) { chain = own; index = i + 1; }
        }
        DeviceChainInsertionPoint point = new DeviceChainInsertionPoint(chain, index, false, false, null);
        boolean ok = point.cfM(content, XuI.cfM.cfM(pHE.MRY, true), null);
        MOD.log("insert " + name + " on " + track + " at " + index + (selected != null ? " after the selected device" : " (end)") + ": " + ok);
    }

    private static aCp window(Object widget) {
        return widget instanceof aCp w ? w : null;
    }

    /** The window's current track: the cursor of its track selection (what the controller API's cursor track follows). */
    private static RYd currentTrack(aBF project) {
        if (project == null) return null;
        bjq tracks = project.Z1C();               // Z1C(): the window's track selection
        if (tracks == null) return null;
        iCC selection = tracks.IwV();
        if (selection == null) return null;
        Object cursor = selection.LaE();           // LaE(): the selection's cursor item
        return cursor instanceof RYd t ? t : null;
    }

    /** The track's selected device: its device selection's cursor device (as the controller API's cursor device). */
    private static jsd selectedDevice(RYd track) {
        iKU holder = track.tp2();                 // tp2(): what holds the track's devices
        if (holder == null || holder.fFM() == null) return null;
        bitwig.obf.EqN selection = holder.fFM().wGc(); // fFM().wGc(): its device selection
        return selection != null ? selection.cfM() : null; // cfM(): its cursor device
    }
}
