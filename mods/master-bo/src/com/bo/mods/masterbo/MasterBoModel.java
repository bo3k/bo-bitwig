package com.bo.mods.masterbo;

import java.util.ArrayList;
import java.util.List;

import bitwig.obf.BhF; // a device's "enabled" control
import bitwig.obf.FAu; // a channel's device chain
import bitwig.obf.Jmw; // a channel's mixer module (volume, pan, meters)
import bitwig.obf.v3U; // a value atom (what a controller parameter adjusts)
import bitwig.obf.RiF; // a plug-in device, of any format (its window: PD3, "is_plugin_window_open")
import bitwig.obf.aBW; // a window's project view
import bitwig.obf.iPC; // a sample rate
import bitwig.obf.XuI; // how content is inserted (move or copy)
import bitwig.obf.pHE; // an insertion flag (MRY: copy)
import com.bitwig.flt.document.core.iface.clipboard.device.DeviceChainInsertionPoint; // a position in a device chain
import com.bitwig.flt.document.core.iface.clipboard.device.Uev; // devices as content to insert
import bitwig.obf.foz; // a listener
import bitwig.obf.ljb; // a meter (its values per channel)
import bitwig.obf.qqr; // a device chain's meter (input_meter / output_meter)
import bitwig.obf.iCC; // a selection of channels (with its cursor)
import bitwig.obf.big; // a selection, as the window shows it (active / Inspector)
import bitwig.obf.akc; // the Inspector panel (its class identifies it in the window)
import bitwig.obf.akQ; // a side panel of the window
import bitwig.obf.AQw; // a panel's visibility
import bitwig.obf.iok; // a colour
import bitwig.obf.jsd; // a device
import bitwig.obf.tft; // one meter value of a channel (peak and RMS)
import com.bitwig.flt.control_surface.values.RangedValueTarget; // a parameter's value, as controllers see it
import com.bitwig.flt.control_surface.values.ValueAtomParameterTarget; // a parameter, as controllers adjust it
import com.bitwig.flt.document.core.iface.RYd; // a channel (track, effect track, master)
import com.bitwig.flt.document.core.iface.kWY; // a group of tracks (the project's root is one)
import com.bitwig.flt.document.core.iface.pHd; // a project
import com.bitwig.ramona.core.ESe; // a text value of a document object
import com.bitwig.ramona.core.Dgc; // a value field of a document object (getValue)
import com.bitwig.ramona.core.bnq; // a field holding document objects (a list or one object)
import com.bitwig.ramona.core.cuN; // an action field of a document object (run)
import com.bitwig.ramona.core.oDc; // a document object
import com.bitwig.flt.document.core.iface.hgq; // a group of channels (its tracks)
import com.bitwig.ramona.core.lBQ; // a boolean value of a document object

/**
 * Master Bo's view of the project's master track, read again on every display frame while the
 * panel is shown (refresh), so a new project, renamed or added devices and every level follow
 * without listeners of its own; refresh says when something changed.
 *
 * The master track is the root track group's own track (kWY.TD2(), Bitwig's
 * "track_group.master_track"), as the controller API and Bitwig's command server reach it.
 *
 * Volume and pan go through Bitwig's controller parameter targets, so a drag is what a
 * controller's touch / set / release is: one adjusting gesture (one undo step) per drag.
 *
 * Levels come from the mixer's meter values, as Bitwig's own meters read them: per channel a
 * peak (peak_level) and an RMS (rms_level) value, linear, at the display rate. Master Bo adds a
 * peak hold per channel (held 1.5 s, then falling 20 dB/s), the highest peak since the last
 * reset, and a clip flag per channel (a peak above 0 dBFS since the last reset).
 *
 * The VU's source is Master Bo's own setting: PRE, the master's device chain input meter
 * ("input_meter": the level arriving at the master, before its inserts), the default; or POST,
 * the mixer's meter (after the inserts and the fader, as the controller API's VU observer reads it).
 *
 * The VU: the RMS per channel, on a VU
 * needle with a VU meter's ballistics (a second-order needle reaching 99% of a step in 300 ms
 * with about 1.3% overshoot), deflection proportional to the level as on an analogue VU (0 VU at
 * about 71% of the arc, +3 VU at the end). 0 VU is a level in dBFS, Master Bo's own setting
 * (default -18 dBFS), kept in the mod's settings.
 */
final class MasterBoModel {
    /** One device of the master's chain. */
    static final class Insert {
        final jsd device;
        String name = "";
        boolean enabled = true;
        boolean switchable;   // its enabled value can be set
        boolean plugin;       // a plug-in: its window button opens and closes its window
        boolean windowOpen;   // a plug-in's window is open

        Insert(jsd device) {
            this.device = device;
        }
    }

    private static final long HOLD_MS = 1500;          // peak hold: how long a hold stays
    private static final double FALL_DB_PER_S = 20;    // then how fast it falls

    private final aBW window;

    // The master and its controller targets, rebuilt when the master changes (another project).
    private RYd master;
    private ValueAtomParameterTarget volume;
    private ValueAtomParameterTarget pan;
    private RangedValueTarget volumeValue;
    private RangedValueTarget panValue;

    String name = "Master";
    double latencyMs;        // the master's latency, as the device panel shows it
    iok colour = iok.Hnt;    // grey until read
    double volumePos;        // 0 (bottom, -inf) .. 1 (top, the maximum), on the strip's scale
    double volumeParam;      // the volume parameter's value (cube root of the gain; 1 = 0 dB)
    double panNorm = 0.5;    // 0 (left) .. 1 (right)
    final double[] peak = new double[2];    // linear, per channel (left, right)
    final double[] rms = new double[2];
    final double[] hold = new double[2];    // the peak hold, linear
    private final long[] holdTime = new long[2];
    private long lastFrame;
    double maxPeak;          // the highest peak since the last reset, linear
    final boolean[] clip = new boolean[2]; // a peak above 0 dBFS since the last reset
    int meterCount;          // channels with meter values (0: none read)
    final List<Insert> inserts = new ArrayList<>();
    private final List<Insert> nextInserts = new ArrayList<>();
    private final java.util.Set<Object> logged = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>()); // devices already logged

    private long lastSignature = Long.MIN_VALUE;

    // ---------- VU ----------
    /** The choices of 0 VU, in dBFS (-9: +4 dBu on an interface where 0 dBFS = +13 dBu). */
    static final int[] VU_REFERENCES = {-20, -18, -14, -12, -9};
    private static final String VU_REFERENCE_KEY = "vu_reference_dbfs";
    private static final double VU_OMEGA = 13.47; // rad/s: 99% of a step in 300 ms
    private static final double VU_ZETA = 0.81;   // about 1.3% overshoot
    static final double VU_FULL = Math.pow(10, 3 / 20.0); // +3 VU: the arc's end, in 0 VU units
    int vuReference = readReference(); // 0 VU, in dBFS
    final double[] vu = new double[2];      // the needles: deflection 0..1 of the arc (may pass 1 a little)
    private static final String VU_SOURCE_KEY = "vu_source_pre"; // true: the chain's input (pre-FX), false: the mixer (post)
    boolean vuPre = readSource();
    final double[] preRms = new double[2];  // the master's chain input, RMS per channel
    final double[] prePeak = new double[2];
    boolean preAvailable;                   // the chain has an input meter with values
    private Object preMeterSubscribed;       // the input meter we listen to (so Bitwig keeps it up to date)
    private final foz quiet = event -> { };  // a listener that only asks for the values
    private final double[] vuSpeed = new double[2];

    MasterBoModel(aBW window) {
        this.window = window;
    }

    boolean hasMaster() {
        return master != null && volumeValue != null && panValue != null;
    }

    /** The project's master track: the root group's own track (null without a project). */
    private static RYd findMaster(aBW window) {
        pHd project = window.mtS(); // the window's project
        if (project == null) return null;
        kWY root = project.VpW();   // the project's root track group
        return root != null ? root.TD2() : null; // TD2(): the group's own track, for the root the master
    }

    /** Reads everything again; true when something shown changed (repaint). */
    boolean refresh() {
        RYd now = findMaster(window);
        if (now != master) bind(now);
        if (master == null) {
            long sig = 17;
            if (sig != lastSignature) { lastSignature = sig; return true; }
            return false;
        }
        name = text(master.cb_(), "Master");  // cb_(): its name
        colour = master.BDm().cfM();          // BDm(): its colour value; cfM(): as a colour
        if (volumeValue != null) {
            volumeParam = volumeValue.getCurrentValue();
            volumePos = volumeToPosition(volumeParam, maxVolume());
        }
        if (panValue != null) panNorm = clamp(panValue.getCurrentNormalizedValue());
        readMeters();
        readInserts();
        latencyMs = readLatency();

        long sig = 31;
        sig = sig * 31 + name.hashCode();
        sig = sig * 31 + colour.qoU();         // qoU(): the colour as one int
        sig = sig * 31 + Math.round(volumePos * 4000);
        sig = sig * 31 + Math.round(panNorm * 4000);
        for (int i = 0; i < 2; i++) {
            sig = sig * 31 + Math.round(position(peak[i]) * 600);
            sig = sig * 31 + Math.round(position(rms[i]) * 600);
            sig = sig * 31 + Math.round(position(hold[i]) * 600);
            sig = sig * 31 + (clip[i] ? 1 : 0);
        }
        sig = sig * 31 + Math.round(dB(maxPeak) * 10);
        sig = sig * 31 + Math.round(vu[0] * 2000);
        sig = sig * 31 + Math.round(vu[1] * 2000);
        sig = sig * 31 + vuReference;
        sig = sig * 31 + (vuPre ? 1 : 2) + (preAvailable ? 4 : 8);
        sig = sig * 31 + meterCount;
        for (Insert insert : inserts) {
            sig = sig * 31 + insert.name.hashCode();
            sig = sig * 31 + (insert.enabled ? 1 : 2);
            sig = sig * 31 + (insert.plugin ? (insert.windowOpen ? 3 : 4) : 5);
        }
        sig = sig * 31 + inserts.size();
        sig = sig * 31 + Math.round(latencyMs * 10);
        readLufs();
        readSpectrum();
        readScope();
        readSoloMute();
        readMacros();
        syncSpectrumSettings();
        sig = sig * 31 + (lufsFound ? 7 : 9);
        sig = sig * 31 + (spectrumFound ? 11 : 13) + slopeChoice + (scopeFound ? 17 : 19);
        sig = sig * 31 + (soloMuteFound ? 1 : 0) + (soloOn ? 2 : 0) + (muteOn ? 4 : 0);
        for (Macro m : macros) sig = sig * 31 + m.name.hashCode() + Math.round(m.norm * 1000) + 7 * m.kind + m.valueText.hashCode();
        if (scopeFound) {
            sig = sig * 31 + Math.round(correlation * 100);
            sig = sig * 31 + gonioFrame; // the persistence changes every frame while it fades
        }
        for (double[] a : thirdOctave ? new double[][] {bands, bandsM, bandsS, bandsH, bandsMH, bandsSH}
                                      : new double[][] {curve, curveM, curveS, curveH, curveMH, curveSH})
            for (double v : a) sig = sig * 31 + Math.round(v * 4);
        sig = sig * 31 + (msMode ? 1 : 2) + (thirdOctave ? 4 : 8) + (holdOn ? 16 : 32) + 64 * holdChoice;
        for (double v : lufs) sig = sig * 31 + Math.round(v * 10);
        sig = sig * 31 + Math.round(lufsRef * 10);
        if (sig == lastSignature) return false;
        lastSignature = sig;
        return true;
    }

    private void bind(RYd now) {
        master = now;
        masterSolo = masterMute = null;
        muteTarget = null;
        soloMuteSearchAt = 0;
        volume = null;
        pan = null;
        volumeValue = null;
        panValue = null;
        inserts.clear();
        resetPeaks();
        if (now == null) return;
        Jmw mixer = now.MIO(); // its mixer module
        if (mixer == null) return;
        volume = ValueAtomParameterTarget.createForAtom(mixer.UNj()); // UNj(): volume
        pan = ValueAtomParameterTarget.createForAtom(mixer.hUR());    // hUR(): pan
        volumeValue = volume.getValueTarget();
        panValue = pan.getValueTarget();
    }

    /** The master's latency in ms, as the device panel shows it: its latency in samples (hDc) at its sample rate (h2a). */
    private double readLatency() {
        int samples = master.hDc(); // hDc(): the channel's latency, in samples
        iPC rate = master.h2a();    // h2a(): the channel's sample rate
        int sampleRate = rate != null ? rate.FzB() : iPC.cfM().FzB(); // FzB(): in Hz; iPC.cfM(): the engine's
        return samples > 0 && sampleRate > 0 ? samples * 1000.0 / sampleRate : 0;
    }

    // ---------- Meters ----------

    private void readMeters() {
        long nowMs = System.currentTimeMillis();
        double seconds = lastFrame == 0 ? 0 : Math.min(0.5, (nowMs - lastFrame) / 1000.0);
        lastFrame = nowMs;
        Jmw mixer = master.MIO();
        List<?> meters = mixer != null && mixer.E3() != null ? mixer.E3().FzB() : List.of(); // E3(): the meters; FzB(): their values
        meterCount = Math.min(2, meters.size());
        for (int i = 0; i < 2; i++) {
            // a mono meter shows the same on both sides
            Object value = meters.isEmpty() ? null : meters.get(Math.min(i, meters.size() - 1));
            if (value instanceof tft meter) {
                peak[i] = sane(meter.IwV()); // IwV(): the peak, linear (peak_level)
                rms[i] = sane(meter.cfM());  // cfM(): the RMS, linear (rms_level)
            } else {
                peak[i] = 0;
                rms[i] = 0;
            }
            if (peak[i] >= hold[i]) {
                hold[i] = peak[i];
                holdTime[i] = nowMs;
            } else if (nowMs - holdTime[i] > HOLD_MS) {
                double fallen = hold[i] * Math.pow(10, -FALL_DB_PER_S * seconds / 20);
                hold[i] = Math.max(peak[i], fallen < 1e-6 ? 0 : fallen);
            }
            if (peak[i] > 1.0) clip[i] = true;
            if (peak[i] > maxPeak) maxPeak = peak[i];
        }
        readPreMeter();
        moveNeedles(seconds);
    }

    /** The master's device chain (FAu), or null. */
    FAu chain() {
        return master != null ? master.Ucl() : null; // Ucl(): the channel's device chain
    }

    /** The master's device chain input meter (before its inserts): FAu.Ucl(), its "input_meter". */
    private void readPreMeter() {
        preAvailable = false;
        FAu chain = master.Ucl();
        qqr input = chain != null ? chain.Ucl() : null; // Ucl(): the chain's input meter
        ljb meter = input != null ? input.cfM() : null; // cfM(): its values
        List<?> values = meter != null ? meter.FzB() : List.of();
        if (meter != null && preMeterSubscribed != meter) {
            for (Object v : values) {
                if (v instanceof tft t) {
                    t.cfM(quiet); // cfM(listener): follow its RMS
                    t.Hnt(quiet); // Hnt(listener): follow its peak
                }
            }
            preMeterSubscribed = meter;
        }
        for (int i = 0; i < 2; i++) {
            Object value = values.isEmpty() ? null : values.get(Math.min(i, values.size() - 1));
            if (value instanceof tft t) {
                preRms[i] = sane(t.cfM());
                prePeak[i] = sane(t.IwV());
                preAvailable = true;
            } else {
                preRms[i] = 0;
                prePeak[i] = 0;
            }
        }
    }

    /** PRE (the chain's input) or POST (the mixer) for the VU (a click on the VU's strip), kept in the mod's settings. */
    void toggleSource() {
        vuPre = !vuPre;
        try {
            MasterBoPanels.MOD.settings().putBoolean(VU_SOURCE_KEY, vuPre);
        } catch (Throwable failure) {
            MasterBoPanels.MOD.log("could not keep the VU source", failure);
        }
    }

    private static boolean readSource() {
        try {
            return MasterBoPanels.MOD.settings().getBoolean(VU_SOURCE_KEY, true);
        } catch (Throwable failure) {
            return true;
        }
    }

    /** The VU needles follow the RMS, as a VU meter's movement does (in small steps for a steady integration). */
    private void moveNeedles(double seconds) {
        double ref = Math.pow(10, vuReference / 20.0);
        int steps = (int) Math.ceil(seconds / 0.001);
        double dt = steps > 0 ? seconds / steps : 0;
        for (int i = 0; i < 2; i++) {
            double level = vuPre && preAvailable ? preRms[i] : rms[i]; // PRE: the chain's input; POST: the mixer
            double target = Math.min(1.25, level / ref / VU_FULL); // deflection: proportional to the level; pinned a little past the end
            for (int s = 0; s < steps; s++) {
                double accel = VU_OMEGA * VU_OMEGA * (target - vu[i]) - 2 * VU_ZETA * VU_OMEGA * vuSpeed[i];
                vuSpeed[i] += accel * dt;
                vu[i] += vuSpeed[i] * dt;
            }
            if (vu[i] < 0) { vu[i] = 0; vuSpeed[i] = 0; } // the needle rests on its stop
        }
    }

    /** The next choice of 0 VU (a click on the VU), kept in the mod's settings. */
    void nextReference() {
        int index = 0;
        for (int i = 0; i < VU_REFERENCES.length; i++) if (VU_REFERENCES[i] == vuReference) index = i;
        vuReference = VU_REFERENCES[(index + 1) % VU_REFERENCES.length];
        try {
            MasterBoPanels.MOD.settings().putInt(VU_REFERENCE_KEY, vuReference);
        } catch (Throwable failure) {
            MasterBoPanels.MOD.log("could not keep the VU reference", failure);
        }
    }

    private static int readReference() {
        try {
            int v = MasterBoPanels.MOD.settings().getInt(VU_REFERENCE_KEY, -18);
            for (int r : VU_REFERENCES) if (r == v) return v;
        } catch (Throwable failure) {
            MasterBoPanels.MOD.log("could not read the VU reference", failure);
        }
        return -18;
    }

    void resetPeaks() {
        maxPeak = 0;
        for (int i = 0; i < 2; i++) {
            clip[i] = false;
            hold[i] = 0;
        }
    }

    private static double sane(double v) {
        return Double.isFinite(v) && v > 0 ? v : 0;
    }

    // ---------- Inserts ----------

    private void readInserts() {
        nextInserts.clear();
        FAu chain = master.Ucl(); // Ucl(): its device chain
        List<?> devices = chain != null ? chain.NC() : List.of(); // NC(): the chain's devices, as the controller API's device bank lists them
        for (Object item : devices) {
            if (!(item instanceof jsd device)) continue;
            Insert insert = find(device);
            if (insert == null) insert = new Insert(device);
            insert.name = text(device.Z1C(), "Device"); // Z1C(): its name, as the controller API's Device.name()
            lBQ enabled = enabledValue(device);
            insert.switchable = enabled != null && enabled.isSettable();
            insert.enabled = enabled == null || enabled.getBoolValue();
            lBQ window = windowValue(device);
            insert.plugin = window != null;
            insert.windowOpen = window != null && window.getBoolValue();
            if (logged.add(device)) {
                Object inner = device.OzW();
                MasterBoPanels.MOD.log("insert \"" + insert.name + "\": " + device.getClass().getName()
                        + " running " + (inner != null ? inner.getClass().getName() : "nothing")
                        + (window != null ? ", plug-in window " + (window.getBoolValue() ? "open" : "closed")
                                + (window.isSettable() ? "" : " (not settable)") : ", a Bitwig device (no plug-in window)"));
            }
            nextInserts.add(insert);
        }
        inserts.clear();
        inserts.addAll(nextInserts);
        nextInserts.clear();
    }

    private Insert find(jsd device) {
        for (Insert insert : inserts) if (insert.device == device) return insert;
        return null;
    }

    private static lBQ enabledValue(jsd device) {
        BhF control = device.wZs(); // wZs(): its "enabled" control
        return control == null ? null : control.iCS(); // iCS(): the control's value
    }

    /**
     * A plug-in's window ("is_plugin_window_open"), as Bitwig's device header and the controller
     * API's Device.isWindowOpen() reach it: a device in the chain runs a plug-in (jsd.OzW(), a RiF
     * whatever its format) or one of Bitwig's devices.
     * Null for Bitwig's own devices.
     */
    private static lBQ windowValue(jsd device) {
        Object inner = device.OzW(); // OzW(): what the device runs: a plug-in (RiF) or one of Bitwig's devices
        return inner instanceof RiF plugin ? plugin.PD3() : null; // PD3(): the plug-in's window is open
    }

    /** Opens or closes a plug-in's window. */
    void toggleWindow(Insert insert) {
        lBQ window = windowValue(insert.device);
        if (window == null) return;
        window.setBoolValue(!window.getBoolValue());
        insert.windowOpen = window.getBoolValue();
    }

    /**
     * Moves an insert to a gap of the master's chain (0 = before the first device, n = after the
     * last), the way Bitwig's own drag and drop does: devices as content, moved (not copied) into a
     * chain insertion point.
     */
    void moveInsert(Insert insert, int gap) {
        FAu chain = chain();
        if (chain == null || insert == null) return;
        DeviceChainInsertionPoint point = new DeviceChainInsertionPoint(chain, gap, false, false, null); // as the chain's drop target
        XuI move = XuI.cfM.cfM(pHE.MRY, false); // pHE.MRY set: copy; not set: move (as the controller API's moveDevices)
        point.cfM(Uev.cfM(insert.device), move, null);
    }

    // ---------- BO LUFS (a loudness meter device somewhere in the project) ----------

    /** M, S, I (LUFS) and TP max (dBTP) from a BO LUFS device; -100 = no value yet. */
    final double[] lufs = {-100, -100, -100, -100};
    double lufsRef = -14;        // its Ref.LUFS knob
    boolean lufsFound;
    private static final String[] LUFS_ATOMS = {"BO LUFS M", "BO LUFS S", "BO LUFS I", "BO LUFS TP"};
    private final Dgc[] lufsFields = new Dgc[4];
    private Dgc lufsRefField;
    private oDc lufsTrigger;     // its Reset trigger (a trigger_atom)
    private long lufsSearchAt;
    private static final int TRIGGER_CLICK = 618; // trigger_atom's "click" action (field id)

    /**
     * The device is found by its name ("BO LUFS") on the master, then on the project's tracks (groups
     * included), once a second. Its hidden readout atoms ("BO LUFS M" ...) hold the values its Nitro
     * code sends every 100 ms; their "value" fields are read here.
     */
    private void readLufs() {
        long now = System.currentTimeMillis();
        if (now >= lufsSearchAt) {
            lufsSearchAt = now + 1000;
            try {
                findLufs();
            } catch (Throwable failure) {
                lufsFound = false;
            }
        }
        if (!lufsFound) return;
        for (int i = 0; i < 4; i++) lufs[i] = number(lufsFields[i], -100);
        lufsRef = number(lufsRefField, -14);
    }

    private static double number(Dgc field, double fallback) {
        try {
            Object v = field != null ? field.getValue() : null;
            return v instanceof Number n ? n.doubleValue() : fallback;
        } catch (Throwable failure) {
            return fallback;
        }
    }

    private void findLufs() {
        java.util.Arrays.fill(lufsFields, null);
        lufsRefField = null;
        lufsTrigger = null;
        lufsFound = false;
        pHd project = window.mtS();
        if (project == null) return;
        List<RYd> channels = new ArrayList<>();
        if (master != null) channels.add(master);
        kWY root = project.VpW();
        if (root != null) collectTracks(root, channels, 0);
        for (RYd channel : channels) {
            FAu chain = channel.Ucl();
            if (chain == null) continue;
            for (Object d : chain.NC()) {
                if (!(d instanceof jsd device)) continue;
                if (!isNamed(text(device.Z1C(), ""), "bo LUFS")) continue;
                if (bindLufs(device.OzW())) return;
            }
        }
    }

    /** Whether a device's name starts with this one in any case ("bo LUFS", or "BO LUFS" before the rename). */
    private static boolean isNamed(String deviceName, String prefix) {
        return deviceName.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    private static void collectTracks(hgq group, List<RYd> out, int depth) {
        if (depth > 6) return;
        int n = group.cU_(); // cU_(): its channels
        for (int i = 0; i < n; i++) {
            RYd track = group.cfM(i);
            if (track == null) continue;
            out.add(track);
            if (track instanceof hgq inner && inner != group) collectTracks(inner, out, depth + 1);
        }
    }

    /** Looks through the device's objects (a few levels) for its readout atoms and its Ref.LUFS knob. */
    private boolean bindLufs(Object inner) {
        if (!(inner instanceof oDc start)) return false;
        java.util.ArrayDeque<oDc> queue = new java.util.ArrayDeque<>();
        java.util.Set<oDc> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        java.util.Map<oDc, Integer> depth = new java.util.IdentityHashMap<>();
        queue.add(start);
        seen.add(start);
        depth.put(start, 0);
        int visited = 0;
        while (!queue.isEmpty() && visited < 3000) {
            oDc o = queue.poll();
            visited++;
            String name = fieldText(o, "name");
            if (name != null) {
                for (int i = 0; i < 4; i++) if (name.equals(LUFS_ATOMS[i])) lufsFields[i] = valueField(o);
                if (name.equals("Ref.LUFS")) lufsRefField = valueField(o);
            }
            if (fieldText(o, "trigger_toggle") != null) lufsTrigger = o; // the device's one trigger: Reset
            int d = depth.get(o);
            if (d >= 4) continue;
            for (Object field : o.nWt()) { // nWt(): the object's fields
                if (!(field instanceof bnq holder)) continue;
                int count = holder.MRY();
                for (int i = 0; i < count && i < 500; i++) {
                    Object child = holder.Hnt(i);
                    if (child instanceof oDc c && seen.add(c)) {
                        depth.put(c, d + 1);
                        queue.add(c);
                    }
                }
            }
        }
        lufsFound = lufsFields[0] != null && lufsFields[1] != null && lufsFields[2] != null && lufsFields[3] != null;
        return lufsFound;
    }

    // ---------- The master's own solo and mute (its mixer module's "solo" field and "mute" atom) ----------

    private lBQ masterSolo, masterMute;
    private ValueAtomParameterTarget muteTarget; // the mute atom as a controller reaches it (when it has no plain value)
    boolean soloOn, muteOn;
    boolean soloMuteFound;
    private long soloMuteSearchAt;

    private void readSoloMute() {
        if (master == null) { masterSolo = masterMute = null; muteTarget = null; soloMuteFound = false; return; }
        long now = System.currentTimeMillis();
        if ((masterSolo == null || (masterMute == null && muteTarget == null)) && now >= soloMuteSearchAt) {
            soloMuteSearchAt = now + 2000;
            StringBuilder log = new StringBuilder("solo / mute:");
            try {
                Jmw mixer = master.MIO(); // its mixer module
                if (mixer instanceof oDc m && m.VpW("solo") instanceof lBQ solo) masterSolo = solo;
                Object atom = mixer == null ? null : mixer.lYc(); // lYc(): its mute atom (as Bitwig's mixer button binds it)
                log.append(" mute atom ").append(atom == null ? "null" : atom.getClass().getSimpleName());
                if (atom instanceof oDc a) {
                    Dgc v = valueField(a);
                    log.append(", value ").append(v == null ? "null" : v.getClass().getSimpleName() + "=" + v.getValue());
                    if (v instanceof lBQ b) masterMute = b;
                }
                if (masterMute == null && atom instanceof v3U valueAtom) {
                    muteTarget = ValueAtomParameterTarget.createForAtom(valueAtom);
                    log.append(", as a parameter");
                }
            } catch (Throwable failure) {
                log.append(" failed: ").append(failure);
            }
            soloMuteFound = masterSolo != null || masterMute != null || muteTarget != null;
            debug(log + "; solo " + (masterSolo != null) + ", mute " + (masterMute != null || muteTarget != null));
        }
        try {
            soloOn = masterSolo != null && masterSolo.getBoolValue();
            if (masterMute != null) muteOn = masterMute.getBoolValue();
            else muteOn = muteTarget != null && muteTarget.getValueTarget().getCurrentNormalizedValue() > 0.5;
        } catch (Throwable failure) {
            soloOn = muteOn = false;
        }
    }

    /** Flips the master's solo (0) or mute (1), as its buttons in Bitwig's mixer do. */
    void toggleSoloMute(int which) {
        if (which == 1 && masterMute == null && muteTarget != null) {
            try {
                var target = muteTarget.getValueTarget();
                boolean on = target.getCurrentNormalizedValue() > 0.5;
                muteTarget.startedAdjusting(false, true);
                target.setCurrentValue(target.normalizedValueToValue(on ? 0 : 1));
                muteTarget.finishedAdjusting(false, true);
                muteOn = !on;
            } catch (Throwable failure) {
                MasterBoPanels.MOD.log("Master Bo could not switch the master's mute", failure);
            }
            return;
        }
        lBQ value = which == 0 ? masterSolo : masterMute;
        if (value == null) return;
        try {
            if (!value.isSettable()) return;
            value.setBoolValue(!value.getBoolValue());
            if (which == 0) soloOn = value.getBoolValue(); else muteOn = value.getBoolValue();
        } catch (Throwable failure) {
            MasterBoPanels.MOD.log("Master Bo could not switch the master's " + (which == 0 ? "solo" : "mute"), failure);
        }
    }

    // ---------- The master's remote controls (its track remote controls, as the mixer shows them) ----------
    //
    // The track (an iface WSA) -> hQi() -> hUR(): its remote controls (hrc); E3(): the page shown;
    // the page's FzB() slots, each cfM(i) a remote control (waW): IwV() its name, TMF().hUR() the list
    // of what it is mapped to. The value shown and set is the first mapped parameter's, as a controller
    // reaches it (ValueAtomParameterTarget).

    static final class Macro {
        String name = "";
        boolean assigned;
        ValueAtomParameterTarget target;
        double norm;
        int kind;             // 0 a knob (continuous), 1 a button (two values), 2 a choice (more)
        int count;            // discrete values (button, choice)
        String valueText = ""; // the choice's current value, as Bitwig names it
    }

    final List<Macro> macros = new ArrayList<>();
    private long macrosSearchAt;

    private void readMacros() {
        long now = System.currentTimeMillis();
        if (now >= macrosSearchAt) {
            macrosSearchAt = now + 1000;
            try {
                findMacros();
            } catch (Throwable failure) {
                macros.clear();
                debug("remote controls failed: " + failure);
            }
        }
        for (Macro m : macros) {
            try {
                if (m.target == null) continue;
                var t = m.target.getValueTarget();
                m.norm = clamp(t.getCurrentNormalizedValue());
                if (t.isContinuous()) { m.kind = 0; m.count = 0; }
                else {
                    m.count = t.discreteValueCount();
                    m.kind = m.count == 2 ? 1 : 2;
                    if (m.kind == 2) {
                        String[] names = t.discreteValueNames();
                        int index = (int) Math.round(m.norm * Math.max(1, m.count - 1));
                        m.valueText = names != null && index >= 0 && index < names.length ? names[index] : String.valueOf(index + 1);
                    }
                }
            } catch (Throwable failure) {
                m.target = null;
            }
        }
    }

    private void findMacros() {
        List<Macro> found = new ArrayList<>();
        if ((Object) master instanceof com.bitwig.flt.document.core.iface.WSA track) {
            Object controls = track.hQi().hUR(); // the track's remote controls
            if (controls instanceof bitwig.obf.hrc rc) {
                bitwig.obf.FyM page = rc.E3(); // the page shown
                int n = page == null ? 0 : 8; // a page has 8 (its FzB() is not the count)
                for (int i = 0; i < n; i++) {
                    bitwig.obf.waW slot;
                    try {
                        slot = page.cfM(i);
                    } catch (Throwable outOfRange) {
                        break;
                    }
                    if (slot == null) break;
                    Macro m = new Macro();
                    if (slot != null) {
                        Object name = slot.IwV().get();
                        m.name = name == null ? "" : name.toString();
                        Object targets = slot.TMF().hUR(); // what it is mapped to
                        if (targets instanceof bnq list && list.MRY() > 0) {
                            v3U atom = findAtom(list.Hnt(0));
                            if (atom != null) m.target = ValueAtomParameterTarget.createForAtom(atom);
                            m.assigned = true;
                        }
                    }
                    found.add(m);
                }
            }
        }
        // keep the old objects (a drag holds one) when nothing changed
        boolean same = found.size() == macros.size();
        for (int i = 0; same && i < found.size(); i++)
            same = found.get(i).name.equals(macros.get(i).name) && found.get(i).assigned == macros.get(i).assigned
                   && (found.get(i).target == null) == (macros.get(i).target == null);
        if (!same) {
            macros.clear();
            macros.addAll(found);
            debug("remote controls: " + found.size() + " " + found.stream().map(m -> m.name + (m.target != null ? "*" : "")).toList());
        }
    }

    /** The parameter a mapping points at: the mapping itself, or the first value atom it holds or refers to. */
    private static v3U findAtom(Object start) {
        if (start instanceof v3U v) return v;
        if (!(start instanceof oDc first)) return null;
        java.util.ArrayDeque<oDc> queue = new java.util.ArrayDeque<>();
        java.util.Map<oDc, Integer> depth = new java.util.IdentityHashMap<>();
        queue.add(first);
        depth.put(first, 0);
        while (!queue.isEmpty() && depth.size() < 400) {
            oDc o = queue.poll();
            int d = depth.get(o);
            for (Object field : o.nWt()) {
                java.util.List<Object> next = new ArrayList<>();
                if (field instanceof bnq holder) for (int i = 0; i < holder.MRY() && i < 50; i++) next.add(holder.Hnt(i));
                else if (field instanceof Dgc value) next.add(value.getValue());
                for (Object c : next) {
                    if (c instanceof v3U v) return v;
                    if (c instanceof oDc child && d < 3 && !depth.containsKey(child)) { depth.put(child, d + 1); queue.add(child); }
                }
            }
        }
        return null;
    }

    void startMacro(Macro m) {
        try { if (m.target != null) m.target.startedAdjusting(false, true); } catch (Throwable ignored) { }
    }

    void setMacro(Macro m, double norm) {
        if (m.target == null) return;
        try {
            var t = m.target.getValueTarget();
            m.norm = clamp(norm);
            t.setCurrentValue(t.normalizedValueToValue(m.norm));
        } catch (Throwable failure) {
            MasterBoPanels.MOD.log("Master Bo could not set a remote control", failure);
        }
    }

    void finishMacro(Macro m) {
        try { if (m.target != null) m.target.finishedAdjusting(false, true); } catch (Throwable ignored) { }
    }

    /** A button flips; a choice steps to its next value (after the last: the first). */
    void stepMacro(Macro m) {
        if (m.target == null) return;
        try {
            var t = m.target.getValueTarget();
            double next;
            if (m.kind == 1) next = m.norm > 0.5 ? 0 : 1;
            else {
                int count = Math.max(2, m.count), index = (int) Math.round(m.norm * (count - 1));
                next = ((index + 1) % count) / (double) (count - 1);
            }
            m.target.startedAdjusting(false, true);
            t.setCurrentValue(t.normalizedValueToValue(next));
            m.target.finishedAdjusting(false, true);
            m.norm = next;
        } catch (Throwable failure) {
            MasterBoPanels.MOD.log("Master Bo could not switch a remote control", failure);
        }
    }

    /** A choice's values, as Bitwig names them. */
    String[] macroNames(Macro m) {
        try {
            if (m.target == null) return new String[0];
            var t = m.target.getValueTarget();
            String[] names = t.discreteValueNames();
            if (names != null && names.length > 0) return names;
            int count = Math.max(0, t.discreteValueCount());
            String[] numbers = new String[count];
            for (int i = 0; i < count; i++) numbers[i] = String.valueOf(i + 1);
            return numbers;
        } catch (Throwable failure) {
            return new String[0];
        }
    }

    /** Sets a choice to one of its values (index of count). */
    void chooseMacro(Macro m, int index, int count) {
        if (m.target == null || count < 2) return;
        try {
            var t = m.target.getValueTarget();
            double v = index / (double) (count - 1);
            double before = t.getCurrentValue();
            m.target.startedAdjusting(false, true);
            t.setCurrentValue(t.normalizedValueToValue(v));
            m.target.finishedAdjusting(false, true);
            debug("choose " + index + "/" + count + ": value " + before + " -> " + t.getCurrentValue() + " (asked "
                  + t.normalizedValueToValue(v) + ", settable " + t.isSettable() + ")");
            m.norm = v;
        } catch (Throwable failure) {
            MasterBoPanels.MOD.log("Master Bo could not choose a value", failure);
        }
    }

    void resetMacro(Macro m) {
        try { if (m.target != null) m.target.setToDefault(); } catch (Throwable ignored) { }
    }

    // ---------- Keeping BO Spectrum's settings and the spectrum's here in step ----------
    //
    // BO Spectrum's choices (Slope, Mode, View, Hold, Hold time) and Master Bo's (slope, M/S, 1/3 Oct |
    // Plot, Hold, its time) are one setting each: a change on either side shows on the other. The
    // device's values are read every refresh; one that changed since the last read is taken over here,
    // and a change made here is written to the device.

    private static final String[] SYNC_NAMES = {"Slope", "Mode", "View", "Hold", "Hold time"};
    private final Dgc[] syncFields = new Dgc[5];
    private final int[] syncSeen = {-1, -1, -1, -1, -1};

    private int localSetting(int i) {
        return switch (i) {
            case 0 -> slopeChoice;
            case 1 -> msMode ? 1 : 0;
            case 2 -> thirdOctave ? 0 : 1;   // the device: 0 Bars, 1 Plot
            case 3 -> holdOn ? 1 : 0;
            default -> holdChoice;
        };
    }

    private void applyLocal(int i, int v) {
        switch (i) {
            case 0 -> setSlope(v);
            case 1 -> { if ((v == 1) != msMode) toggleMs(); }
            case 2 -> { if ((v == 0) != thirdOctave) toggleThirdOctave(); }
            case 3 -> { if ((v == 1) != holdOn) toggleHold(); }
            default -> { if (v >= 0 && v < HOLD_SECONDS.length && v != holdChoice) { holdChoice = v - 1; nextHoldTime(); } }
        }
    }

    private static int readSetting(Dgc f) {
        Object v = f.getValue();
        if (v instanceof Boolean b) return b ? 1 : 0;
        if (v instanceof Number n) return n.intValue();
        return -1;
    }

    private static void writeSetting(Dgc f, int v) {
        try {
            if (!f.isSettable()) return;
            if (f instanceof lBQ b) { b.setBoolValue(v != 0); return; }
            Object cur = f.getValue();
            Object boxed = cur instanceof Long ? (Object) (long) v : cur instanceof Short ? (Object) (short) v
                         : cur instanceof Byte ? (Object) (byte) v : cur instanceof Double ? (Object) (double) v : (Object) v;
            f.getClass().getMethod("setValue", Object.class).invoke(f, boxed);
        } catch (Throwable failure) {
            MasterBoPanels.MOD.log("Master Bo could not set BO Spectrum", failure);
        }
    }

    /** Every refresh: the device's changes come here; then this side's settings go to the device. */
    private void syncSpectrumSettings() {
        for (int i = 0; i < 5; i++) {
            Dgc f = syncFields[i];
            if (f == null) continue;
            try {
                int dev = readSetting(f);
                if (dev < 0) continue;
                if (syncSeen[i] >= 0 && dev != syncSeen[i]) applyLocal(i, dev); // changed on the device
                int local = localSetting(i);
                if (dev != local) writeSetting(f, local);                        // changed here (or new device)
                syncSeen[i] = readSetting(f);
            } catch (Throwable failure) {
                debug("spectrum sync failed: " + failure);
            }
        }
    }

    private void findSyncFields(Object inner) {
        java.util.Arrays.fill(syncFields, null);
        java.util.Arrays.fill(syncSeen, -1);
        if (!(inner instanceof oDc start)) return;
        java.util.ArrayDeque<oDc> queue = new java.util.ArrayDeque<>();
        java.util.Set<oDc> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        queue.add(start);
        seen.add(start);
        int visited = 0;
        while (!queue.isEmpty() && visited < 3000) {
            oDc o = queue.poll();
            visited++;
            String name = fieldText(o, "name");
            if (name != null) {
                for (int i = 0; i < 5; i++) {
                    if (name.equals(SYNC_NAMES[i]) && syncFields[i] == null) {
                        Dgc v = valueField(o);
                        if (v != null) syncFields[i] = v;
                    }
                }
            }
            for (Object field : o.nWt()) {
                if (!(field instanceof bnq holder)) continue;
                int count = holder.MRY();
                for (int k = 0; k < count && k < 500; k++) {
                    Object child = holder.Hnt(k);
                    if (child instanceof oDc c && seen.add(c)) queue.add(c);
                }
            }
        }
        StringBuilder log = new StringBuilder("spectrum sync:");
        for (int i = 0; i < 5; i++) log.append(' ').append(SYNC_NAMES[i]).append('=').append(syncFields[i] == null ? "-" : String.valueOf(syncFields[i].getValue()));
        debug(log.toString());
    }

    // ---------- BO Spectrum (a pass-through device holding Bitwig's own spectrum analyser) ----------

    /** 32 bands, 1/3 octave apart from 20 Hz to 20 kHz (centres), in dB (slope applied), -200 = nothing. */
    static final int BANDS = 31;
    final double[] bands = new double[BANDS];   // L/R: the stereo signal
    final double[] bandsM = new double[BANDS];  // M/S: mid
    final double[] bandsS = new double[BANDS];  // M/S: side
    // Plot: a curve of CURVE_N points, 20 Hz .. 20 kHz evenly on the log scale, each the mean power over a
    // sixth of an octave around it (stereo, mid, side)
    static final int CURVE_N = 200;
    final double[] curve = new double[CURVE_N], curveM = new double[CURVE_N], curveS = new double[CURVE_N];
    // the peak hold of each (bars and curve), and when each was last raised (ms)
    final double[] bandsH = new double[BANDS], bandsMH = new double[BANDS], bandsSH = new double[BANDS];
    final double[] curveH = new double[CURVE_N], curveMH = new double[CURVE_N], curveSH = new double[CURVE_N];
    private final long[] bandsT = new long[BANDS], bandsMT = new long[BANDS], bandsST = new long[BANDS];
    private final long[] curveT = new long[CURVE_N], curveMT = new long[CURVE_N], curveST = new long[CURVE_N];

    /** The frequency of curve point p. */
    static double curveHz(int p) {
        return 20 * Math.pow(1000, p / (double) (CURVE_N - 1));
    }
    boolean spectrumFound;
    boolean msMode = readMs();                  // M/S mode (else L/R)
    private final List<bitwig.obf.WIj> analysers = new ArrayList<>(); // stereo, mid, side (BO Spectrum's order)
    private final List<bitwig.obf.tF> streams = new ArrayList<>();
    private long spectrumSearchAt;
    private long lastSpectrumNanos;
    private boolean spectrumLogged;
    private static final String MS_KEY = "spectrum_ms";
    { clearSpectrum(); }

    /** A click on the spectrum's scales: the levels and holds start again. */
    void resetSpectrum() {
        clearSpectrum();
    }

    private void clearSpectrum() {
        for (double[] a : new double[][] {bands, bandsM, bandsS, curve, curveM, curveS, bandsH, bandsMH, bandsSH, curveH, curveMH, curveSH})
            java.util.Arrays.fill(a, -200);
    }

    // ---------- Peak hold: on/off and its time (1, 3, 10 s or for ever), kept in the settings ----------
    static final double[] HOLD_SECONDS = {1, 3, 10, Double.POSITIVE_INFINITY};
    static final String[] HOLD_LABELS = {"1 s", "3 s", "10 s", "\u221e"};
    private static final String HOLD_KEY = "spectrum_hold", HOLD_TIME_KEY = "spectrum_hold_time";
    boolean holdOn = readBool(HOLD_KEY, false);
    int holdChoice = readHoldChoice();

    private static boolean readBool(String key, boolean otherwise) {
        try {
            return MasterBoPanels.MOD.settings().getBoolean(key, otherwise);
        } catch (Throwable failure) {
            return otherwise;
        }
    }

    private static int readHoldChoice() {
        try {
            int v = MasterBoPanels.MOD.settings().getInt(HOLD_TIME_KEY, 1);
            return v >= 0 && v < HOLD_SECONDS.length ? v : 1;
        } catch (Throwable failure) {
            return 1;
        }
    }

    void toggleHold() {
        holdOn = !holdOn;
        for (double[] a : new double[][] {bandsH, bandsMH, bandsSH, curveH, curveMH, curveSH}) java.util.Arrays.fill(a, -200);
        try {
            MasterBoPanels.MOD.settings().putBoolean(HOLD_KEY, holdOn);
        } catch (Throwable failure) {
            MasterBoPanels.MOD.log("Master Bo could not keep the spectrum hold", failure);
        }
    }

    void nextHoldTime() {
        holdChoice = (holdChoice + 1) % HOLD_SECONDS.length;
        try {
            MasterBoPanels.MOD.settings().putInt(HOLD_TIME_KEY, holdChoice);
        } catch (Throwable failure) {
            MasterBoPanels.MOD.log("Master Bo could not keep the spectrum hold time", failure);
        }
    }

    /** The hold follows each value up at once, keeps it for the hold time, then falls 12 dB/s (never under the value). */
    private void updateHold(double[] v, double[] h, long[] t, double dt, long now) {
        if (!holdOn) return;
        double keep = HOLD_SECONDS[holdChoice] * 1000;
        for (int i = 0; i < v.length; i++) {
            if (v[i] >= h[i]) { h[i] = v[i]; t[i] = now; }
            else if (now - t[i] > keep) h[i] = Math.max(v[i], h[i] - 12 * dt);
        }
    }

    private static boolean readMs() {
        try {
            return MasterBoPanels.MOD.settings().getBoolean(MS_KEY, false);
        } catch (Throwable failure) {
            return false;
        }
    }

    /** Plot: each band is the mean of its bins (an FFT plot's level); 1/3 Oct: that mean over the 1 kHz band's width (+10 log(231.6 Hz / bin)), as the reference analyser's third-octave view reads. */
    boolean thirdOctave = readThirdOctave();
    private static final String THIRD_KEY = "spectrum_third_octave";

    private static boolean readThirdOctave() {
        try {
            return MasterBoPanels.MOD.settings().getBoolean(THIRD_KEY, false);
        } catch (Throwable failure) {
            return false;
        }
    }

    void toggleThirdOctave() {
        thirdOctave = !thirdOctave;
        try {
            MasterBoPanels.MOD.settings().putBoolean(THIRD_KEY, thirdOctave);
        } catch (Throwable failure) {
            MasterBoPanels.MOD.log("Master Bo could not keep the spectrum reading", failure);
        }
    }

    void toggleMs() {
        msMode = !msMode;
        try {
            MasterBoPanels.MOD.settings().putBoolean(MS_KEY, msMode);
        } catch (Throwable failure) {
            MasterBoPanels.MOD.log("Master Bo could not keep the spectrum mode", failure);
        }
    }

    /** Whether the M/S mode can be shown (a BO Spectrum with mid and side analysers). */
    boolean msAvailable() {
        return analysers.size() >= 3;
    }

    private void readSpectrum() {
        long now = System.currentTimeMillis();
        if (now >= spectrumSearchAt) {
            spectrumSearchAt = now + 1000;
            try {
                findSpectrum();
            } catch (Throwable failure) {
                unbindSpectrum();
                debug("spectrum search failed: " + failure);
            }
        }
        if (analysers.isEmpty()) return;
        try {
            long t = System.nanoTime();
            double dt = lastSpectrumNanos == 0 ? 0 : Math.min(0.2, (t - lastSpectrumNanos) / 1e9);
            lastSpectrumNanos = t;
            bitwig.obf.WIj main = analysers.get(0);
            long ms = System.currentTimeMillis();
            boolean both = msMode && analysers.size() >= 3; // M/S: only mid and side are drawn
            if (thirdOctave) { // bars
                if (!both) {
                    computeBands(main.cfM(), main.FzB(), bands, dt); // cfM(): the bins; FzB(): the sample rate
                    updateHold(bands, bandsH, bandsT, dt, ms);
                } else {
                    computeBands(analysers.get(1).cfM(), analysers.get(1).FzB(), bandsM, dt);
                    computeBands(analysers.get(2).cfM(), analysers.get(2).FzB(), bandsS, dt);
                    updateHold(bandsM, bandsMH, bandsMT, dt, ms);
                    updateHold(bandsS, bandsSH, bandsST, dt, ms);
                }
            } else { // Plot: the curve
                if (!both) {
                    computeCurve(main.cfM(), main.FzB(), curve, dt);
                    updateHold(curve, curveH, curveT, dt, ms);
                } else {
                    computeCurve(analysers.get(1).cfM(), analysers.get(1).FzB(), curveM, dt);
                    computeCurve(analysers.get(2).cfM(), analysers.get(2).FzB(), curveS, dt);
                    updateHold(curveM, curveMH, curveMT, dt, ms);
                    updateHold(curveS, curveSH, curveST, dt, ms);
                }
            }
        } catch (Throwable failure) {
            debug("spectrum read failed: " + failure);
            unbindSpectrum();
        }
    }

    private void findSpectrum() {
        pHd project = window.mtS();
        if (project == null) { unbindSpectrum(); return; }
        List<RYd> channels = new ArrayList<>();
        if (master != null) channels.add(master);
        kWY root = project.VpW();
        if (root != null) collectTracks(root, channels, 0);
        for (RYd channel : channels) {
            FAu chain = channel.Ucl();
            if (chain == null) continue;
            for (Object d : chain.NC()) {
                if (!(d instanceof jsd device)) continue;
                if (!isNamed(text(device.Z1C(), ""), "bo Spectrum")) continue;
                List<bitwig.obf.WIj> found = findAnalysers(device.OzW());
                if (!found.isEmpty()) {
                    if (!found.equals(analysers)) {
                        bindSpectrum(found);
                        findSyncFields(device.OzW());
                    }
                    return;
                }
            }
        }
        unbindSpectrum();
    }

    /** The device's analysers, in its order (a breadth-first walk of its objects). */
    private static List<bitwig.obf.WIj> findAnalysers(Object inner) {
        List<bitwig.obf.WIj> out = new ArrayList<>();
        if (!(inner instanceof oDc start)) return out;
        java.util.ArrayDeque<oDc> queue = new java.util.ArrayDeque<>();
        java.util.Set<oDc> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        queue.add(start);
        seen.add(start);
        int visited = 0;
        while (!queue.isEmpty() && visited < 3000) {
            oDc o = queue.poll();
            visited++;
            if (o instanceof bitwig.obf.WIj a) { out.add(a); continue; }
            for (Object field : o.nWt()) {
                if (!(field instanceof bnq holder)) continue;
                int count = holder.MRY();
                for (int i = 0; i < count && i < 500; i++) {
                    Object child = holder.Hnt(i);
                    if (child instanceof oDc c && seen.add(c)) queue.add(c);
                }
            }
        }
        return out;
    }

    private void bindSpectrum(List<bitwig.obf.WIj> found) {
        unbindSpectrum();
        for (bitwig.obf.WIj a : found) {
            analysers.add(a);
            bitwig.obf.tF stream = a.iCS(); // the engine's stream of its data, as Bitwig's spectrum display starts it
            if (stream != null) {
                stream.cfM();
                streams.add(stream);
            }
        }
        spectrumFound = true;
        spectrumLogged = false;
        debug("spectrum: bound " + found.size() + " analyser(s)");
    }

    private void unbindSpectrum() {
        for (bitwig.obf.tF stream : streams) {
            try {
                stream.FzB();
            } catch (Throwable ignored) {
            }
        }
        streams.clear();
        analysers.clear();
        spectrumFound = false;
        clearSpectrum();
        java.util.Arrays.fill(syncFields, null);
    }

    /** Band centres: the 31 ISO third-octave bands, 20 Hz to 20 kHz (base ten: 1 kHz is band 17). */
    static double bandCentre(int b) {
        return 1000 * Math.pow(10, (b - 17) / 10.0);
    }

    /** The (fractional) band of a frequency, for the grid. */
    static double bandOf(double hz) {
        return 17 + 10 * Math.log10(hz / 1000);
    }

    int slopeChoice = readSlope(); // 0: 0 dB/oct, 1: 3 dB/oct, 2: 4.5 dB/oct (pivot 1 kHz)
    static final double[] SLOPES = {0, 3, 4.5};
    static final String[] SLOPE_LABELS = {"0 dB", "3 dB", "4.5 dB"};
    private static final String SLOPE_KEY = "spectrum_slope";

    private static int readSlope() {
        try {
            int v = MasterBoPanels.MOD.settings().getInt(SLOPE_KEY, 2);
            return v >= 0 && v < 3 ? v : 2;
        } catch (Throwable failure) {
            return 2;
        }
    }

    void nextSlope() {
        setSlope((slopeChoice + 1) % 3);
    }

    void setSlope(int choice) {
        slopeChoice = Math.max(0, Math.min(2, choice));
        try {
            MasterBoPanels.MOD.settings().putInt(SLOPE_KEY, slopeChoice);
        } catch (Throwable failure) {
            MasterBoPanels.MOD.log("Master Bo could not keep the spectrum slope", failure);
        }
    }

    /**
     * Bins (linear amplitude, 0 .. nyquist) into 32 bands: each band's level is the mean power of the
     * bins within it (the nearest bin's when none falls inside), in dB, as an FFT analyser shows a bin:
     * pink noise then falls 3 dB per octave, which the slope (dB per octave around 1 kHz) tilts back,
     * as in analysers with a 3 / 4.5 dB slope. (A sum per band, as an RTA reads, already makes pink
     * noise flat: with a slope on top the highs read far too high.) The bars fall at 24 dB/s.
     */
    private void computeBands(float[] bins, double sampleRate, double[] out, double dt) {
        if (bins == null || bins.length < 8 || !(sampleRate > 1000)) return;
        int n = bins.length;
        double binHz = sampleRate / 2 / (n - 1);
        if (!spectrumLogged) {
            spectrumLogged = true;
            float max = 0;
            for (float v : bins) max = Math.max(max, v);
            debug("spectrum: " + n + " bins, " + sampleRate + " Hz, max " + max);
        }
        double slope = SLOPES[slopeChoice];
        for (int b = 0; b < BANDS; b++) {
            double fc = bandCentre(b);
            double lo = fc / Math.pow(10, 0.05), hi = fc * Math.pow(10, 0.05);
            int i0 = (int) Math.ceil(lo / binHz), i1 = (int) Math.floor(hi / binHz);
            double power = 0;
            if (i1 - i0 >= 4) {
                int count = 0;
                for (int k = Math.max(0, i0); k <= i1 && k < n; k++) { power += (double) bins[k] * bins[k]; count++; }
                if (count > 0) power /= count; // the mean of the band's bins
            } else {
                // a band only a few bins wide (the bass): the mean of the power spectrum over the band's
                // exact edges, the bins' powers joined by straight lines (no steps where bands share a bin)
                int m = 24;
                for (int j = 0; j < m; j++) {
                    double pos = (lo + (hi - lo) * (j + 0.5) / m) / binHz;
                    int k = (int) Math.floor(pos);
                    double f = pos - k;
                    if (k < 1 || k + 1 >= n) continue; // never the DC bin
                    double p0 = (double) bins[k] * bins[k], p1 = (double) bins[k + 1] * bins[k + 1];
                    power += p0 + (p1 - p0) * f;
                }
                power /= m;
            }
            double dB = power > 1e-20 ? 10 * Math.log10(power) : -200;
            // 1/3 Oct: the band's level as a third-octave analyser reads it: the mean power over the
            // width of the 1 kHz band (231.6 Hz), the same lift in every band
            if (thirdOctave && dB > -199) dB += 10 * Math.log10(231.6 / binHz);
            if (dB > -199) dB += slope * Math.log(fc / 1000) / Math.log(2);
            double fallen = out[b] - 24 * dt;
            out[b] = Math.max(dB, fallen);
        }
    }

    /**
     * Plot: the curve. Each point the mean power over a sixth of an octave around its frequency (the
     * bins joined by straight lines where the span is only a few bins wide, so the bass is smooth), in
     * dB as an FFT plot reads (the same level as the Plot bars had), plus the slope; falling 24 dB/s.
     */
    private void computeCurve(float[] bins, double sampleRate, double[] out, double dt) {
        if (bins == null || bins.length < 8 || !(sampleRate > 1000)) return;
        int n = bins.length;
        double binHz = sampleRate / 2 / (n - 1);
        double slope = SLOPES[slopeChoice];
        double half = Math.pow(2, 1 / 12.0);
        for (int p = 0; p < CURVE_N; p++) {
            double fc = curveHz(p);
            double lo = fc / half, hi = fc * half;
            int i0 = (int) Math.ceil(lo / binHz), i1 = (int) Math.floor(hi / binHz);
            double power = 0;
            if (i1 - i0 >= 4) {
                int count = 0;
                for (int k = Math.max(1, i0); k <= i1 && k < n; k++) { power += (double) bins[k] * bins[k]; count++; }
                if (count > 0) power /= count;
            } else {
                int m = 16;
                for (int j = 0; j < m; j++) {
                    double pos = (lo + (hi - lo) * (j + 0.5) / m) / binHz;
                    int k = (int) Math.floor(pos);
                    double f = pos - k;
                    if (k < 1 || k + 1 >= n) continue;
                    double p0 = (double) bins[k] * bins[k], p1 = (double) bins[k + 1] * bins[k + 1];
                    power += p0 + (p1 - p0) * f;
                }
                power /= m;
            }
            double dB = power > 1e-20 ? 10 * Math.log10(power) : -200;
            if (dB > -199) dB += slope * Math.log(fc / 1000) / Math.log(2);
            out[p] = Math.max(dB, out[p] - 24 * dt);
        }
    }

    // ---------- BO Scope (a pass-through device holding Bitwig's own oscilloscope, L on A, R on B) ----------

    boolean scopeFound;
    float[] scopeL = new float[0], scopeR = new float[0]; // the latest window of samples (L, R)
    int scopeCount;                                        // how many of them are valid
    double correlation;                                     // -1 .. +1, smoothed
    private bitwig.obf.anM scope;
    private bitwig.obf.tF scopeStream;
    private long scopeSearchAt;
    private boolean scopeLogged;

    private void readScope() {
        long now = System.currentTimeMillis();
        if (now >= scopeSearchAt) {
            scopeSearchAt = now + 1000;
            try {
                findScope();
            } catch (Throwable failure) {
                unbindScope();
                debug("scope search failed: " + failure);
            }
        }
        if (scope == null) return;
        try {
            Object data = scope.FzB(); // FzB(): the oscilloscope's data (its two sample buffers)
            if (!(data instanceof bitwig.obf.nng d)) return;
            float[] a = d.cfM(), b = d.FzB(); // cfM(): input A (L); FzB(): input B (R)
            if (a == null || b == null) return;
            int n = Math.min(a.length, b.length);
            if (!scopeLogged) {
                scopeLogged = true;
                debug("scope: A " + a.length + ", B " + b.length + ", TMF " + d.TMF() + ", E3 " + d.E3() + ", hUR " + d.hUR());
            }
            accumulate(a, b, n);
            double lr = 0, ll = 0, rr = 0;
            for (int i = 0; i < n; i++) { lr += (double) a[i] * b[i]; ll += (double) a[i] * a[i]; rr += (double) b[i] * b[i]; }
            double c = ll > 1e-12 && rr > 1e-12 ? lr / Math.sqrt(ll * rr) : (ll + rr > 1e-12 ? 0 : 1);
            if (Double.isFinite(c)) correlation += 0.25 * (c - correlation);
        } catch (Throwable failure) {
            debug("scope read failed: " + failure);
            unbindScope();
        }
    }

    // ---------- The goniometer's persistence: a grid of glow over x = (R - L) / 2 (side, across) and
    // y = (L + R) / 2 (mid, up), from -GONIO_RANGE to +GONIO_RANGE (1 = full-scale mono at the radius;
    // every sample within full scale stays inside the diamond |x| + |y| <= 1).
    // Every sample lands as a soft 3 x 3 spot (blur); every frame the grid fades, to about 5 % in 1 s.

    static final int GONIO_N = 200;
    static final double GONIO_RANGE = 1.1;
    final float[] gonio = new float[GONIO_N * GONIO_N];
    int gonioFrame;
    private long gonioAt;
    private int lastHash;
    private static final float[] SPOT = {0.25F, 0.5F, 0.25F, 0.5F, 1F, 0.5F, 0.25F, 0.5F, 0.25F};

    private void accumulate(float[] a, float[] b, int n) {
        long now = System.nanoTime();
        double dt = gonioAt == 0 ? 0 : Math.min(0.5, (now - gonioAt) / 1e9);
        gonioAt = now;
        float decay = (float) Math.exp(-3 * dt);
        boolean any = false;
        for (int i = 0; i < gonio.length; i++) {
            float v = gonio[i];
            if (v == 0) continue;
            v *= decay;
            gonio[i] = v < 0.004F ? 0 : v;
            any |= v >= 0.004F;
        }
        // a new window only (the same buffer read again adds nothing)
        int hash = n;
        for (int i = 0; i < n; i += Math.max(1, n / 32)) hash = hash * 31 + Float.floatToIntBits(a[i]) + 7 * Float.floatToIntBits(b[i]);
        if (hash != lastHash && n > 0) {
            lastHash = hash;
            float w = (float) Math.min(0.6, 300.0 / n); // the glow per sample: a frame's worth reads alike for any window
            double scale = GONIO_N / (2 * GONIO_RANGE), k = 0.5; // M = (L + R) / 2, S = (R - L) / 2: full-scale mono at the radius
            for (int i = 0; i < n; i++) {
                double x = (b[i] - a[i]) * k, y = (a[i] + b[i]) * k;
                int gx = (int) Math.floor((x + GONIO_RANGE) * scale), gy = (int) Math.floor((GONIO_RANGE - y) * scale);
                if (gx < 1 || gy < 1 || gx >= GONIO_N - 1 || gy >= GONIO_N - 1) continue;
                int c = gy * GONIO_N + gx;
                for (int dy = -1, s = 0; dy <= 1; dy++)
                    for (int dx = -1; dx <= 1; dx++, s++) gonio[c + dy * GONIO_N + dx] += w * SPOT[s];
            }
            any = true;
        }
        if (any) gonioFrame++;
    }

    private void findScope() {
        pHd project = window.mtS();
        if (project == null) { unbindScope(); return; }
        List<RYd> channels = new ArrayList<>();
        if (master != null) channels.add(master);
        kWY root = project.VpW();
        if (root != null) collectTracks(root, channels, 0);
        for (RYd channel : channels) {
            FAu chain = channel.Ucl();
            if (chain == null) continue;
            for (Object d : chain.NC()) {
                if (!(d instanceof jsd device)) continue;
                if (!isNamed(text(device.Z1C(), ""), "bo Scope")) continue;
                bitwig.obf.anM found = findScopeAtom(device.OzW());
                if (found != null) {
                    if (found != scope) bindScope(found);
                    return;
                }
            }
        }
        unbindScope();
    }

    private static bitwig.obf.anM findScopeAtom(Object inner) {
        if (!(inner instanceof oDc start)) return null;
        java.util.ArrayDeque<oDc> queue = new java.util.ArrayDeque<>();
        java.util.Set<oDc> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        queue.add(start);
        seen.add(start);
        int visited = 0;
        while (!queue.isEmpty() && visited < 3000) {
            oDc o = queue.poll();
            visited++;
            if (o instanceof bitwig.obf.anM a) return a;
            for (Object field : o.nWt()) {
                if (!(field instanceof bnq holder)) continue;
                int count = holder.MRY();
                for (int i = 0; i < count && i < 500; i++) {
                    Object child = holder.Hnt(i);
                    if (child instanceof oDc c && seen.add(c)) queue.add(c);
                }
            }
        }
        return null;
    }

    private void bindScope(bitwig.obf.anM found) {
        unbindScope();
        scope = found;
        scopeStream = found.iCS(); // the engine's stream of its data, as Bitwig's scope display starts it
        if (scopeStream != null) scopeStream.cfM();
        scopeFound = true;
        scopeLogged = false;
        debug("scope: bound " + found.getClass().getSimpleName());
    }

    private void unbindScope() {
        if (scopeStream != null) {
            try {
                scopeStream.FzB();
            } catch (Throwable ignored) {
            }
        }
        scopeStream = null;
        scope = null;
        scopeFound = false;
        scopeCount = 0;
        correlation = 0;
        java.util.Arrays.fill(gonio, 0);
    }

    // ---------- Selecting an insert (so Bitwig's own Delete / Backspace removes it) ----------

    /** The insert last selected from Master Bo (shown highlighted while it is still selected). */
    jsd selectedInsert;

    /**
     * Selects the insert as a click on it in the device chain does: the master's device selection
     * (the window's, as Bitwig's device panel uses it) holds only it and becomes the window's active
     * selection, so Bitwig's own Delete / Backspace (and Copy, Duplicate ...) act on it.
     */
    void selectInsert(Insert insert) {
        if (master == null || insert == null) return;
        try {
            bitwig.obf.bid devices = window.Mph().cfM(master.tp2()); // Mph(): the window's panels; cfM(iKU): a chain's device selection
            if (devices == null) { debug("insert select: no selection"); return; }
            devices.MRY(insert.device); // MRY(OLR): select only this
            window.MRY(devices);        // MRY(big): active_selection
            selectedInsert = insert.device;
            debug("insert select: " + insert.name);
        } catch (Throwable failure) {
            debug("insert select failed: " + failure);
            MasterBoPanels.MOD.log("Master Bo could not select an insert", failure);
        }
    }

    /** Resets BO LUFS (integrated, maxima, true peak) as a click on its own panel does: its trigger's "click". */
    void resetLufs() {
        oDc trigger = lufsTrigger;
        if (trigger == null) return;
        try {
            for (Object action : trigger.upC()) { // upC(): the object's actions
                if (action instanceof cuN a && a.MRY().E3() == TRIGGER_CLICK) { // MRY(): its definition; E3(): its id
                    a.run();
                    return;
                }
            }
            Object f = trigger.VpW("trigger_toggle"); // no click action: flip the trigger's toggle
            if (f instanceof Dgc toggle && toggle.getValue() instanceof Boolean b) toggle.setValue(!b);
        } catch (Throwable failure) {
            MasterBoPanels.MOD.log("Master Bo could not reset BO LUFS", failure);
        }
    }

    private static String fieldText(oDc o, String field) {
        try {
            Object f = o.VpW(field);
            Object v = f instanceof Dgc value ? value.getValue() : null;
            return v != null ? v.toString() : null;
        } catch (Throwable noSuchField) {
            return null;
        }
    }

    private static Dgc valueField(oDc o) {
        try {
            Object f = o.VpW("value");
            return f instanceof Dgc value ? value : null;
        } catch (Throwable noSuchField) {
            return null;
        }
    }

    /** Switches an insert on or off, as Bitwig's own Toggle Active does. */
    void toggle(Insert insert) {
        lBQ value = enabledValue(insert.device);
        if (value == null || !value.isSettable()) return;
        value.setBoolValue(!value.getBoolValue());
        insert.enabled = value.getBoolValue();
    }

    private static String text(Object value, String fallback) {
        Object v = value instanceof ESe e ? e.getValue() : value;
        String s = v == null ? null : v.toString();
        return s == null || s.isBlank() ? fallback : s;
    }

    // ---------- Selecting the master (the device panel follows) ----------

    /** Selects the master track alone and makes it the current track, so the device panel shows its chain. */
    /**
     * Selects the master as clicking its track header does (its "select" action: one undo-free
     * document action), then makes sure the Inspector panel is open, so it shows the master.
     */
    void selectMaster() {
        if (master == null) { debug("select: no master"); return; }
        try {
            cuN select = master.TgI(); // TgI(): the channel's "select" action (what select_track runs)
            if (select != null) { select.run(); debug("select: action run"); }
            else { master.LEd(); debug("select: LEd"); }
        } catch (Throwable failure) {
            debug("select failed: " + failure);
            MasterBoPanels.MOD.log("Master Bo could not select the master", failure);
        }
        try { // the window's track selection (as the track headers use it): the master alone, made the active one and the Inspector's
            bitwig.obf.bjq tracks = window.Mph().Z1C(); // Mph(): the window's panels; Z1C(): its track selection
            if (tracks != null) {
                tracks.MRY(master); // MRY(OLR): select only this
                window.MRY(tracks); // MRY(big): active_selection
                window.IwV(tracks); // IwV(big): inspector_selection
                debug("selection: window track selection set (" + tracks.getClass().getSimpleName() + ")");
            } else {
                debug("selection: the window has no track selection");
            }
        } catch (Throwable failure) {
            debug("selection failed: " + failure);
        }
        showInspector();
        dumpLayout();
    }

    /** Debugging: the window's layout, with every panel's "is shown" and every tab group's choice. */
    private void dumpLayout() {
        try {
            bitwig.obf.akC profile = window.puu();
            com.bitwig.flt.document_view_settings.gJi settings = window.RDJ();
            debug("layout of profile " + profile.MIO() + ", window " + window.sc() + ", mode " + settings.vpw());
            dump(profile.cfM(window.sc()).TMF(), settings, "  ");
        } catch (Throwable failure) {
            debug("layout dump failed: " + failure);
        }
    }

    private void dump(bitwig.obf.akw node, com.bitwig.flt.document_view_settings.gJi settings, String indent) {
        if (node == null) { debug(indent + "-"); return; }
        String line = indent + node.getClass().getSimpleName() + " " + node.tSS();
        if (node instanceof akQ panel) {
            AQw shown = panel.cfM(settings);
            line += shown == null ? " (no is-shown)" : " shown=" + shown.getBoolValue();
        }
        if (node instanceof bitwig.obf.ajF group) {
            bitwig.obf.LJt choice = group.cfM(settings);
            line += " choice=" + (choice == null ? "?" : "'" + choice.getValue() + "'");
            debug(line);
            for (Object tab : group.lYc()) {
                String t = indent + "  tab " + tab.getClass().getSimpleName() + " " + ((bitwig.obf.akw) tab).tSS();
                if (tab instanceof akQ p) { AQw sh = p.cfM(settings); if (sh != null) t += " shown=" + sh.getBoolValue(); }
                debug(t);
            }
            return;
        }
        if (node instanceof bitwig.obf.akq main) {
            debug(line + " (main area, " + main.wGc() + " layouts)");
            return;
        }
        debug(line);
        if (node instanceof bitwig.obf.ala split) {
            dump(split.E3(), settings, indent + "  ");
            dump(split.wGc(), settings, indent + "  ");
        }
    }

    /** Opens the Inspector panel when it is closed (never closes it), as its "focus or toggle" action does. */
    private void showInspector() {
        try {
            Object panel = window.cfM(akc.class); // cfM(Class): the window's panel of that kind
            debug("inspector panel: " + (panel == null ? "null" : panel.getClass().getName()));
            AQw visible = panel instanceof akQ side ? side.cfM(window.RDJ()) : null; // RDJ(): the window's layout settings
            boolean shown = visible != null && visible.getBoolValue();
            debug("inspector visible: " + (visible == null ? "unknown" : shown));
            if (!shown) { window.wGc(true); debug("inspector: opened"); } // wGc(true): focus_or_toggle_inspector
        } catch (Throwable failure) {
            debug("inspector failed: " + failure);
            MasterBoPanels.MOD.log("Master Bo could not open the Inspector", failure);
        }
    }

    /** A line in %LOCALAPPDATA%\\Bitwig Studio\\master-bo.log (BML's own log is not kept without Debug Mode). */
    static void debug(String line) {
        try {
            String base = System.getenv("LOCALAPPDATA");
            if (base == null) return;
            java.nio.file.Path file = java.nio.file.Path.of(base, "Bitwig Studio", "master-bo.log");
            java.nio.file.Files.writeString(file, java.time.LocalTime.now() + " " + line + System.lineSeparator(),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Throwable ignored) {
        }
    }

    // ---------- Adjusting, as a controller does: touch, set, release ----------

    void startAdjusting(boolean vol) {
        if (!hasMaster()) return;
        (vol ? volume : pan).startedAdjusting(false, true);
    }

    void finishAdjusting(boolean vol) {
        if (!hasMaster()) return;
        (vol ? volume : pan).finishedAdjusting(false, true);
    }

    /** Sets the volume from a position on the strip's scale (0..1). */
    void setVolumePosition(double position) {
        if (!hasMaster()) return;
        double max = maxVolume();
        double value = Math.max(0, Math.min(max, positionToVolume(position, max)));
        volumeValue.setCurrentValue(value);
        volumeParam = value;
        volumePos = volumeToPosition(value, max);
    }

    void setPan(double norm) {
        if (!hasMaster()) return;
        panNorm = clamp(norm);
        panValue.setCurrentValue(panValue.normalizedValueToValue(panNorm));
    }

    void resetVolume() {
        if (hasMaster()) volume.setToDefault();
    }

    void resetPan() {
        if (hasMaster()) pan.setToDefault();
    }

    /** The volume parameter's maximum value (+6 dB). */
    double maxVolume() {
        return volumeValue != null ? volumeValue.normalizedValueToValue(1) : dbToValue(6);
    }

    // ---------- The strip's scale: Bitwig's mixer scale ----------
    //
    // Measured from Bitwig 6.1.4's mixer: +6 dB at the top, then 0, -6, -12, -18, -24 and -36 dB
    // at these heights (from the bottom, -inf), linear in dB between them; below -36 dB linear in
    // the level down to -inf. The fader and the meters share it, as in Bitwig's mixer.

    static final double[] SCALE_DB = {6, 0, -6, -12, -18, -24, -36};
    static final double[] SCALE_POS = {1.0, 0.885, 0.760, 0.628, 0.510, 0.398, 0.234};
    /** The marks Bitwig labels (without the minus), and its -inf. */
    static final double[] LABEL_DB = {0, -6, -12, -18, -24, -36, Double.NEGATIVE_INFINITY};
    static final String[] LABELS = {"0", "6", "12", "18", "24", "36", "\u221E"};
    /** Small marks between the labels. */
    static final double[] TICK_DB = {3, -3, -9, -15, -21, -30, -48};

    /** The volume parameter's value for a level in dB: its value is the cube root of the gain, so 10^(dB / 60). */
    static double dbToValue(double dB) {
        return Math.pow(10, dB / 60);
    }

    // A taller scale reaches lower, as in Bitwig's mixer: up to the reference height the scale is the
    // measured one; above it the part from +6 to -36 dB keeps its size in pixels, the extra height
    // continues linear in dB below -36 (at the -24..-36 spacing), and the tail to -inf keeps its size.
    // ratio = the scale's height / the reference height (set by the widget's layout).
    static volatile double scaleRatio = 1;
    private static final double SLOPE = (0.398 - 0.234) / 12; // reference heights per dB, below -24

    /** The lowest dB of the linear part (-36 at the reference height, lower when taller). */
    static double floorDb() {
        return SCALE_DB[SCALE_DB.length - 1] - Math.max(0, scaleRatio - 1) / SLOPE;
    }

    /** Where a level in dB sits on the scale: 0 (bottom, -inf) .. 1 (top, +6 dB). */
    static double dbToPosition(double dB) {
        if (!(dB > -1000)) return 0;
        if (dB >= SCALE_DB[0]) return 1;
        int last = SCALE_DB.length - 1;
        double r = Math.max(1, scaleRatio);
        if (dB >= SCALE_DB[last]) {
            int i = 0;
            while (dB < SCALE_DB[i + 1]) i++;
            double t = (dB - SCALE_DB[i + 1]) / (SCALE_DB[i] - SCALE_DB[i + 1]);
            double p = SCALE_POS[i + 1] + t * (SCALE_POS[i] - SCALE_POS[i + 1]);
            return 1 - (1 - p) / r;                                       // the measured part, its size kept
        }
        double floor = floorDb();
        if (dB >= floor) return 1 - ((1 - SCALE_POS[last]) + (SCALE_DB[last] - dB) * SLOPE) / r; // the extension
        return SCALE_POS[last] / r * Math.pow(10, (dB - floor) / 20);     // the tail, linear in the level
    }

    /** The level in dB at a position (dbToPosition's inverse). */
    static double positionToDb(double position) {
        position = clamp(position);
        int last = SCALE_DB.length - 1;
        if (position <= 0) return Double.NEGATIVE_INFINITY;
        double r = Math.max(1, scaleRatio);
        double tail = SCALE_POS[last] / r;
        if (position < tail) return floorDb() + 20 * Math.log10(position / tail);
        double p = 1 - (1 - position) * r;                               // the position on the reference scale
        if (p < SCALE_POS[last]) return SCALE_DB[last] - (SCALE_POS[last] - p) / SLOPE; // the extension
        int i = 0;
        while (i < last - 1 && p < SCALE_POS[i + 1]) i++;
        double t = (p - SCALE_POS[i + 1]) / (SCALE_POS[i] - SCALE_POS[i + 1]);
        return SCALE_DB[i + 1] + t * (SCALE_DB[i] - SCALE_DB[i + 1]);
    }

    /** The labelled marks for the current scale: Bitwig's, then every 12 dB below -36 that has room. */
    static double[] labelDbs() {
        java.util.List<Double> marks = new java.util.ArrayList<>();
        for (int i = 0; i < LABEL_DB.length - 1; i++) marks.add(LABEL_DB[i]);
        double floor = floorDb();
        for (double dB = -48; dB >= floor + 4; dB -= 12) marks.add(dB);
        double[] out = new double[marks.size()];
        for (int i = 0; i < out.length; i++) out[i] = marks.get(i);
        return out;
    }

    /** Where a volume value sits on the scale. */
    static double volumeToPosition(double value, double max) {
        return value > 0 ? dbToPosition(60 * Math.log10(value)) : 0;
    }

    /** The volume value at a position on the scale (capped at the parameter's maximum). */
    static double positionToVolume(double position, double max) {
        double dB = positionToDb(position);
        return dB > -1000 ? Math.min(max, dbToValue(dB)) : 0;
    }

    /** Where a linear level (a meter's amplitude) sits on the scale. */
    static double position(double amplitude) {
        return amplitude > 0 ? dbToPosition(20 * Math.log10(amplitude)) : 0;
    }

    static double dB(double amplitude) {
        return amplitude > 0 ? 20 * Math.log10(amplitude) : Double.NEGATIVE_INFINITY;
    }

    /** The fader's level in dB. */
    double volumeDb() {
        return volumeParam > 0 ? 60 * Math.log10(volumeParam) : Double.NEGATIVE_INFINITY;
    }

    static String dbText(double dB) {
        if (!(dB > -200)) return "-inf";
        double r = Math.round(dB * 10) / 10.0;
        if (r == 0) r = 0; // no "-0.0"
        return (r > 0 ? "+" : "") + String.format(java.util.Locale.ROOT, "%.1f", r);
    }

    String panText() {
        double p = panNorm * 2 - 1;
        long amount = Math.round(Math.abs(p) * 100);
        if (amount == 0) return "C";
        return (p < 0 ? "L" : "R") + amount;
    }

    static double clamp(double value) {
        return value < 0 ? 0 : value > 1 ? 1 : value;
    }
}
