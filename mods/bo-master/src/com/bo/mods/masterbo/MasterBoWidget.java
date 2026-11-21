package com.bo.mods.masterbo;

import java.util.List;

import bitwig.obf.aBW; // a window's project view
import bitwig.obf.aXu; // Bitwig's pop-up browser for inserting devices
import bitwig.obf.dns; // a widget, as Bitwig types it
import bitwig.obf.dve; // a widget
import bitwig.obf.dJH; // a mouse press, as a gesture receives it
import bitwig.obf.dJI; // a mouse release
import bitwig.obf.dJM; // a mouse move while a gesture is held
import bitwig.obf.dmO; // a callback before each display frame
import bitwig.obf.dpM; // a display frame
import bitwig.obf.dpN; // Bitwig's UI event loop
import bitwig.obf.drX; // a mouse gesture (press, drag, release)
import bitwig.obf.drd; // a subscription to the display frames
import bitwig.obf.drv; // told when the UI scale around a widget changes
import bitwig.obf.duG; // a window
import bitwig.obf.eeY; // a theme colour
import bitwig.obf.eoV; // a set of held modifier keys
import bitwig.obf.eoR; // a key press
import bitwig.obf.eoO; // a key
import bitwig.obf.eoU; // Bitwig's keys (Backspace, Delete, ...)
import bitwig.obf.dsg; // the root of a window's widgets (it knows the keyboard focus)
import bitwig.obf.eoW; // a mouse button
import bitwig.obf.iok; // a colour
import com.bitwig.graphics.BrJ; // a loaded icon
import bml.panels.ViewContext;
import com.bitwig.base.geom.tfK; // a widget size (width, height)
import com.bitwig.graphics.Hpk; // a font
import com.bitwig.graphics.SYo; // Bitwig's drawing surface

/**
 * Master Bo's widget: the master track's strip.
 *
 * - VU: at the top, the master's level on VU needles with a VU meter's ballistics (left and
 *   right side by side, or both needles on one face where there is no room for two). On its strip,
 *   PRE/POST switches the source and the reference's value chooses 0 VU (-20, -18, -14, -12 or -9
 *   dBFS); a click anywhere else folds it to a bar labelled "VU Meter", and a click on the bar
 *   opens it again.
 * - Header: the master's colour and name; a click selects the master track (the device panel
 *   then shows its chain).
 * - Inserts: the master's devices in chain order. The square switches a device on or off; a click
 *   on the name selects the master track; on a plug-in, Bitwig's window button on the right opens
 *   or closes its window, lit while it is open.
 * - Pan: a bar from the centre. Drag sideways (Shift: fine); double-click or Alt-click resets.
 * - Fader and meters: Bitwig's mixer scale and look for both (0, 6, 12, 18, 24, 36, \u221e). The
 *   fader: drag up or down (Shift: fine); double-click or Ctrl/Cmd-click resets to 0 dB. The
 *   meters: per channel the peak (lighter) and the RMS (solid), a peak-hold line and a clip
 *   light; over them the highest peak since the last reset. A click on the readout or a clip
 *   light resets them.
 *
 * Layout: in a wide place (a lower panel) the header, inserts and pan sit in a column on the
 * left and the fader and meters on the right; in a tall place they stack, the fader and meters
 * taking the rest of the height.
 *
 * While shown it reads the master once per display frame and repaints only when something
 * changed; it stops reading while hidden and when it is disposed (a window rebuild).
 */
final class MasterBoWidget extends dve implements drv {
    // Sizes in Bitwig's layout units (scaled with the UI, eLY).
    private static final long PAD = 10;          // gaps between the columns (the wide layout)
    private static final int EDGE = 1;           // the outline around the panel, in pixels
    private static final long COLOUR_BAR = 4;    // the master's colour bar at the bottom, as Bitwig's mixer
    private static final long BOTTOM_GAP = 4;    // between the content and the colour bar
    private static final long HEADER = 46; // as tall as Bitwig's own master header in the mixer
    private static final long ROW = 20;
    private static final long PAN_HEIGHT = 18;
    private static final long READOUT = 18;
    // Bitwig's mixer strip (measured from 6.1.4 at 100%): labels, two 7-wide meters a pixel apart, the fader bar
    private static final long SCALE_WIDTH = 30;
    private static final long METER_WIDTH = 7;
    private static final long METER_GAP = 1;
    private static final long FADER_GAP = 2;
    private static final long FADER_WIDTH = 46;
    private static final long FADER_LINE = 3;
    private static final long CLIP = 4;
    private static final iok ACCENT = iok.cfM(255, 131, 18);     // Bitwig's orange, if the theme has no accent
    private static final iok ACTIVATION = iok.cfM(255, 202, 0);  // Bitwig's activation yellow, if the theme has none
    private static final long LEFT_MIN = 150;
    private static final long LEFT_MAX = 260;
    private static final long STRIP_MIN = 150;
    private static final long VU_MAX_HEIGHT = 110;
    private static final long VU_BAR = 20;      // the VU folded: a bar this high
    private static final long SEPARATOR = 9;  // the separator's height (a line with a grip; drag it to size the fader section)
    private static final long FADER_MIN = 140;
    private static final String FADER_HEIGHT_KEY = "fader_section_height"; // in layout units; 0: the default
    private static final long VU_TWO_FACES = 230;  // the width from which left and right get a face each
    private static final long WINDOW_BUTTON = 22;  // the window button's column at a row's right end

    // Meter colours (fixed: the levels mean the same in every theme).
    private static final iok GREEN = iok.cfM(76, 201, 106);
    private static final iok YELLOW = iok.cfM(232, 203, 70);
    private static final iok RED = iok.cfM(235, 64, 52);
    // The VU's colours: Bitwig's theme greys (resolved at each paint, so a theme change follows)
    private iok vuFace, vuBorder, vuInk, vuRed, vuSecond, vuHousing, vuHousingText;

    private static boolean vuCollapsed = readCollapsed(); // the VU folded to a bar, kept in Master Bo's settings
    private static final String VU_COLLAPSED_KEY = "vu_collapsed";
    private final java.util.List<double[]> sourceHits = new java.util.ArrayList<>(); // where PRE/POST is drawn (x, y, w, h), per face
    private final java.util.List<double[]> referenceHits = new java.util.ArrayList<>(); // where the reference's value is drawn
    private final java.util.List<double[]> vuLabelHits = new java.util.ArrayList<>(); // where "VU" is drawn, per face
    private static final String VU_SCHEME_KEY = "vu_scheme";
    private static final int VU_SCHEMES = 4;   // 0 as Bitwig (dark), 1 dark grey + orange (default), 2 white, 3 vintage cream
    private static int vuScheme = readScheme();

    private static int readScheme() {
        try {
            return Math.floorMod(MasterBoPanels.MOD.settings().getInt(VU_SCHEME_KEY, 1), VU_SCHEMES); // default: dark grey + orange
        } catch (Throwable failure) {
            return 1;
        }
    }

    private void nextScheme() {
        vuScheme = (vuScheme + 1) % VU_SCHEMES;
        try {
            MasterBoPanels.MOD.settings().putInt(VU_SCHEME_KEY, vuScheme);
        } catch (Throwable failure) {
            MasterBoPanels.MOD.log("Master Bo could not keep the VU colours", failure);
        }
        jbi();
    }
    private static int faderHeight = readFaderHeight(); // the fader section's height, in layout units (0: the default), kept in Master Bo's settings
    private ViewContext context;
    private aBW window;
    private MasterBoModel model;
    private drd frames;
    private boolean subscribing;

    // The layout, worked out at each paint (in pixels).
    private boolean wide;
    private double vuX, vuY, vuW, vuH;
    private double vuFaceH;                     // the VU's own height (vuH during layout includes the spectrum under it)

    // ---------- The spectrum (from a BO Spectrum device): 31 orange bars (20 Hz .. 20 kHz) under the VU, the slope chosen in its strip ----------
    private double specX, specY, specW, specH;
    private final java.util.List<double[]> slopeHits = new java.util.ArrayList<>(); // x, y, w, h, choice
    private static final double SPEC_TOP_DB = 0, SPEC_RANGE_DB = 72; // the graph: 0 dB at the top, 72 dB down

    /** The spectrum's height and the gap above it (none without a BO Spectrum device). */
    /**
     * The analysis section: the spectrum (with a BO Spectrum) and, under it, the goniometer (with a
     * BO Scope). specH is the whole section; specOwnH the spectrum's part, gonioH the goniometer's.
     */
    private double spectrumBlock(double width, double h) {
        specOwnH = 0;
        gonioH = 0;
        if (model.spectrumFound) {
            if (specCollapsed) specOwnH = eLY(VU_BAR); // folded: a bar, as the VU's
            else specOwnH = Math.max(eLY(56), Math.min(Math.min(width * 0.5, eLY(VU_MAX_HEIGHT)), h * 0.2))
                    + eLY(SPEC_FREQ_ROW) + eLY(SPEC_STRIP);
        }
        if (model.scopeFound) gonioH = gonioCollapsed ? eLY(VU_BAR) : Math.max(eLY(80), Math.min(width * 0.75, eLY(170))) + eLY(SPEC_STRIP);
        specH = specOwnH + gonioH + (specOwnH > 0 && gonioH > 0 ? eLY(6) : 0);
        return specH > 0 ? eLY(6) + specH : 0;
    }

    private double specOwnH, gonioH;

    private static final long SPEC_STRIP = 16;
    private static final long SPEC_FREQ_ROW = 11;   // the frequencies' row under the graph
    private static final long SPEC_SCALE = 22;      // the dB scale's margin at the right

    // its place: at the top (under the VU, or under the header and big meters when the VU is at the
    // bottom) or at the bottom (right above the fader section, under the VU when that is low too)
    private static final String SPEC_LOW_KEY = "spectrum_low";
    private static boolean specLow = readSpecLow();
    private double[] specMoveHit;

    private static boolean readSpecLow() {
        try {
            return MasterBoPanels.MOD.settings().getBoolean(SPEC_LOW_KEY, false);
        } catch (Throwable failure) {
            return false;
        }
    }

    private void moveSpectrum() {
        specLow = !specLow;
        try {
            MasterBoPanels.MOD.settings().putBoolean(SPEC_LOW_KEY, specLow);
        } catch (Throwable failure) {
            MasterBoPanels.MOD.log("Master Bo could not keep the spectrum position", failure);
        }
        jbi();
    }

    // the goniometer folds the same way, kept apart
    private static final String GONIO_COLLAPSED_KEY = "gonio_collapsed";
    private static boolean gonioCollapsed = readBool(GONIO_COLLAPSED_KEY);

    private static boolean readBool(String key) {
        try {
            return MasterBoPanels.MOD.settings().getBoolean(key, false);
        } catch (Throwable failure) {
            return false;
        }
    }

    private void setGonioCollapsed(boolean collapsed) {
        gonioCollapsed = collapsed;
        try {
            MasterBoPanels.MOD.settings().putBoolean(GONIO_COLLAPSED_KEY, collapsed);
        } catch (Throwable failure) {
            MasterBoPanels.MOD.log("Master Bo could not keep the goniometer folded", failure);
        }
        jbi();
    }

    // folded to a bar (as the VU): a click on the graph folds it, a click on the bar opens it again
    private static final String SPEC_COLLAPSED_KEY = "spectrum_collapsed";
    private static boolean specCollapsed = readSpecCollapsed();

    private static boolean readSpecCollapsed() {
        try {
            return MasterBoPanels.MOD.settings().getBoolean(SPEC_COLLAPSED_KEY, false);
        } catch (Throwable failure) {
            return false;
        }
    }

    private void setSpecCollapsed(boolean collapsed) {
        specCollapsed = collapsed;
        try {
            MasterBoPanels.MOD.settings().putBoolean(SPEC_COLLAPSED_KEY, collapsed);
        } catch (Throwable failure) {
            MasterBoPanels.MOD.log("Master Bo could not keep the spectrum folded", failure);
        }
        jbi();
    }

    private boolean specWithVu() { return specH > 0 && specLow == vuLow; }
    private boolean specTopAlone() { return specH > 0 && !specLow && vuLow; }
    private boolean specBottomAlone() { return specH > 0 && specLow && !vuLow; }

    /**
     * The bars on whole pixels: each band's left and right edges and its top are rounded, with a
     * one-pixel gap, so every bar is crisp and the gaps are even (fractional positions blur).
     */
    private void paintSpectrumBars(SYo g, double[] values, double gx, double gy, double gh, double barW, iok colour) {
        double bottom = Math.round(gy + gh);
        for (int b = 0; b < MasterBoModel.BANDS; b++) {
            double f = (values[b] - (SPEC_TOP_DB - SPEC_RANGE_DB)) / SPEC_RANGE_DB;
            if (!(f > 0)) continue;
            f = Math.min(1, f);
            double x0 = Math.round(gx + b * barW), x1 = Math.round(gx + (b + 1) * barW) - 1;
            double y0 = Math.round(gy + gh * (1 - f));
            if (x1 <= x0 || bottom <= y0) continue;
            rect(g, x0, y0, x1 - x0, bottom - y0, colour);
        }
    }

    /** Peak hold over the bars: a 2 px line at each band's held level. */
    private void paintHoldBars(SYo g, double[] values, double gx, double gy, double gh, double barW, iok colour) {
        for (int b = 0; b < MasterBoModel.BANDS; b++) {
            double f = (values[b] - (SPEC_TOP_DB - SPEC_RANGE_DB)) / SPEC_RANGE_DB;
            if (!(f > 0)) continue;
            f = Math.min(1, f);
            double x0 = Math.round(gx + b * barW), x1 = Math.round(gx + (b + 1) * barW) - 1;
            double y0 = Math.round(gy + gh * (1 - f));
            rect(g, x0, y0, x1 - x0, Math.max(1, Math.round(eLY(2))), colour);
        }
    }

    /**
     * Plot: the curve, its points placed by frequency on the bars' scale (band of f, centred in its
     * column), a line 1.5 px; filled: the area under it in the colour at 25 %.
     */
    private void paintCurve(SYo g, double[] values, double gx, double gy, double gh, double barW, iok colour, boolean filled) {
        int n = values.length;
        double bottom = gy + gh;
        double[] xs = new double[n], ys = new double[n];
        for (int p = 0; p < n; p++) {
            xs[p] = gx + barW * (MasterBoModel.bandOf(MasterBoModel.curveHz(p)) + 0.5);
            double f = (values[p] - (SPEC_TOP_DB - SPEC_RANGE_DB)) / SPEC_RANGE_DB;
            ys[p] = gy + gh * (1 - Math.max(0, Math.min(1, f)));
        }
        if (filled) {
            g.MRY(xs[0], bottom);
            for (int p = 0; p < n; p++) g.cfM(xs[p], ys[p]);
            g.cfM(xs[n - 1], bottom);
            g.Hnt(colour.cfM(0.25F));
            g.wGc(); // fill
        }
        g.MRY(xs[0], ys[0]);
        for (int p = 1; p < n; p++) g.cfM(xs[p], ys[p]);
        g.Hnt(colour);
        g.IwV((filled ? 1.5 : 1.0) * eLY(1));
        g.hbP(); // stroke
    }

    /**
     * The master's remote controls: a title row ("Remote Controls" and the move triangle), then each
     * control as a knob (its value as an orange arc, as Bitwig's) with its name; an unmapped one dim.
     */
    private void paintMacros(SYo g, Hpk font, iok text) {
        macroHits.clear();
        macrosMoveHit = null;
        if (macrosH <= 0 || !macrosOn()) return;
        Hpk small = smallFont(font);
        double sa = small.cfM().cfM();
        g.cfM(small);
        iok orange = theme("Accent (default)", ACCENT);
        double titleH = eLY(MACRO_TITLE);
        double sw = eLY(VU_MOVE), ix = macrosX + macrosW - sw - eLY(2);
        String title = "TRACK REMOTES"; // "REMOTES" where that leaves no room for the triangle
        if (macrosX + eLY(4) + small.cfM(title).IwV() > ix - eLY(4)) title = "REMOTES";
        text(g, small, macrosX + eLY(4), macrosY + (titleH + sa) / 2 - 1, title, text.cfM(0.45F));
        boolean low = macrosLow && !wide;
        triangle(g, ix + sw / 2, macrosY + titleH / 2, low, orange); // pointing where a click moves it (up when low)
        macrosMoveHit = new double[] {ix, macrosY, sw, titleH};
        int n = model.macros.size();
        int cols = low ? 1 : (macrosW >= eLY(200) ? 2 : 1);
        double row = eLY(MACRO_ROW), colW = (macrosW - (cols - 1) * eLY(4)) / cols;
        iok control = themeGrey("Grey 4", 61, "Grey 5", 73, 8 / 12.0); // 69 in the default theme
        for (int i = 0; i < n; i++) {
            int c = i % cols, rr = i / cols;
            double x = macrosX + c * (colW + eLY(4)), y = macrosY + titleH + rr * row;
            if (y + row > macrosY + macrosH + 1) break;
            MasterBoModel.Macro m = model.macros.get(i);
            double cy = y + (row - 2) / 2, cw = eLY(MACRO_CTL), cx0 = x + eLY(2); // the control's room at the left
            boolean live = m.assigned && m.target != null;
            if (!live) { // unmapped: a dim ring
                arc(g, cx0 + cw / 2, cy, eLY(8), 0, 1, text.cfM(0.15F), Math.max(2, eLY(2)));
            } else if (m.kind == 1) { // a button: orange when on, as Bitwig's
                double bh = eLY(20);
                if (m.norm > 0.5) rect(g, Math.round(cx0), Math.round(cy - bh / 2), Math.round(cw), Math.round(bh), orange);
                else bitwigFace(g, cx0, cy - bh / 2, cw, bh);
            } else if (m.kind == 2) { // a choice: its value in a dark box with a little arrow; a click opens its list
                double bh = eLY(20);
                bitwigFace(g, cx0, cy - bh / 2, cw, bh);
                String v = fit(small, m.valueText, cw - eLY(8));
                text(g, small, cx0 + eLY(2), cy + sa / 2 - 1, v, text);
                double ax = cx0 + cw - eLY(4), ay = cy;
                g.MRY(ax - eLY(2), ay - eLY(1)); g.cfM(ax + eLY(2), ay - eLY(1)); g.cfM(ax, ay + eLY(2));
                g.Hnt(text.cfM(0.6F)); g.wGc();
            } else { // a knob as Bitwig's: a dark disc, a grey ring with the value as an orange arc, a light pointer
                double r = eLY(11), kx = cx0 + cw / 2;
                g.MRY(kx, cy, r - eLY(2)); g.Hnt(themeGrey("Grey 5", 73, "Grey 6", 90, 8 / 17.0)); g.wGc(); // the buttons' grey (81 in the default theme)
                arc(g, kx, cy, r, 0, 1, themeGrey("Grey 3", 50, "Grey 4", 61, 6 / 11.0), Math.max(2.0, 2.5 * eLY(1))); // 56
                arc(g, kx, cy, r, 0, m.norm, orange, Math.max(2.0, 2.5 * eLY(1)));
                double ang = Math.toRadians(225 - 270 * m.norm);
                line(g, kx, cy, kx + Math.cos(ang) * r * 0.7, cy - Math.sin(ang) * r * 0.7, text.cfM(0.9F), Math.max(1.0, 1.5 * eLY(1)));
            }
            String name = m.name.isEmpty() ? "\u2013" : m.name;
            double tx = cx0 + cw + eLY(6);
            Hpk bold = boldFont(small);
            text(g, bold, tx, cy + sa / 2 - 1, fit(bold, name, x + colW - tx - eLY(3)), m.assigned ? text : text.cfM(0.4F));
            g.cfM(small);
            macroHits.add(new double[] {x, y, colW, row - 2, i});
        }
        g.cfM(font);
    }

    // ---------- A choice's drop-down: its values in a list under the box (over the box when no room) ----------
    private int popupMacro = -1;
    private double popupX, popupY, popupW, popupItemH;
    private String[] popupNames = new String[0];
    private int popupHover = -1; // the value under the pointer

    /** The pointer over the widget (no button held): the drop-down marks the value under it. */
    @Override
    public void cfM(dJM move) {
        super.cfM(move);
        hoverAt(move.hbP(), move.Grk());
    }

    /** Bitwig's pointer-over call (as its own widgets get it), with the position in the widget. */
    @Override
    public void f_(double x, double y) {
        super.f_(x, y);
        hoverAt(x, y);
    }

    private boolean hoverLogged;

    private void hoverAt(double x, double y) {
        if (popupMacro < 0) return;
        if (!hoverLogged) { hoverLogged = true; MasterBoModel.debug("hover events arrive"); }
        double h = popupNames.length * popupItemH + eLY(4);
        int over = in(x, y, popupX, popupY, popupW, h) ? (int) Math.floor((y - popupY - eLY(2)) / popupItemH) : -1;
        if (over >= popupNames.length) over = -1;
        if (over != popupHover) { popupHover = over; jbi(); }
    }

    private void openPopup(int index, double bx, double by, double bw, double bh) {
        MasterBoModel.Macro m = model.macros.get(index);
        String[] names = model.macroNames(m);
        if (names.length == 0) return;
        Hpk small = smallFont(kI_().cfM(MrC(), this));
        double w = bw;
        for (String n : names) w = Math.max(w, small.cfM(n).IwV() + eLY(16));
        popupItemH = eLY(20);
        double h = names.length * popupItemH + eLY(4);
        popupX = Math.max(0, Math.min(bx, Sxo() - w - 1));
        popupY = by + bh + h <= yiX() ? by + bh : Math.max(0, by - h);
        popupW = w;
        popupNames = names;
        popupMacro = index;
        jbi();
    }

    private void paintPopup(SYo g, Hpk font, iok text) {
        if (popupMacro < 0 || popupMacro >= model.macros.size()) { popupMacro = -1; return; }
        MasterBoModel.Macro m = model.macros.get(popupMacro);
        Hpk small = smallFont(font);
        double sa = small.cfM().cfM();
        g.cfM(small);
        double h = popupNames.length * popupItemH + eLY(4);
        rect(g, popupX - 1, popupY - 1, popupW + 2, h + 2, theme("Grey 6", iok.cfM(90, 90, 90)));   // its edge
        rect(g, popupX, popupY, popupW, h, theme("Grey 2", iok.cfM(38, 38, 38)));
        int current = (int) Math.round(m.norm * Math.max(1, popupNames.length - 1));
        iok orange = theme("Accent (default)", ACCENT);
        for (int i = 0; i < popupNames.length; i++) {
            double y = popupY + eLY(2) + i * popupItemH;
            if (i == popupHover) rect(g, popupX, y, popupW, popupItemH, themeGrey("Grey 4", 61, "Grey 5", 73, 9 / 12.0)); // (70) under the pointer (the current value: orange)
            text(g, small, popupX + eLY(8), y + (popupItemH + sa) / 2 - 1, popupNames[i], i == current ? orange : text);
        }
        g.cfM(font);
    }

    /** Bitwig's button face: a grey from 85 at the top to 77 at the bottom (in the default theme; between its Grey 5 and Grey 6). */
    private void bitwigFace(SYo g, double x, double y, double w, double h) {
        x = Math.round(x); y = Math.round(y); w = Math.round(w); h = Math.round(h);
        int bands = (int) Math.max(1, h);
        iok top = themeGrey("Grey 5", 73, "Grey 6", 90, 12 / 17.0), bottom = themeGrey("Grey 5", 73, "Grey 6", 90, 4 / 17.0);
        for (int k = 0; k < bands; k++) {
            rect(g, x, y + k, w, 1, top.cfM(bottom, (float) (k / (double) Math.max(1, bands - 1)))); // cfM(iok, t): mixed
        }
    }

    /** A knob's arc from 0 to f (0 .. 1) over 270 degrees, starting at the lower left. */
    private static void arc(SYo g, double cx, double cy, double r, double f0, double f1, iok colour, double width) {
        if (f1 <= f0) return;
        int steps = Math.max(2, (int) Math.ceil((f1 - f0) * 40));
        for (int k = 0; k <= steps; k++) {
            double a = Math.toRadians(225 - 270 * (f0 + (f1 - f0) * k / steps));
            double x = cx + Math.cos(a) * r, y = cy - Math.sin(a) * r;
            if (k == 0) g.MRY(x, y); else g.cfM(x, y);
        }
        g.Hnt(colour);
        g.IwV(width);
        g.hbP();
    }

    /** Dragging a remote control up and down (Shift: fine), as a knob; a double-click resets it. */
    private final class MacroDrag extends drX {
        private final MasterBoModel.Macro macro;
        private double lastY, value;
        private boolean adjusting;

        MacroDrag(MasterBoModel.Macro macro, double y) {
            this.macro = macro;
            this.lastY = y;
        }

        @Override
        public void cfM(dJH press) {
            if (adjusting) return;
            value = macro.norm;
            model.startMacro(macro);
            adjusting = true;
        }

        @Override
        public void cfM(dJM move) {
            if (!adjusting) return;
            double y = move.Grk();
            double factor = move.E3().cfM() ? 0.1 : 1;
            value = MasterBoModel.clamp(value - (y - lastY) / Math.max(1, eLY(150)) * factor);
            model.setMacro(macro, value);
            lastY = y;
            jbi();
        }

        @Override
        public void cfM(dJI release) {
            finish();
            super.cfM(release);
        }

        @Override
        public void FzB() {
            finish();
            super.FzB();
        }

        private void finish() {
            if (!adjusting) return;
            adjusting = false;
            model.finishMacro(macro);
        }

        @Override
        public boolean jC_() {
            return true;
        }
    }

    private static final double[] SPEC_OCTAVES = {20, 40, 80, 160, 315, 630, 1250, 2500, 5000, 10000, 20000};
    private static final String[] SPEC_OCTAVE_LABELS = {"20", "40", "80", "160", "315", "630", "1.2k", "2.5k", "5k", "10k", "20k"};

    private void paintSpectrum(SYo g, Hpk font, iok text, double ascent) {
        slopeHits.clear();
        specMoveHit = null;
        gonioMoveHit = null;
        if (specOwnH > 0) paintSpectrumOwn(g, font, text, ascent);
        if (gonioH > 0) paintGonio(g, font, text, ascent, specY + specH - gonioH, true);
    }

    private double[] gonioMoveHit;

    /** The spectrum's and the goniometer's face: (47, 47, 47) in the default theme, as on bo Spectrum's and bo Scope's panels (the VU's schemes leave it alone). */
    private iok analysisFace() {
        return themeGrey("Grey 2", 38, "Grey 3", 50, 9 / 12.0); // a mid-dark grey, whatever the VU's scheme
    }

    /**
     * The goniometer: L and R as points, rotated 45 degrees (mid up, side across: a mono signal is a
     * vertical line), with the L and R diagonals and the axes; under it, in its strip, the correlation
     * (-1 .. +1) as a bar with its value, and the move switch when there is no spectrum above.
     */
    private void paintGonio(SYo g, Hpk font, iok text, double ascent, double y, boolean withSwitch) {
        if (gonioCollapsed) { // folded: a bar, as the VU's and the spectrum's
            rect(g, specX, y, specW, gonioH, text.cfM(0.06F));
            g.cfM(specX + eLY(8), y + (gonioH + ascent) / 2 - 1, "Goniometer", text.cfM(0.7F));
            double sw = eLY(VU_MOVE), ix = specX + specW - sw - eLY(2);
            triangle(g, ix + sw / 2, y + gonioH / 2, specLow, theme("Accent (default)", ACCENT));
            gonioMoveHit = new double[] {ix, y, sw, gonioH};
            return;
        }
        iok face = analysisFace();
        iok orange = theme("Accent (default)", ACCENT);
        iok grid = text.cfM(0.10F);
        double strip = eLY(SPEC_STRIP);
        double h = gonioH - strip;
        rect(g, specX, y, specW, gonioH, face);
        double cx = Math.round(specX + specW / 2), cy = Math.round(y + h / 2);
        double r = Math.min(specW, h) / 2 - eLY(6);
        // the axes (mid, side) and the diagonals (L, R)
        rect(g, cx, cy - r, 1, 2 * r, grid);
        rect(g, cx - r, cy, 2 * r, 1, grid);
        line(g, cx - r * 0.7071, cy - r * 0.7071, cx + r * 0.7071, cy + r * 0.7071, grid);
        line(g, cx + r * 0.7071, cy - r * 0.7071, cx - r * 0.7071, cy + r * 0.7071, grid);
        Hpk tiny = tinyFont(font);
        double ta = tiny.cfM().cfM();
        g.cfM(tiny);
        iok labelInk = text.cfM(0.8F);
        g.cfM(cx - r * 0.7071 - tiny.cfM("L").IwV() - eLY(2), cy - r * 0.7071 + ta / 2, "L", labelInk);
        g.cfM(cx + r * 0.7071 + eLY(2), cy - r * 0.7071 + ta / 2, "R", labelInk);
        g.cfM(cx + eLY(3), cy - r + ta, "M", labelInk);
        g.cfM(cx + r - tiny.cfM("S").IwV(), cy - eLY(3), "S", labelInk);
        // the glow: the model's persistence grid, each cell as bright as its glow (soft, never quite solid)
        int gn = MasterBoModel.GONIO_N;
        float[] glow = model.gonio;
        double cell = r * 2 * MasterBoModel.GONIO_RANGE / gn;
        double gx0 = cx - r * MasterBoModel.GONIO_RANGE, gy0 = cy - r * MasterBoModel.GONIO_RANGE;
        for (int j = 0; j < gn; j++) {
            double top = Math.floor(gy0 + j * cell), bottom = Math.floor(gy0 + (j + 1) * cell);
            if (bottom <= top || top < y + 1 || bottom > y + h - 1) continue;
            for (int i = 0; i < gn; i++) {
                float v = glow[j * gn + i];
                if (v < 0.01F) continue;
                double left = Math.floor(gx0 + i * cell), right = Math.floor(gx0 + (i + 1) * cell);
                if (right <= left || left < specX + 1 || right > specX + specW - 1) continue;
                rect(g, left, top, right - left, bottom - top, orange.cfM((float) (0.95 * (1 - Math.exp(-2.5 * v)))));
            }
        }
        // the strip: Corr, its bar (-1 .. +1) and its value; the move switch when it is alone
        double sTop = y + gonioH - strip;
        rect(g, specX + 1, sTop, specW - 2, strip - 1, theme("Grey 2", iok.cfM(38, 38, 38)));
        Hpk small = smallFont(font);
        double sa = small.cfM().cfM();
        g.cfM(small);
        double base = sTop + (strip + sa) / 2 - 1;
        double tx = text(g, small, specX + eLY(5), base, "Corr", text.cfM(0.7F)) + eLY(6);
        double switchW = withSwitch ? eLY(VU_MOVE) + eLY(2) : 0;
        String value = String.format(java.util.Locale.ENGLISH, "%+.2f", model.correlation);
        double vw = small.cfM("+0.00").IwV();
        double bx = tx, bw = specX + specW - eLY(6) - switchW - vw - eLY(6) - bx;
        if (bw > eLY(20)) {
            double by = sTop + strip / 2 - eLY(2), bh = eLY(4);
            rect(g, bx, by, bw, bh, text.cfM(0.08F));
            rect(g, Math.round(bx + bw / 2), by - eLY(1), 1, bh + eLY(2), text.cfM(0.35F));
            double c = Math.max(-1, Math.min(1, model.correlation));
            double mx = bx + bw / 2 + c * bw / 2;
            iok ink = c < 0 ? theme("Meter Clipping", RED) : orange;
            if (c >= 0) rect(g, bx + bw / 2, by, mx - (bx + bw / 2), bh, ink);
            else rect(g, mx, by, bx + bw / 2 - mx, bh, ink);
            text(g, small, bx + bw + eLY(6), base, value, c < 0 ? theme("Meter Clipping", RED) : orange);
        }
        if (withSwitch) {
            double ix = specX + specW - eLY(VU_MOVE) - eLY(2);
            triangle(g, ix + eLY(VU_MOVE) / 2, sTop + strip / 2, specLow, orange);
            gonioMoveHit = new double[] {ix, sTop, eLY(VU_MOVE), strip};
        }
        g.cfM(font);
    }

    private final double[][] specScaleHits = new double[2][];

    private void paintSpectrumOwn(SYo g, Hpk font, iok text, double ascent) {
        double specH = specOwnH; // this part only (the goniometer has the rest of the section)
        if (specCollapsed) { // folded: a bar in the MASTER header's grey, with the move switch
            rect(g, specX, specY, specW, specH, text.cfM(0.06F));
            g.cfM(specX + eLY(8), specY + (specH + ascent) / 2 - 1, "Spectrum", text.cfM(0.7F));
            double sw = eLY(VU_MOVE), ix = specX + specW - sw - eLY(2);
            triangle(g, ix + sw / 2, specY + specH / 2, specLow, theme("Accent (default)", ACCENT));
            specMoveHit = new double[] {ix, specY, sw, specH};
            return;
        }
        iok face = analysisFace(); // the VU's face (its dark schemes)
        iok border = theme("Grey 5", iok.cfM(73, 73, 73));
        iok orange = theme("Accent (default)", ACCENT);
        iok grid = text.cfM(0.10F);
        Hpk small = smallFont(font);
        double sa = small.cfM().cfM();
        Hpk tiny = tinyFont(font);
        double ta = tiny.cfM().cfM();
        // the dB scale's margin: as wide as its widest value, plus a small gap
        double strip = eLY(SPEC_STRIP), freqRow = eLY(SPEC_FREQ_ROW), scaleW = tiny.cfM("-72").IwV() + eLY(6);
        // the graph, with the dB scale's margin at its right and the frequencies under it
        double gx = specX + eLY(3), gy = specY + eLY(4);
        double gw = specW - eLY(3) - scaleW, gh = specH - strip - freqRow - eLY(4);
        // the scales (the dB margin at the right, the frequencies under the graph): a click resets
        specScaleHits[0] = new double[] {gx + gw, specY, specX + specW - (gx + gw), gy + gh - specY};
        specScaleHits[1] = new double[] {specX, gy + gh, specW, specY + specH - strip - (gy + gh)};
        rect(g, specX, specY, specW, specH, face);
        g.cfM(small);
        double barW = gw / MasterBoModel.BANDS;
        double x20 = Math.round(gx + barW * (MasterBoModel.bandOf(20) + 0.5));            // 20 Hz
        double x20k = Math.round(gx + barW * (MasterBoModel.bandOf(20000) + 0.5));        // 20 kHz
        // the scales (dB at the right, the octaves under the graph) in a font 2 under the strip's
        g.cfM(tiny);
        // horizontal: every 12 dB from 20 Hz to 20 kHz, its value at the right
        for (int d = 0; d <= SPEC_RANGE_DB; d += 12) {
            double y = Math.round(gy + gh * d / SPEC_RANGE_DB);
            rect(g, x20, y, x20k - x20 + 1, 1, d == 0 ? text.cfM(0.16F) : grid);
            String label = d == 0 ? "0" : "-" + d;
            double lw = tiny.cfM(label).IwV();
            g.cfM(specX + specW - eLY(3) - lw, y + ta / 2 - 1, label, text.cfM(0.8F));
        }
        // vertical: the octaves from 20 Hz to 20 kHz that have room for their value (line and value)
        double lastRight = -1e9;
        for (int i = 0; i < SPEC_OCTAVES.length; i++) {
            double b = MasterBoModel.bandOf(SPEC_OCTAVES[i]); // the band (fractional) at that frequency
            double x = Math.round(gx + barW * (b + 0.5));
            String label = SPEC_OCTAVE_LABELS[i];
            double lw = tiny.cfM(label).IwV();
            double lx = Math.max(gx, Math.min(gx + gw - lw, x - lw / 2));
            if (lx < lastRight + eLY(3)) continue;
            rect(g, x, gy, 1, gh, grid);
            g.cfM(lx, gy + gh + eLY(2) + ta, label, text.cfM(0.8F));
            lastRight = lx + lw;
        }
        g.cfM(small);
        // Stereo: the signal in orange; M/S: the mid in orange, the side in blue in front. 1/3 Oct: bars;
        // Plot: a curve, filled under it. Peak hold: a light line over each bar / a thin curve.
        boolean ms = model.msMode && model.msAvailable();
        iok blue = iok.cfM(70, 150, 255);
        iok holdInk = iok.cfM(255, 220, 180);
        iok holdBlue = iok.cfM(170, 205, 255);
        if (model.thirdOctave) {
            paintSpectrumBars(g, ms ? model.bandsM : model.bands, gx, gy, gh, barW, orange);
            if (ms) paintSpectrumBars(g, model.bandsS, gx, gy, gh, barW, blue);
            if (model.holdOn) {
                paintHoldBars(g, ms ? model.bandsMH : model.bandsH, gx, gy, gh, barW, holdInk);
                if (ms) paintHoldBars(g, model.bandsSH, gx, gy, gh, barW, holdBlue);
            }
        } else {
            paintCurve(g, ms ? model.curveM : model.curve, gx, gy, gh, barW, orange, true);
            if (ms) paintCurve(g, model.curveS, gx, gy, gh, barW, blue, true);
            if (model.holdOn) {
                paintCurve(g, ms ? model.curveMH : model.curveH, gx, gy, gh, barW, holdInk.cfM(0.75F), false);
                if (ms) paintCurve(g, model.curveSH, gx, gy, gh, barW, holdBlue.cfM(0.75F), false);
            }
        }
        // the strip: Slope (its value; a click goes to the next), L/R | M/S (a click switches), the move switch
        double sTop = specY + specH - strip;
        rect(g, specX + 1, sTop, specW - 2, strip - 1, theme("Grey 2", iok.cfM(38, 38, 38)));
        double base = sTop + (strip + sa) / 2 - 1;
        double switchW = eLY(VU_MOVE);
        double tx = specX + eLY(5);
        tx = text(g, small, tx, base, "Slope", text.cfM(0.7F)) + eLY(6);
        String slope = MasterBoModel.SLOPE_LABELS[model.slopeChoice];
        double sx = tx;
        tx = text(g, small, tx, base, slope, orange);
        slopeHits.add(new double[] {sx - eLY(4), sTop, tx - sx + eLY(8), strip, 0});
        tx += eLY(12);
        String mode = model.msMode ? "M/S" : "Stereo";
        double mw = small.cfM(mode).IwV();
        if (tx + mw <= specX + specW - eLY(4) - switchW) {
            text(g, small, tx, base, mode, model.msMode && !model.msAvailable() ? text.cfM(0.45F) : orange);
            slopeHits.add(new double[] {tx - eLY(4), sTop, mw + eLY(8), strip, 1});
            tx += mw + eLY(12);
        }
        String reading = model.thirdOctave ? "Bars" : "Plot";
        double rw = small.cfM(reading).IwV();
        if (tx + rw <= specX + specW - eLY(4) - switchW) {
            text(g, small, tx, base, reading, orange);
            slopeHits.add(new double[] {tx - eLY(4), sTop, rw + eLY(8), strip, 2});
            tx += rw + eLY(12);
        }
        // Hold (on: orange) and its time (a click: the next)
        double hw = small.cfM("Hold").IwV();
        if (tx + hw <= specX + specW - eLY(4) - switchW) {
            text(g, small, tx, base, "Hold", model.holdOn ? orange : text.cfM(0.45F));
            slopeHits.add(new double[] {tx - eLY(4), sTop, hw + eLY(8), strip, 3});
            tx += hw + eLY(6);
            String time = MasterBoModel.HOLD_LABELS[model.holdChoice];
            double tw = small.cfM(time).IwV();
            if (tx + tw <= specX + specW - eLY(4) - switchW) {
                text(g, small, tx, base, time, model.holdOn ? orange : text.cfM(0.45F));
                slopeHits.add(new double[] {tx - eLY(3), sTop, tw + eLY(6), strip, 4});
            }
        }
        double ix = specX + specW - switchW - eLY(2);
        triangle(g, ix + switchW / 2, sTop + strip / 2, specLow, orange); // pointing where a click moves it
        specMoveHit = new double[] {ix, sTop, switchW, strip};
        g.cfM(font);
    }
    private double latencyY;   // the latency row, under the inserts
    private double separatorY; // the separator over the fader section (-1: none, in the wide layout)
    private double headerX, headerY, headerW;

    // ---------- Big meters (Bitwig's "Show Big Meters"): a tall pair of meters under the header ----------
    private static final String BIG_METERS_KEY = "big_meters_mode";
    private static int bigMode = readBigMode(); // 0 off, 1 under the header (above the VU), 2 under the VU
    private double bigX, bigY, bigW, bigH;           // the section (0 high when off)
    private double bigIconX, bigIconY, bigIconW, bigIconH; // its toggle in the header's right end
    private static final long BIG_ICON = 14;
    private static final long BIG_MIN = 120, BIG_MAX = 320; // their own height: a share of the panel within these
    private static final long BIG_SHORTEST = 60;            // the shortest they get when the fader section takes their room
    private static final long BIG_SCALE = 24;       // the labels' column
    private static final long BIG_METER_MAX = 30;   // a meter's widest
    // its scale, as Bitwig's big meters: +6 dB at the top, linear in dB to -30, then closer steps to -60, -inf at the bottom
    private static final double[] BIG_DB = {6, 0, -6, -12, -18, -24, -30, -36, -42, -48, -60};
    private static final double[] BIG_POS = {1.0, 0.897, 0.776, 0.654, 0.532, 0.411, 0.289, 0.208, 0.162, 0.116, 0.062};
    private static final double[] BIG_LABELS = {0, -6, -12, -18, -24, -30, -36, -42, -48, -60};

    private static int readBigMode() {
        try {
            int mode = MasterBoPanels.MOD.settings().getInt(BIG_METERS_KEY, -1);
            if (mode < 0) mode = MasterBoPanels.MOD.settings().getBoolean("big_meters", false) ? 1 : 0; // dev.23's on/off
            return mode >= 0 && mode <= 2 ? mode : 0;
        } catch (Throwable failure) {
            return 0;
        }
    }

    private static int bigLastMode = 1; // the order (1: big meters above the VU, 2: below) they come back with

    /** The header's bars: the big meters on or off (where they sit relative to the VU: the VU's triangles). */
    private void toggleBigMeters() {
        if (bigMode > 0) {
            bigLastMode = bigMode;
            bigMode = 0;
        } else {
            bigMode = vuLow ? 1 : bigLastMode;
        }
        try {
            MasterBoPanels.MOD.settings().putInt(BIG_METERS_KEY, bigMode);
        } catch (Throwable failure) {
            MasterBoPanels.MOD.log("Master Bo could not keep the big meters", failure);
        }
        jbi();
    }

    /** Where a level sits on the big meters: 0 (bottom, -inf) .. 1 (top, +6 dB). */
    static double bigPosition(double dB) {
        if (!(dB > -1000)) return 0;
        if (dB >= BIG_DB[0]) return 1;
        int last = BIG_DB.length - 1;
        if (dB < BIG_DB[last]) return BIG_POS[last] * Math.pow(10, (dB - BIG_DB[last]) / 20); // the tail, linear in the level
        int i = 0;
        while (dB < BIG_DB[i + 1]) i++;
        double t = (dB - BIG_DB[i + 1]) / (BIG_DB[i] - BIG_DB[i + 1]);
        return BIG_POS[i + 1] + t * (BIG_POS[i] - BIG_POS[i + 1]);
    }
    private double lufsX, lufsY, lufsW, lufsH; // BO LUFS readouts under the VU (when a BO LUFS is in the project)
    private static final int LUFS_H = 34;
    /** The scale's labels in the order they get room: Bitwig's, the lower ones, then the ones between. */
    private static final double[] LABEL_ORDER = {0, -6, -12, -18, -24, -36, -48, -60, -72, -30, -3, -9, -15, -21, -42, -54, -84, -96};
    /** Small marks (where no label is). */
    private static final double[] TICK_ORDER = {3, -3, -9, -15, -21, -30, -42, -48, -54, -60, -72};
    private static final int LUFS_ICON = 12;   // the move switch (a triangle) at the LUFS section's right end
    private static final int VU_MOVE = 14;     // the VU's move switch at its strip's right end
    // ---------- The master's remote controls: shown or not (the knob in the header), and where ----------
    private static final String MACROS_KEY = "macros_shown", MACROS_LOW_KEY = "macros_low";
    private static boolean macrosShown = readBool(MACROS_KEY), macrosLow = readBool(MACROS_LOW_KEY);
    private double macroIconX;                              // its knob in the header, left of the big meters' icon
    private double macrosX, macrosY, macrosW, macrosH;      // the section (0 high: not shown)
    private final List<double[]> macroHits = new java.util.ArrayList<>(); // x, y, w, h, index
    private double[] macrosMoveHit;
    private static final long MACRO_ROW = 32, MACRO_TITLE = 18, MACRO_CTL = 30; // as the mixer's: 30 wide controls

    private static void keepBool(String key, boolean value) {
        try {
            MasterBoPanels.MOD.settings().putBoolean(key, value);
        } catch (Throwable failure) {
            MasterBoPanels.MOD.log("Master Bo could not keep " + key, failure);
        }
    }

    private void toggleMacros() {
        macrosShown = !macrosShown;
        keepBool(MACROS_KEY, macrosShown);
        jbi();
    }

    private void moveMacros() {
        macrosLow = !macrosLow;
        keepBool(MACROS_LOW_KEY, macrosLow);
        jbi();
    }

    private boolean macrosOn() {
        return macrosShown && !model.macros.isEmpty();
    }

    /** The section's height above the inserts: a title row, then rows of two (one when narrow). */
    private double macrosTopH(double width) {
        if (!macrosOn() || (macrosLow && !wide)) return 0;
        int cols = width >= eLY(200) ? 2 : 1;
        int rows = (model.macros.size() + cols - 1) / cols;
        return eLY(MACRO_TITLE) + rows * (double) eLY(MACRO_ROW) + eLY(4);
    }

    private static final String LUFS_LOW_KEY = "lufs_above_pan";
    private static boolean lufsLow = readLufsLow(); // true: the section sits above the pan, false: under the VU

    private static boolean readLufsLow() {
        try {
            return MasterBoPanels.MOD.settings().getBoolean(LUFS_LOW_KEY, false);
        } catch (Throwable failure) {
            return false;
        }
    }

    // ---------- The VU's place: top (under the header), middle (under the big meters), bottom (over the fader section) ----------
    private static final String VU_LOW_KEY = "vu_low";
    private static boolean vuLow = readVuLow(); // bottom: right above the separator (above the LUFS section when that is low too)
    private final java.util.List<double[]> vuMoveHits = new java.util.ArrayList<>(); // x, y, w, h, direction (-1 up, +1 down)

    private static boolean readVuLow() {
        try {
            return MasterBoPanels.MOD.settings().getBoolean(VU_LOW_KEY, false);
        } catch (Throwable failure) {
            return false;
        }
    }

    /** 0: top, 1: middle (the big meters above it), 2: bottom. */
    private static int vuSlot() {
        return vuLow ? 2 : bigMode == 1 ? 1 : 0;
    }

    /** Moves the VU one place up (-1) or down (+1): top, under the big meters (when shown), bottom. */
    private void moveVu(int direction) {
        int slot = vuSlot();
        if (direction > 0) {
            if (slot == 0 && bigMode == 2) bigMode = 1;   // top → under the big meters
            else if (slot < 2) vuLow = true;              // → bottom
        } else {
            if (slot == 2) {                              // bottom → under the big meters, or the top without them
                vuLow = false;
                if (bigMode > 0) bigMode = 1;
            } else if (slot == 1) {
                bigMode = 2;                              // under the big meters → top
            }
        }
        try {
            MasterBoPanels.MOD.settings().putBoolean(VU_LOW_KEY, vuLow);
            MasterBoPanels.MOD.settings().putInt(BIG_METERS_KEY, bigMode);
        } catch (Throwable failure) {
            MasterBoPanels.MOD.log("Master Bo could not keep the VU position", failure);
        }
        jbi();
    }

    /** A small triangle pointing up or down, centred at (cx, cy). */
    private void triangle(SYo g, double cx, double cy, boolean up, iok colour) {
        double tw = eLY(7), th = eLY(5);
        double tip = up ? cy - th / 2 : cy + th / 2, base = up ? cy + th / 2 : cy - th / 2;
        g.MRY(cx - tw / 2, base);
        g.cfM(cx + tw / 2, base);
        g.cfM(cx, tip);
        g.cfM(cx - tw / 2, base);
        g.Hnt(colour);
        g.wGc();
    }

    /**
     * The VU's move switch at (x .. x+w, y .. y+h): a triangle pointing where a click moves it,
     * two (up and down) in the middle place. Records the click areas.
     */
    private void vuMoveIcon(SYo g, double x, double y, double w, double h) {
        iok orange = theme("Accent (default)", ACCENT);
        double cx = x + w / 2, mid = y + h / 2;
        int slot = vuSlot();
        if (slot == 1) {
            triangle(g, cx, mid - eLY(4), true, orange);
            triangle(g, cx, mid + eLY(4), false, orange);
            vuMoveHits.add(new double[] {x, y, w, h / 2, -1});
            vuMoveHits.add(new double[] {x, mid, w, h / 2, 1});
        } else {
            boolean up = slot == 2;
            triangle(g, cx, mid, up, orange);
            vuMoveHits.add(new double[] {x, y, w, h, up ? -1 : 1});
        }
    }

    // the VU strip's smaller font (2 under the widget's)
    private com.bitwig.graphics.Hpk vuSmall;
    private double vuSmallAscent;
    private bitwig.obf.efu smallSpec;
    private String smallKey;

    // the spectrum scales' font (4 under the widget's)
    private bitwig.obf.efu tinySpec;
    private String tinyKey;

    private Hpk tinyFont(Hpk font) {
        try {
            bitwig.obf.efu base = MrC();
            if (base == null) return font;
            String key = base.Ucl() + "/" + base.ODA();
            if (!key.equals(tinyKey)) {
                tinySpec = bitwig.obf.efu.cfM(base.Ucl(), base.lYc(), Math.max(5f, base.ODA() - 4f));
                tinyKey = key;
            }
            Hpk tiny = kI_().cfM(tinySpec, this);
            return tiny != null ? tiny : font;
        } catch (Throwable failure) {
            return font;
        }
    }

    private bitwig.obf.efu boldSpec;
    private String boldKey;

    /** The widget's font in Bitwig's bold face, the same size. */
    private Hpk boldFont(Hpk font) {
        try {
            bitwig.obf.efu base = MrC();
            if (base == null) return font;
            String key = base.Ucl() + "/" + base.ODA();
            if (!key.equals(boldKey)) {
                boldSpec = bitwig.obf.efu.cfM("Bold", bitwig.obf.efs.FzB, base.ODA()); // efs.FzB: the bold face
                boldKey = key;
            }
            Hpk bold = kI_().cfM(boldSpec, this);
            return bold != null ? bold : font;
        } catch (Throwable failure) {
            return font;
        }
    }

    private Hpk smallFont(Hpk font) {
        try {
            bitwig.obf.efu base = MrC(); // the widget's font: name, style, size
            if (base == null) return font;
            String key = base.Ucl() + "/" + base.ODA();
            if (!key.equals(smallKey)) {
                smallSpec = bitwig.obf.efu.cfM(base.Ucl(), base.lYc(), Math.max(6f, base.ODA() - 2f));
                smallKey = key;
            }
            Hpk small = kI_().cfM(smallSpec, this);
            return small != null ? small : font;
        } catch (Throwable failure) {
            return font;
        }
    }

    private void moveLufs() {
        lufsLow = !lufsLow;
        try {
            MasterBoPanels.MOD.settings().putBoolean(LUFS_LOW_KEY, lufsLow);
        } catch (Throwable failure) {
            MasterBoPanels.MOD.log("Master Bo could not keep the LUFS position", failure);
        }
        jbi();
    }
    private double insertsX, insertsY, insertsW, insertsH;
    private double panX, panY, panW;
    private double stripX, stripY, stripW, stripH;
    private double scaleTop, scaleBottom; // the fader and meters' scale
    private double faderX, meterX0, meterX1, stripGroupX;

    private MasterBoWidget() {
    }

    static MasterBoWidget create(ViewContext context) {
        MasterBoWidget widget = new MasterBoWidget();
        widget.context = context;
        widget.window = (aBW) context.window();
        widget.model = new MasterBoModel(widget.window);
        widget.H_(); // Bitwig's initialisation (the framework puts it in Bitwig's panel widget)
        return widget;
    }

    // ---------- Sizes: no minimum, no maximum (fills its place like a stock panel) ----------

    @Override
    public tfK mL_() {
        return tfK.cfM(); // zero
    }

    @Override
    public tfK nj_() {
        return tfK.MRY(); // unbounded
    }

    @Override
    public dve MJ() {
        return this; // Bitwig's typed self
    }

    @Override
    public dns UZr() {
        return this; // Bitwig's typed self
    }

    // ---------- Display frames: read the master while shown ----------

    private final dmO tick = new dmO() {
        @Override
        public void aboutToPaint(dpM frame) {
            onFrame();
        }
    };

    private void onFrame() {
        try {
            if (!shown()) {
                stopFrames();
                return;
            }
            if (model.refresh()) jbi(); // jbi(): repaint
        } catch (Throwable failure) {
            stopFrames();
            MasterBoPanels.MOD.log("Master Bo stopped reading the master track", failure);
        }
    }

    private boolean shown() {
        return ZTl() && context.shown(); // ZTl(): attached to a window
    }

    private void startFrames() {
        if (frames != null || subscribing) return;
        subscribing = true;
        dpN.TVZ().asyncExec(() -> { // after the current paint
            subscribing = false;
            if (frames == null && shown()) frames = drd.cfM(tick, this); // this window's display frames
        });
    }

    private void stopFrames() {
        if (frames != null) {
            frames.cfM(); // unsubscribes
            frames = null;
        }
    }

    @Override
    public void iy_() {
        stopFrames(); // disposed: the window closes or is rebuilt
        super.iy_();
    }

    /** The UI scale around it changed (a panel scale): repaint at the new scale. */
    @Override
    public void cfM(duG window) {
        jbi();
    }

    // ---------- Layout ----------

    private void layout() {
        double w = Sxo(), h = yiX(); // Sxo(): width; yiX(): height
        // the panel as one mixer strip: a 1 px outline (EDGE) at its edges, the header right inside it, the
        // master's colour bar at the bottom; no margin between the edges and the content
        double pad = EDGE, gap = eLY(PAD);
        double bottom = h - EDGE - colourBarH() - eLY(BOTTOM_GAP); // where the content ends
        wide = w > h * 1.1 && w >= eLY(LEFT_MIN) + eLY(STRIP_MIN);
        if (wide) {
            double left = Math.max(eLY(LEFT_MIN), Math.min(eLY(LEFT_MAX), w * 0.5 - gap));
            headerX = pad; headerY = EDGE; headerW = left; // the header right at the top
            layoutBigIcon();
            vuX = pad; vuW = left; vuH = vuCollapsed ? eLY(VU_BAR) : vuHeight(left, h);
            vuFaceH = vuH; double specBlock = spectrumBlock(vuW, h);
            if (specWithVu()) vuH += specBlock; // the spectrum goes with the VU, right under it
            bigH = bigMode > 0 ? Math.max(eLY(BIG_MIN), Math.min(eLY(BIG_MAX), h * 0.3)) : 0; // no separator here: a share of the height
            double under = stackVuAndBig(headerY + eLY(HEADER) + eLY(6));
            panX = pad; panW = left; panY = bottom - eLY(PAN_HEIGHT) - eLY(14);
            lufsX = pad; lufsW = left; lufsH = model.lufsFound ? eLY(LUFS_H) : 0;
            boolean low = lufsLow && lufsH > 0;
            double abovePan = panY - eLY(18) - msRowH(); // above the pan (and the solo / mute row)
            lufsY = low ? abovePan - lufsH : under + eLY(6);
            if (vuLow) vuY = (low ? lufsY - eLY(6) : abovePan) - vuH; // above the LUFS section / the pan
            if (specBottomAlone()) specY = (low ? lufsY - eLY(6) : abovePan) - specH;
            insertsX = pad; insertsW = left; insertsY = low ? under + eLY(8) : lufsY + lufsH + eLY(lufsH > 0 ? 8 : 2);
            macrosH = macrosTopH(left); macrosX = pad; macrosW = left; macrosY = insertsY;
            insertsY += macrosH;
            int rowsWide = Math.max(1, model.inserts.size());
            double limitWide = vuLow ? vuY - eLY(8) : specBottomAlone() ? specY - eLY(8) : low ? lufsY - eLY(8) : abovePan;
            double mostWide = Math.max(0, limitWide - eLY(ROW) - eLY(4) - insertsY);
            insertsH = Math.min(rowsWide * (double) eLY(ROW), Math.floor(mostWide / eLY(ROW)) * eLY(ROW));
            latencyY = insertsY + insertsH + eLY(2);
            stripX = pad + left + gap * 2; stripY = headerY + eLY(6);
            stripW = Math.max(0, w - stripX - pad); stripH = Math.max(0, bottom - stripY);
            separatorY = -1; // no separator: the strip has its own column
        } else {
            headerX = pad; headerY = EDGE; headerW = Math.max(0, w - pad * 2); // the header right at the top
            layoutBigIcon();
            vuX = pad; vuW = headerW; vuH = vuCollapsed ? eLY(VU_BAR) : vuHeight(vuW, h);
            vuFaceH = vuH; double specBlock = spectrumBlock(vuW, h);
            if (specWithVu()) vuH += specBlock; // the spectrum goes with the VU, right under it
            lufsX = pad; lufsW = headerW; lufsH = model.lufsFound ? eLY(LUFS_H) : 0;
            boolean low = lufsLow && lufsH > 0;
            // the separator (dragged, kept) splits the panel: above it the big meters, the VU, the inserts
            // and the latency under the last one; under it the fader section: the pan, the readouts, the
            // fader and meters. The big meters keep their own height (a share of the panel) until the
            // fader section needs their room: then they get shorter, down to BIG_SHORTEST, while the
            // inserts keep all their rows (pushed up); after that the inserts give up rows.
            double latencyRow = eLY(ROW);
            double panBlock = eLY(18) + eLY(PAN_HEIGHT) + eLY(12) + msRowH();
            double top = headerY + eLY(HEADER) + eLY(6);
            double under0 = vuLow ? (specTopAlone() ? top + specH : top - eLY(6)) : top + vuH; // under the VU (and the spectrum), without big meters
            double insertsY0 = (low ? under0 + eLY(8) : under0 + eLY(6) + lufsH + eLY(lufsH > 0 ? 8 : 2)) + macrosTopH(headerW);
            int allRows = Math.max(1, model.inserts.size());
            double tail = latencyRow + eLY(6) + (low ? lufsH + eLY(6) : 0)   // under the inserts: the latency (and the LUFS section)
                    + (vuLow ? vuH + eLY(8) : 0)                                 // (and the VU at the bottom)
                    + (specBottomAlone() ? specH + eLY(8) : 0);                  // (and the spectrum at the bottom)
            double oneRow = insertsY0 + eLY(ROW) + tail;                        // above the separator with one insert row
            double allOfThem = insertsY0 + allRows * (double) eLY(ROW) + tail;  // ... with every insert row
            double bigGap = bigMode > 0 ? eLY(6) : 0;
            double bigDefault = bigMode > 0 ? Math.max(eLY(BIG_MIN), Math.min(eLY(BIG_MAX), h * 0.3)) : 0;
            double bigShortest = bigMode > 0 ? Math.min(bigDefault, eLY(BIG_SHORTEST)) : 0;
            double room = bottom - eLY(SEPARATOR) - oneRow - bigShortest - bigGap; // the most the fader section can have
            double wanted = faderHeight > 0 ? faderHeight * unit() : (h - top) * 0.62; // kept in layout units
            double section = Math.max(Math.min(eLY(FADER_MIN) + panBlock, Math.max(0, room)), Math.min(wanted, room));
            double aboveRoom = bottom - eLY(SEPARATOR) - section;               // all that is above the separator
            bigH = bigMode > 0 ? Math.max(bigShortest, Math.min(bigDefault, aboveRoom - allOfThem - bigGap)) : 0;
            double under = stackVuAndBig(top);
            lufsY = under + eLY(6); // moved under the separator below, when low
            insertsX = pad; insertsW = headerW; insertsY = low ? under + eLY(8) : lufsY + lufsH + eLY(lufsH > 0 ? 8 : 2);
            macrosH = macrosTopH(headerW); macrosX = pad; macrosW = headerW; macrosY = insertsY;
            insertsY += macrosH;
            separatorY = bottom - section - eLY(SEPARATOR);
            if (low) lufsY = separatorY - eLY(4) - lufsH; // right above the separator
            if (vuLow) vuY = (low ? lufsY - eLY(6) : separatorY - eLY(4)) - vuH; // above it (and above a low LUFS section)
            if (specBottomAlone()) specY = (low ? lufsY - eLY(6) : separatorY - eLY(4)) - specH;
            panX = pad; panW = headerW; panY = separatorY + eLY(SEPARATOR) + eLY(18) + msRowH();
            stripX = pad; stripY = panY + eLY(PAN_HEIGHT) + eLY(12);
            stripW = headerW; stripH = Math.max(0, bottom - stripY);
            if (macrosOn() && macrosLow) { // the remote controls left of the fader section; the pan, meters and fader to the right
                macrosX = pad; macrosW = Math.round(Math.min(headerW * 0.45, eLY(120)));
                macrosY = separatorY + eLY(SEPARATOR) + eLY(6); macrosH = Math.max(0, bottom - macrosY);
                double right = macrosX + macrosW + eLY(6);
                panX = right; panW = Math.max(0, pad + headerW - right);
                stripX = right; stripW = panW;
            }
            int rows = Math.max(1, model.inserts.size());
            double limit = vuLow ? vuY - eLY(4) : specBottomAlone() ? specY - eLY(4) : low ? lufsY - eLY(4) : separatorY;
            double most = Math.max(0, limit - eLY(6) - latencyRow - insertsY);
            insertsH = Math.min(rows * (double) eLY(ROW), Math.floor(most / eLY(ROW)) * eLY(ROW));
            latencyY = insertsY + insertsH + eLY(2);
        }
        // the strip, as Bitwig's mixer has it: readouts on top, then labels | meter L | meter R | the fader bar
        // with the big meters shown, the strip is the scale and the fader only (no meters)
        boolean meters = bigMode == 0;
        double metersW = meters ? 2 * eLY(METER_WIDTH) + eLY(METER_GAP) : 0;
        double group = eLY(SCALE_WIDTH) + metersW + eLY(FADER_GAP) + eLY(FADER_WIDTH);
        double x0 = stripX + Math.max(0, (stripW - group) / 2);
        stripGroupX = x0;
        meterX0 = x0 + eLY(SCALE_WIDTH);
        meterX1 = meterX0 + eLY(METER_WIDTH) + eLY(METER_GAP);
        faderX = meterX0 + metersW + eLY(FADER_GAP);
        // the pan above the strip: no wider than the scale, meters and fader, its right edge the fader's
        double faderRight = Math.round(faderX + eLY(FADER_WIDTH));
        if (x0 >= panX - 1 && faderRight <= panX + panW + 1) {
            double l = Math.max(panX, Math.round(x0));
            panW = Math.max(eLY(20), faderRight - l);
            panX = faderRight - panW;
        }
        scaleTop = stripY + eLY(READOUT) + eLY(CLIP) + eLY(5);
        scaleBottom = Math.max(scaleTop + 1, stripY + stripH - eLY(4));
        // the VU's own height again; the spectrum sits under it
        specX = vuX; specW = vuW;
        if (specWithVu()) specY = vuY + vuFaceH + eLY(6);
        vuH = vuFaceH;
    }

    /** The big meters' toggle in the header's right end. */
    private void layoutBigIcon() {
        bigIconW = eLY(BIG_ICON); bigIconH = eLY(HEADER);
        bigIconX = headerX + headerW - bigIconW - eLY(4); bigIconY = headerY;
        macroIconX = bigIconX - bigIconW; // the remote controls' knob, left of it
        bigX = headerX; bigW = headerW;
    }

    /**
     * Stacks the VU and the big meters under the header (the big meters above the VU in mode 1,
     * under it in mode 2) and returns the y under the lower of them.
     */
    private double stackVuAndBig(double top) {
        if (vuLow) { // the VU sits at the bottom: only the big meters (and a spectrum at the top) up here
            bigY = top;
            double under = bigMode > 0 ? bigY + bigH : top - eLY(6);
            if (specTopAlone()) {
                specY = under + eLY(6);
                under = specY + specH;
            }
            return under;
        }
        if (bigMode == 2) {
            vuY = top;
            bigY = vuY + vuH + eLY(6);
            return bigY + bigH;
        }
        bigY = top;
        vuY = bigMode == 1 ? bigY + bigH + eLY(6) : top;
        return vuY + vuH;
    }

    /** The VU's height: a face's proportions (two faces side by side when wide enough), at most a quarter of the panel. */
    private double vuHeight(double width, double h) {
        double face = width >= eLY(VU_TWO_FACES) ? (width - eLY(6)) / 2 : width;
        return Math.max(0, Math.min(Math.min(face * 0.62, eLY(VU_MAX_HEIGHT)), h * 0.25));
    }

    /** Pixels per layout unit (Bitwig's UI scale and the panel's). */
    private double unit() {
        return eLY(1000) / 1000.0;
    }

    private static boolean readCollapsed() {
        try {
            return MasterBoPanels.MOD.settings().getBoolean(VU_COLLAPSED_KEY, false);
        } catch (Throwable failure) {
            return false;
        }
    }

    private static void setCollapsed(boolean collapsed) {
        vuCollapsed = collapsed;
        try {
            MasterBoPanels.MOD.settings().putBoolean(VU_COLLAPSED_KEY, collapsed);
        } catch (Throwable failure) {
            MasterBoPanels.MOD.log("could not keep the VU's state", failure);
        }
    }

    private static int readFaderHeight() {
        try {
            return Math.max(0, MasterBoPanels.MOD.settings().getInt(FADER_HEIGHT_KEY, 0));
        } catch (Throwable failure) {
            return 0;
        }
    }

    private static void keepFaderHeight(int units) {
        faderHeight = Math.max(0, units);
        try {
            MasterBoPanels.MOD.settings().putInt(FADER_HEIGHT_KEY, faderHeight);
        } catch (Throwable failure) {
            MasterBoPanels.MOD.log("could not keep the fader section's height", failure);
        }
    }

    private double posY(double position) {
        return scaleTop + (1 - position) * (scaleBottom - scaleTop);
    }

    // ---------- Painting ----------

    @Override
    public void cfM(SYo g) {
        startFrames(); // (Bitwig's own paint for a widget draws nothing; the panel's background is its parent's)
        try {
            if (!model.hasMaster()) model.refresh();
            layout();
            paintAll(g);
        } catch (Throwable failure) {
            MasterBoPanels.MOD.log("Master Bo could not draw", failure);
        }
    }

    private void paintAll(SYo g) {
        Hpk font = kI_().cfM(MrC(), this); // the widget's font (as a label's)
        iok text = e3y().cfM(this);        // the theme's text colour
        double ascent = font.cfM().cfM();  // the font's ascent
        g.qoU();                           // save
        g.cfM(font);
        eeY background = jl_().BAa().cfM("Grey 1"); // the theme's dark grey
        if (background != null) {
            rect(g, 0, 0, Sxo(), yiX(), background.cfM(this));
        }
        if (!model.hasMaster()) {
            g.cfM(eLY(PAD), eLY(PAD) + ascent, "No project", text.cfM(0.5F));
        } else {
            paintLowerBackground(g);
            paintVu(g, font, text, ascent);
            paintSpectrum(g, font, text, ascent);
            paintHeader(g, font, text, ascent);
            paintBig(g, font, text, ascent);
            paintLufs(g, font, text, ascent);
            paintMacros(g, font, text);
            paintInserts(g, font, text, ascent);
            paintLatency(g, font, text, ascent);
            paintPan(g, font, text, ascent);
            paintSeparator(g, text);
            paintStrip(g, font, text, ascent);
        }
        paintFrame(g);
        if (model.hasMaster()) paintPopup(g, kI_().cfM(MrC(), this), e3y().cfM(this)); // over everything
        g.ucQ();                           // restore
    }

    /**
     * BO LUFS: M, S, I and TP in four cells, label over value. S and I turn orange at or above the
     * device's Ref.LUFS, TP red at or above 0 dBTP (as on the device).
     */
    private void paintLufs(SYo g, Hpk font, iok text, double ascent) {
        if (lufsH <= 0) return;
        String[] labels = {"M", "S", "I", "TP"};
        double gap = eLY(3);
        double iw = eLY(LUFS_ICON);
        double cw = (lufsW - iw - 4 * gap) / 4;
        // the move switch: one triangle pointing where a click moves the section (under the VU / above the pan)
        double ix = lufsX + lufsW - iw, mid = lufsY + lufsH / 2;
        triangle(g, ix + iw / 2, mid, lufsLow, theme("Accent (default)", ACCENT)); // pointing where a click moves it
        iok orange = theme("Accent (default)", ACCENT);
        iok red = theme("Meter Clipping", RED);
        for (int i = 0; i < 4; i++) {
            double x = lufsX + i * (cw + gap);
            rect(g, x, lufsY, cw, lufsH, theme("Grey 1", iok.cfM(26, 26, 26))); // dark, as the fader's background
            double v = model.lufs[i];
            String label = labels[i];
            g.cfM(x + (cw - font.cfM(label).IwV()) / 2, lufsY + eLY(3) + ascent, label, text.cfM(0.45F));
            boolean none = v <= -99.95;
            String value = none ? "-" : (i == 3 && v > 0.05 ? "+" : "") + String.format(java.util.Locale.ENGLISH, "%.1f", v);
            iok ink = text;
            if (!none && (i == 1 || i == 2) && v > -70 && v >= model.lufsRef) ink = orange;
            if (!none && i == 3 && v >= 0) ink = red;
            if (none) ink = text.cfM(0.4F);
            g.cfM(x + (cw - font.cfM(value).IwV()) / 2, lufsY + lufsH - eLY(5), value, ink);
        }
    }

    private void paintHeader(SYo g, Hpk font, iok text, double ascent) {
        double h = eLY(HEADER);
        // the whole header in the master's colour; on a bright colour the name and the icon turn dark
        // grey, as Bitwig's mixer track headers do
        iok colour = model.colour;
        rect(g, headerX, headerY, headerW, h, colour);
        boolean bright = colour.MRY() > 0.6f; // MRY(): the colour's luminance (0 .. 1)
        iok ink = bright ? iok.cfM(38, 38, 38) : text;
        String title = fit(font, model.name.toUpperCase(java.util.Locale.ROOT), headerW - eLY(16) - 2 * eLY(BIG_ICON) - eLY(4));
        g.cfM(headerX + eLY(10), headerY + (h + ascent) / 2 - 1, title, ink);
        // Show Big Meters (Bitwig's icon: a small scale and two bars) and the remote controls (a knob):
        // orange while on (dark grey on a bright header), the ink dimmed while off
        iok accent = theme("Accent (default)", ACCENT);
        iok bars = bigMode > 0 ? (bright ? ink : accent) : ink.cfM(bright ? 0.55F : 0.45F);
        double u = eLY(1), ih = Math.round(11 * u);
        double ix = Math.round(bigIconX + (bigIconW - 9 * u) / 2), iy = Math.round(headerY + (h - ih) / 2);
        for (int k = 0; k < 3; k++) rect(g, ix, iy + Math.round(k * 5 * u), Math.max(1, Math.round(u)), Math.max(1, Math.round(u)), bars);
        for (int k = 0; k < 2; k++) rect(g, ix + Math.round((2 + 4 * k) * u), iy, Math.round(3 * u), ih, bars);
        iok knob = macrosShown ? (bright ? ink : accent) : ink.cfM(bright ? 0.55F : 0.45F);
        double r = 6 * u, kx = macroIconX + bigIconW / 2, ky = headerY + h / 2;
        g.MRY(kx, ky, r);
        g.Hnt(knob);
        g.wGc();
        rect(g, Math.round(kx - u), Math.round(ky - r), Math.max(2, Math.round(2 * u)), Math.round(r - u), colour); // its pointer: a slot up to the middle
    }

    /** The big meters: Bitwig's tall pair (peak lighter, RMS solid, the hold line), labels on the left, marks on the right. */
    private void paintBig(SYo g, Hpk font, iok text, double ascent) {
        if (bigH <= 0) return;
        iok well = text.cfM(0.07F);
        double scaleW = eLY(BIG_SCALE), gap = eLY(2), right = eLY(8);
        double mw = Math.max(eLY(4), Math.min(eLY(BIG_METER_MAX), (bigW - scaleW - gap - right) / 2));
        double group = scaleW + 2 * mw + gap + right;
        double x0 = bigX + Math.max(0, (bigW - group) / 2) + scaleW;
        double x1 = x0 + mw + gap;
        double top = bigY + eLY(2), bottom = bigY + bigH - eLY(2);
        rect(g, x0, top, mw, bottom - top, well);
        rect(g, x1, top, mw, bottom - top, well);
        // labels (where they have room) and their marks right of the meters
        double room = ascent + eLY(2);
        java.util.List<Double> placed = new java.util.ArrayList<>();
        placed.add(bottom - ascent / 2);
        String inf = "\u221E";
        g.cfM(x0 - eLY(7) - font.cfM(inf).IwV(), bottom - 1, inf, text.cfM(0.75F));
        for (double mark : BIG_LABELS) {
            double y = bigY(bigPosition(mark), top, bottom);
            boolean free = true;
            for (double other : placed) if (Math.abs(other - y) < room) { free = false; break; }
            if (!free || y < top + ascent / 2 - 1) continue;
            placed.add(y);
            String label = String.valueOf((int) Math.round(Math.abs(mark)));
            g.cfM(x0 - eLY(7) - font.cfM(label).IwV(), y + ascent / 2 - 1, label, text.cfM(0.75F));
            rect(g, x0 - eLY(4), y - 0.5, eLY(2), 1, text.cfM(0.45F));      // the mark beside the digits
            rect(g, x1 + mw + eLY(3), y - 0.5, eLY(2), 1, text.cfM(0.45F)); // and right of the meters
        }
        if (model.meterCount == 0) return;
        for (int ch = 0; ch < 2; ch++) {
            double x = ch == 0 ? x0 : x1;
            bigBar(g, x, mw, top, bottom, model.peak[ch], 1F); // one bar, as Bitwig's: the peak
            double holdPos = bigPosition(MasterBoModel.dB(model.hold[ch]));
            if (holdPos > 0.002) {
                double y = bigY(holdPos, top, bottom);
                rect(g, x, y - 1, mw, 2, holdColour(y, bottom, bigY(bigPosition(0), top, bottom)));
            }
        }
    }

    private static double bigY(double position, double top, double bottom) {
        return top + (1 - position) * (bottom - top);
    }

    /** A level bar on the big meters, in Bitwig's mixer gradient (by the level at each height). */
    private void bigBar(SYo g, double x, double w, double top, double bottom, double amplitude, float alpha) {
        double position = bigPosition(MasterBoModel.dB(amplitude));
        if (position <= 0) return;
        gradientBar(g, x, w, bigY(position, top, bottom), bottom, y -> bigDb(1 - (y - top) / (bottom - top)));
    }

    /** The level at a position on the big meters (bigPosition's inverse). */
    static double bigDb(double position) {
        if (position <= 0) return Double.NEGATIVE_INFINITY;
        if (position >= 1) return BIG_DB[0];
        int last = BIG_DB.length - 1;
        if (position < BIG_POS[last]) return BIG_DB[last] + 20 * Math.log10(position / BIG_POS[last]);
        int i = 0;
        while (i < last - 1 && position < BIG_POS[i + 1]) i++;
        double t = (position - BIG_POS[i + 1]) / (BIG_POS[i] - BIG_POS[i + 1]);
        return BIG_DB[i + 1] + t * (BIG_DB[i] - BIG_DB[i + 1]);
    }

    // an insert being dragged to another place in the chain: its row, and the gap it would land in
    private int dragFrom = -1, dragGap = -1;

    private void paintInserts(SYo g, Hpk font, iok text, double ascent) {
        double row = eLY(ROW);
        iok slot = themeGrey("Grey 4", 61, "Grey 5", 73, 8 / 12.0); // (69) each FX slot, as Bitwig's mixer has them (1 px of the background between)
        List<MasterBoModel.Insert> inserts = model.inserts;
        if (inserts.isEmpty()) {
            if (insertsH >= row) rect(g, insertsX, insertsY, insertsW, row - 1, slot);
            if (insertsH >= row) g.cfM(insertsX + eLY(8), insertsY + (row + ascent) / 2 - 1, "No inserts", text.cfM(0.4F));
            return;
        }
        int fit = (int) Math.floor(insertsH / row);
        int shown = inserts.size() > fit ? Math.max(0, fit - 1) : inserts.size();
        for (int i = 0; i < shown; i++) {
            MasterBoModel.Insert insert = inserts.get(i);
            double top = insertsY + i * row;
            rect(g, insertsX, top, insertsW, Math.round(top + row) - Math.round(top) - 1, slot);
            double dot = eLY(7), dx = insertsX + eLY(7) + dot / 2, dy = top + row / 2;
            g.MRY(dx, dy, dot / 2); // the on/off dot, as Bitwig's device headers have it
            g.Hnt(insert.enabled ? theme("Activation Yellow", ACTIVATION) : text.cfM(0.22F));
            g.wGc();
            double nameX = insertsX + eLY(7) + dot + eLY(8);
            double nameEnd = insertsX + insertsW - (insert.plugin ? eLY(WINDOW_BUTTON) : 0) - eLY(6);
            String name = fit(font, insert.name, nameEnd - nameX);
            g.cfM(nameX, top + (row + ascent) / 2 - 1, name, insert.enabled ? text : text.cfM(0.4F));
            if (insert.plugin) paintWindowButton(g, text, insertsX + insertsW - eLY(WINDOW_BUTTON), top, eLY(WINDOW_BUTTON), row, insert.windowOpen);
            if (i == dragFrom) rect(g, insertsX, top, insertsW, row, text.cfM(0.08F)); // the row being dragged
            if (insert.device == model.selectedInsert && hasKeyboard()) { // selected (while Delete reaches it): Bitwig's selection look (a light fill and an orange edge)
                rect(g, insertsX, top, insertsW, row, text.cfM(0.10F));
                rect(g, insertsX, top, eLY(2), row, theme("Accent (default)", ACCENT));
            }
        }
        // dragging: a bright line in the gap where the insert would land
        if (dragFrom >= 0 && dragGap >= 0 && dragGap != dragFrom && dragGap != dragFrom + 1) {
            double y = insertsY + Math.min(dragGap, shown) * row;
            rect(g, insertsX + eLY(2), y - eLY(2) / 2.0, insertsW - eLY(4), Math.max(2, eLY(2)), text);
        }
        if (shown < inserts.size() && fit > 0) {
            double top = insertsY + shown * row;
            g.cfM(insertsX + eLY(8), top + (row + ascent) / 2 - 1, "+" + (inserts.size() - shown) + " more", text.cfM(0.45F));
        }
    }

    /**
     * A plug-in's window button: Bitwig's own icon (plugin_window.svg, as on a plug-in's device
     * header), orange while the window is open, grey while it is closed.
     */
    private void paintWindowButton(SYo g, iok text, double x, double y, double w, double h, boolean open) {
        BrJ icon = windowIcon();
        iok ink = open ? theme("Accent (default)", ACCENT) : text.cfM(0.55F); // orange while the window is open
        if (icon != null) {
            g.Hnt(ink); // Bitwig draws its icons as a shape in the current colour
            icon.cfM(g, x + (w - icon.IwV()) / 2, y + (h - icon.UNj()) / 2); // IwV/UNj: the icon's width and height
        } else {
            strokeRect(g, x + (w - eLY(12)) / 2 + 0.5, y + (h - eLY(9)) / 2 + 0.5, eLY(12) - 1, eLY(9) - 1, ink);
        }
    }

    private BrJ icon;
    private double iconScale;

    /** plugin_window.svg at the widget's scale, loaded once per scale by the window's icon loader. */
    private BrJ windowIcon() {
        double scale = eLY(1000) / 1000.0; // the widget's scale (Bitwig's UI scale and the panel's)
        if (icon == null || scale != iconScale) {
            try {
                icon = kI_().cfM("plugin_window.svg", scale); // kI_(): the window; cfM(name, scale): one of Bitwig's icons
                iconScale = scale;
            } catch (Throwable failure) {
                icon = null;
                iconScale = scale;
            }
        }
        return icon;
    }

    // ---------- VU ----------

    private void paintVu(SYo g, Hpk font, iok text, double ascent) {
        sourceHits.clear();
        referenceHits.clear();
        vuMoveHits.clear();
        if (vuW <= 0) return;
        if (vuCollapsed) { // folded: a bar in the MASTER header's grey; a click opens it again
            rect(g, vuX, vuY, vuW, vuH, text.cfM(0.06F)); // the header's fill
            g.cfM(vuX + eLY(8), vuY + (vuH + ascent) / 2 - 1, "VU Meter", text.cfM(0.7F));
            vuMoveIcon(g, vuX + vuW - eLY(VU_MOVE), vuY, eLY(VU_MOVE), vuH);
            return;
        }
        vuSmall = smallFont(font);
        vuSmallAscent = vuSmall.cfM().cfM();
        if (vuH < eLY(30)) return;
        vuLabelHits.clear();
        vuFace = theme("Grey 0", iok.cfM(15, 15, 15));        // the face: the theme's darkest grey
        vuBorder = theme("Grey 4", iok.cfM(61, 61, 61));
        vuInk = text;                                           // printing and the needle: the theme's text
        vuRed = theme("Meter Clipping", RED);                   // the red zone
        vuSecond = theme("Accent (default)", ACCENT);           // the right needle on a shared face: Bitwig's orange
        vuHousing = theme("Grey 2", iok.cfM(38, 38, 38));      // the strip over the needle's pivot (L R, the reference)
        vuHousingText = text.cfM(0.7F);
        switch (vuScheme) { // a click on "VU" goes through these, kept in Master Bo's settings
            case 1 -> { // dark grey, the + range in Bitwig's orange
                vuFace = theme("Grey 3", iok.cfM(50, 50, 50));
                vuBorder = theme("Grey 5", iok.cfM(73, 73, 73));
                vuRed = theme("Accent (default)", ACCENT);
            }
            case 2 -> { // white (95 %), black printing and needles
                vuFace = iok.cfM(242, 242, 242);
                vuInk = iok.cfM(0, 0, 0);
                vuRed = iok.cfM(0, 0, 0);
                vuSecond = iok.cfM(0, 0, 0);
                vuBorder = theme("Grey 4", iok.cfM(61, 61, 61));
            }
            case 3 -> { // vintage: light cream, black needles, black and red printing
                vuFace = iok.cfM(245, 234, 196);
                vuInk = iok.cfM(0, 0, 0);
                vuRed = iok.cfM(196, 32, 28);
                vuSecond = iok.cfM(0, 0, 0);
                vuBorder = theme("Grey 4", iok.cfM(61, 61, 61));
            }
            default -> { }
        }
        if (vuW >= eLY(VU_TWO_FACES)) {
            double faceW = (vuW - eLY(6)) / 2;
            paintFace(g, font, ascent, vuX, vuY, faceW, vuH, "L", 0, -1); // the faces fill the width, as the sections under them
            double x2 = vuX + faceW + eLY(6);
            paintFace(g, font, ascent, x2, vuY, faceW, vuH, "R", 1, -1);
        } else {
            double faceW = vuW; // as wide as the spectrum, the goniometer and the rest
            paintFace(g, font, ascent, vuX + (vuW - faceW) / 2, vuY, faceW, vuH, "", 0, 1);
        }
    }

    /** The VU marks: values in VU and their labels, as printed on a VU meter's scale. */
    private static final double[] VU_MARKS = {-20, -10, -7, -5, -3, -2, -1, 0, 1, 2, 3};
    private static final String[] VU_LABELS = {"20", "10", "7", "5", "3", "2", "1", "0", "1", "2", "3"};
    private static final double SWEEP = Math.toRadians(48); // each side of the vertical

    private static double deflection(double vuValue) {
        return Math.pow(10, vuValue / 20) / MasterBoModel.VU_FULL;
    }

    /** One face: cream, the arc with its marks and red zone, "VU", the reference, the needle(s). */
    private void paintFace(SYo g, Hpk font, double ascent, double x, double y, double w, double h, String label, int ch, int second) {
        rect(g, x, y, w, h, vuFace);
        double cx = x + w / 2;
        double cy = y + h * 1.05;                  // the pivot, under the face
        double r = Math.min(h * 0.86, (w / 2 - eLY(8)) / Math.sin(SWEEP)); // the arc's radius
        double tickIn = r * 0.9, tickOut = r;
        // the arc: black up to 0 VU, a thick red band from 0 to +3
        polyArc(g, cx, cy, r, 0, deflection(0), vuInk, vuScheme >= 2 ? 1.5 : 1);
        polyArc(g, cx, cy, r, deflection(0), 1, vuRed, 1);
        polyArc(g, cx, cy, r * 0.965, deflection(0), 1, vuRed, Math.max(2, eLY(3)));
        for (int i = 0; i < VU_MARKS.length; i++) {
            double f = deflection(VU_MARKS[i]);
            double a = angle(f);
            boolean red = VU_MARKS[i] > 0;
            boolean major = VU_MARKS[i] == 0 || VU_MARKS[i] == -20 || VU_MARKS[i] == -10 || VU_MARKS[i] == 3;
            boolean light = vuScheme >= 2; // light faces: the printing at full strength
            iok ink = red ? vuRed : major || light ? vuInk : vuInk.cfM(0.7F);
            line(g, cx + Math.sin(a) * (major ? tickIn - eLY(2) : tickIn), cy - Math.cos(a) * (major ? tickIn - eLY(2) : tickIn),
                    cx + Math.sin(a) * tickOut, cy - Math.cos(a) * tickOut, ink, major ? 2 : 1);
            double lr = r + eLY(3);
            double lw = font.cfM(VU_LABELS[i]).IwV();
            if (VU_MARKS[i] == -7 || VU_MARKS[i] == -2) continue; // too close to their neighbours on a small face
            g.cfM(cx + Math.sin(a) * lr - lw / 2, cy - Math.cos(a) * lr - eLY(2), VU_LABELS[i], ink);
        }
        String vu = "VU";
        double vuWidth = font.cfM(vu).IwV();
        g.cfM(cx - vuWidth / 2, y + h * 0.6, vu, second >= 0 ? vuSecond : vuInk); // the needle's colour
        vuLabelHits.add(new double[] {cx - vuWidth / 2 - eLY(6), y + h * 0.6 - ascent - eLY(4), vuWidth + eLY(12), ascent + eLY(8)}); // a click: the next colour scheme
        // the needle(s), then the strip over their pivot, with the reference and the channel on it
        needle(g, cx, cy, r, y + h, model.vu[ch], vuInk);
        if (second >= 0) needle(g, cx, cy, r, y + h, model.vu[second], vuSecond);
        double strip = Math.max(ascent + eLY(5), h * 0.2);
        rect(g, x + 1, y + h - strip, w - 2, strip - 1, vuHousing);
        double stripTop = y + h - strip;
        // the move switch at the right end (on the right face when there are two)
        boolean switchHere = !"L".equals(label);
        double switchW = switchHere ? eLY(VU_MOVE) : 0;
        if (switchHere) vuMoveIcon(g, x + w - switchW - eLY(2), stripTop, switchW, strip);
        // the strip, left to right with room between, in the smaller font: the channel (L, R, or L/R on a
        // shared face) in grey, the source (PRE / POST) and the reference's value in orange, "0 VU =" in grey
        Hpk sf = vuSmall != null ? vuSmall : font;
        double sa = vuSmall != null ? vuSmallAscent : ascent;
        g.cfM(sf);
        double base = y + h - (strip - sa) / 2 - 1;
        iok orange = theme("Accent (default)", ACCENT);
        String channel = second < 0 ? label : "L/R";
        String source = model.vuPre ? (model.preAvailable ? "PRE" : "PRE?") : "POST";
        String value = model.vuReference + " dBFS";
        String equals = "0 VU =";
        double gap = eLY(10), space = sf.cfM(" ").IwV();
        double room = w - eLY(10) - switchW;
        double full = sf.cfM(channel).IwV() + gap + sf.cfM(source).IwV() + gap + sf.cfM(equals).IwV() + space + sf.cfM(value).IwV();
        // too long: first without "dBFS", then also without "0 VU ="
        boolean shortForm = false;
        if (full > room) {
            value = String.valueOf(model.vuReference);
            full = sf.cfM(channel).IwV() + gap + sf.cfM(source).IwV() + gap + sf.cfM(equals).IwV() + space + sf.cfM(value).IwV();
            shortForm = full > room;
        }
        double tx = x + eLY(5);
        tx = text(g, sf, tx, base, channel, vuHousingText) + gap;
        double sx = tx;
        tx = text(g, sf, tx, base, source, orange);
        sourceHits.add(new double[] {sx - eLY(3), stripTop, tx - sx + eLY(6), strip}); // a click: PRE / POST
        tx += gap;
        if (!shortForm) tx = text(g, sf, tx, base, equals, vuHousingText) + space;
        if (tx + sf.cfM(value).IwV() <= x + w - eLY(4) - switchW) {
            double vx = tx;
            tx = text(g, sf, tx, base, value, orange);
            referenceHits.add(new double[] {vx - eLY(3), stripTop, tx - vx + eLY(6), strip}); // a click: the next reference
        }
        g.cfM(font);
    }

    /** Draws a text and returns where it ends. */
    private static double text(SYo g, Hpk font, double x, double baseline, String s, iok colour) {
        g.cfM(x, baseline, s, colour);
        return x + font.cfM(s).IwV();
    }

    private static double angle(double f) {
        double a = -SWEEP + 2 * SWEEP * Math.max(-0.04, Math.min(1.06, f)); // the needle's stops a little outside the arc
        return a;
    }

    private void needle(SYo g, double cx, double cy, double r, double faceBottom, double f, iok colour) {
        double a = angle(f);
        double start = (cy - faceBottom + eLY(2)) / Math.cos(a); // from the face's lower edge
        double end = r * 1.04;
        line(g, cx + Math.sin(a) * start, cy - Math.cos(a) * start, cx + Math.sin(a) * end, cy - Math.cos(a) * end, colour, Math.max(1.2, eLY(3) / 2.0));
    }

    /** An arc between two deflections, as short lines. */
    private void polyArc(SYo g, double cx, double cy, double r, double f0, double f1, iok colour, double width) {
        int n = 24;
        double a0 = angle(f0), a1 = angle(f1);
        g.MRY(cx + Math.sin(a0) * r, cy - Math.cos(a0) * r);
        for (int i = 1; i <= n; i++) {
            double a = a0 + (a1 - a0) * i / n;
            g.cfM(cx + Math.sin(a) * r, cy - Math.cos(a) * r);
        }
        g.Hnt(colour);
        g.IwV(width);
        g.hbP();
    }

    // ---------- The master's solo and mute, a row above the pan, as Bitwig's mixer has them ----------

    private static final long MS_ROW = 30;
    private static final double MS_BUTTON_H = 23; // the S and M buttons' height (their width: half the pan's each)
    private final List<double[]> soloMuteHits = new java.util.ArrayList<>();

    private double msRowH() {
        return model.soloMuteFound ? eLY(MS_ROW) : 0;
    }

    /** S (solo: yellow when on) and M (mute: orange when on); off, grey with a light letter. */
    private void paintSoloMute(SYo g, Hpk font, iok text) {
        soloMuteHits.clear();
        if (!model.soloMuteFound) return;
        // the two buttons together as wide as the pan (the scale, meters and fader under it), centred over it
        double gap = eLY(3), bh = Math.round(MS_BUTTON_H * eLY(10) / 10.0);
        double left = Math.round(panX), right = Math.round(panX + panW);
        double bw = Math.floor((right - left - gap) / 2);
        left = Math.round((left + right - 2 * bw - gap) / 2);
        double top = panY - eLY(16) - eLY(MS_ROW) + eLY(2);
        Hpk small = boldFont(font); // the letters bold, in the panel's size
        double sa = small.cfM().cfM();
        g.cfM(small);
        iok yellow = theme("Solo", iok.cfM(232, 203, 70));
        iok orange = theme("Accent (default)", ACCENT);
        for (int k = 0; k < 2; k++) {
            boolean on = k == 0 ? model.soloOn : model.muteOn;
            double x = left + k * (bw + gap);
            rect(g, x, top, bw, bh, on ? (k == 0 ? yellow : orange) : themeGrey("Grey 4", 61, "Grey 5", 73, 8 / 12.0)); // off: the FX slots' grey
            String letter = k == 0 ? "S" : "M";
            double lw = small.cfM(letter).IwV();
            iok ink = on ? iok.cfM(20, 20, 20) : text.cfM(0.85F);
            double lx = x + (bw - lw) / 2, ly = top + (bh + sa) / 2 - 1;
            text(g, small, lx, ly, letter, ink);
            text(g, small, lx + 0.6, ly, letter, ink); // drawn twice, a hair apart: bolder than the bold face alone
            soloMuteHits.add(new double[] {x, top, bw, bh, k});
        }
        g.cfM(font);
    }

    private void paintPan(SYo g, Hpk font, iok text, double ascent) {
        paintSoloMute(g, font, text);
        double h = eLY(PAN_HEIGHT);
        g.cfM(panX, panY - eLY(4), "PAN", text.cfM(0.45F));
        String value = model.panText();
        double vw = font.cfM(value).IwV(); // the label's advance
        g.cfM(panX + panW - vw, panY - eLY(4), value, text);
        rect(g, panX, panY, panW, h, text.cfM(0.07F));
        double centre = panX + panW / 2;
        double x = panX + model.panNorm * panW;
        iok accent = theme("Accent (default)", ACCENT); // Bitwig's orange, as the fader
        rect(g, Math.min(centre, x), panY + eLY(3), Math.abs(x - centre), h - eLY(6), accent.cfM(0.45F));
        line(g, centre, panY, centre, panY + h, text.cfM(0.3F));
        rect(g, x - 1, panY, 2, h, accent);
    }

    /** The latency row under the inserts: the master's latency (value only, right-aligned), as the device panel shows it. */
    private void paintLatency(SYo g, Hpk font, iok text, double ascent) {
        double row = eLY(ROW);
        if (latencyY < insertsY) return;
        double base = latencyY + (row + ascent) / 2 - 1;
        // "+": insert a device after the last one (Bitwig's pop-up browser), as the mixer's device slots
        double s = eLY(9), px = insertsX + eLY(4), py = latencyY + (row - s) / 2;
        iok plus = text.cfM(0.75F);
        line(g, px, py + s / 2, px + s, py + s / 2, plus, Math.max(1, eLY(3) / 2.0));
        line(g, px + s / 2, py, px + s / 2, py + s, plus, Math.max(1, eLY(3) / 2.0));
        String value = String.format(java.util.Locale.ENGLISH, "%.1f ms", model.latencyMs);
        g.cfM(insertsX + insertsW - font.cfM(value).IwV(), base, value, text.cfM(0.75F));
    }

    /**
     * Under the big meters (or the header, without them) to the bottom: (35, 35, 35); behind the pan,
     * the scale, the meters and the fader: (26, 26, 26), from the PAN label down.
     */
    private double colourBarH() { return Math.round(eLY(COLOUR_BAR)); }

    /** The master's colour bar at the bottom and the near-black 1 px outline around the whole panel, as a mixer strip. */
    private void paintFrame(SYo g) {
        double w = Sxo(), h = yiX(), bar = colourBarH();
        if (model.hasMaster()) rect(g, EDGE, h - EDGE - bar, w - 2 * EDGE, bar, model.colour);
        iok outline = theme("Grey 0", iok.cfM(15, 15, 15)); // the theme's darkest grey
        rect(g, 0, 0, w, EDGE, outline);
        rect(g, 0, h - EDGE, w, EDGE, outline);
        rect(g, 0, 0, EDGE, h, outline);
        rect(g, w - EDGE, 0, EDGE, h, outline);
    }

    private void paintLowerBackground(SYo g) {
        double top = bigH > 0 ? bigY + bigH + eLY(3) : headerY + eLY(HEADER) + eLY(3);
        rect(g, 0, Math.round(top), Sxo(), yiX() - Math.round(top), themeGrey("Grey 1", 26, "Grey 2", 38, 9 / 12.0)); // 35 in the default theme
        double faderRight = faderX + eLY(FADER_WIDTH);
        if (!(stripGroupX >= panX - 1 && faderRight <= panX + panW + 1)) return; // the strip is not under the pan
        double m = eLY(4);
        double l = Math.round(Math.min(panX, stripGroupX) - m), r = Math.round(Math.max(panX + panW, faderRight) + m);
        double t = Math.round(panY - eLY(16) - m);
        rect(g, l, t, r - l, yiX() - EDGE - colourBarH() - eLY(2) - t, theme("Grey 1", iok.cfM(26, 26, 26)));
        // cut into three, as Bitwig's mixer: the pan | the readouts | the meters and the fader
        iok cut = themeGrey("Grey 1", 26, "Grey 2", 38, 9 / 12.0);
        double panBottom = panY + eLY(PAN_HEIGHT);
        double gapY = Math.round((panBottom + stripY) / 2 - 1.5);
        rect(g, l, gapY, r - l, 3, cut);
        rect(g, l, Math.round(stripY + eLY(READOUT) + eLY(1)), r - l, 1, cut);
    }

    /** The separator over the fader section: a line with a grip in the middle; drag it to size the section. */
    private void paintSeparator(SYo g, iok text) {
        if (separatorY < 0) return;
        double y = separatorY + eLY(SEPARATOR) / 2.0;
        rect(g, 0, y - 0.5, Sxo(), 1, theme("Grey 3", iok.cfM(50, 50, 50)));
        double gw = eLY(24), cx = Sxo() / 2;
        rect(g, cx - gw / 2, y - eLY(2) - 0.5, gw, 1, text.cfM(0.3F));
        rect(g, cx - gw / 2, y + eLY(2) - 0.5, gw, 1, text.cfM(0.3F));
    }

    /**
     * The fader and meters in Bitwig's mixer style: the scale's labels (0, 6, 12, 18, 24, 36, ∞)
     * with small marks, two thin meter strips, and the fader as a bar: the master's colour, darkened,
     * filled up to the level, with a line in its full colour on top; the level over it in that colour.
     */
    private void paintStrip(SYo g, Hpk font, iok text, double ascent) {
        double top = scaleTop, bottom = scaleBottom;
        if (bottom - top < eLY(20)) return;
        iok panel = theme("Grey 1", iok.cfM(26, 26, 26));
        iok well = theme("Grey 3", iok.cfM(47, 47, 47));
        iok colour = theme("Accent (default)", ACCENT); // Bitwig's orange, as its mixer's fader
        // readouts: the highest peak (left, over the meters) and the fader's level (right, in the master's colour)
        double readY = stripY + ascent;
        String level = MasterBoModel.dbText(model.volumeDb()) + " dB";
        g.cfM(faderX + eLY(FADER_WIDTH) - font.cfM(level).IwV(), readY, level, colour);
        if (bigMode == 0) { // the meters' peak readout (the big meters have the meters then)
            boolean clipped = model.clip[0] || model.clip[1];
            String peak = model.maxPeak > 0 ? MasterBoModel.dbText(MasterBoModel.dB(model.maxPeak)) : "-inf";
            g.cfM(meterX0 - eLY(SCALE_WIDTH) + eLY(2), readY, peak, clipped ? theme("Meter Clipping", RED) : text.cfM(0.6F));
        }

        // labels and marks: the scale always runs from +6 dB to -inf; a taller one has room for more
        // labels (lower ones first, then the ones between), each only where it does not crowd another
        double room = ascent + eLY(3);
        java.util.List<Double> placed = new java.util.ArrayList<>();
        placed.add(bottom - ascent / 2); // the -inf label
        java.util.List<Double> shown = new java.util.ArrayList<>();
        for (double mark : LABEL_ORDER) {
            double y = posY(MasterBoModel.dbToPosition(mark));
            if (y < scaleTop + ascent / 2 - 1) continue;
            boolean free = true;
            for (double other : placed) if (Math.abs(other - y) < room) { free = false; break; }
            if (!free) continue;
            placed.add(y);
            shown.add(mark);
            String label = String.valueOf((int) Math.round(Math.abs(mark)));
            double lw = font.cfM(label).IwV();
            g.cfM(meterX0 - eLY(10) - lw, y + ascent / 2 - 1, label, text.cfM(0.75F));
            rect(g, meterX0 - eLY(6), y - 0.5, eLY(3), 1, text.cfM(0.45F));
        }
        String inf = "\u221E";
        g.cfM(meterX0 - eLY(10) - font.cfM(inf).IwV(), bottom - 1, inf, text.cfM(0.75F));
        for (double dB : TICK_ORDER) { // small marks where there is no label, if they do not touch one
            if (shown.contains(dB)) continue;
            double y = posY(MasterBoModel.dbToPosition(dB));
            boolean free = true;
            for (double other : placed) if (Math.abs(other - y) < eLY(3)) { free = false; break; }
            if (free && y < bottom - eLY(2)) rect(g, meterX0 - eLY(5), y - 0.5, eLY(1), 1, text.cfM(0.3F));
        }

        // the meters (none while the big meters are shown)
        if (bigMode == 0) {
            paintMeter(g, text, well, meterX0, 0);
            paintMeter(g, text, well, meterX1, 1);
        }

        // the fader bar: darkened colour up to the level, then the line
        double fy = posY(model.volumePos);
        rect(g, faderX, fy, eLY(FADER_WIDTH), bottom - fy, colour.cfM(0.33F));
        rect(g, faderX, fy - eLY(FADER_LINE) / 2.0, eLY(FADER_WIDTH), eLY(FADER_LINE), colour);
    }

    private void paintMeter(SYo g, iok text, iok well, double x, int ch) {
        double w = eLY(METER_WIDTH), top = scaleTop, bottom = scaleBottom;
        rect(g, x, top, w, bottom - top, well);
        // the clip light over it
        rect(g, x, top - eLY(CLIP) - eLY(2), w, eLY(CLIP), model.clip[ch] ? theme("Meter Clipping", RED) : well);
        if (model.meterCount == 0) return;
        bar(g, x, w, MasterBoModel.position(model.peak[ch]), 1F); // one bar, as Bitwig's mixer: the peak
        double holdPos = MasterBoModel.position(model.hold[ch]);
        if (holdPos > 0.002) {
            double y = posY(holdPos);
            rect(g, x, y - 1, w, 2, holdColour(y, scaleBottom, posY(MasterBoModel.dbToPosition(0))));
        }
    }

    /** A level bar from the bottom up to a position, in Bitwig's mixer gradient (by the level at each height). */
    private void bar(SYo g, double x, double w, double position, float alpha) {
        if (position <= 0) return;
        double top = scaleTop, bottom = scaleBottom;
        gradientBar(g, x, w, posY(position), bottom, y -> MasterBoModel.positionToDb(1 - (y - top) / (bottom - top)));
    }

    /** A bar from yTop down to yBottom, each one-pixel row in the colour of the level at its height. */
    private void gradientBar(SYo g, double x, double w, double yTop, double yBottom, java.util.function.DoubleUnaryOperator dbAt) {
        for (double y = Math.floor(yTop); y < yBottom; y += 1) {
            double row = Math.max(y, yTop);
            double h = Math.min(y + 1, yBottom) - row;
            if (h <= 0) continue;
            rect(g, x, row, w, h, levelColour(dbAt.applyAsDouble(row + h / 2)));
        }
    }

    // Bitwig's mixer meter gradient, by level: dark green at the bottom, green, lime at -24 dB,
    // yellow at -9 dB, orange at -3 dB, red above 0 dB
    private static final double[] GRAD_DB = {-90, -48, -24, -9, -3, 0, 0.01};
    private static final int[][] GRAD_RGB = {{30, 110, 45}, {85, 165, 45}, {160, 205, 40}, {235, 215, 55},
                                             {245, 140, 25}, {245, 120, 20}, {235, 56, 41}};
    private static final iok[] GRAD_CACHE = new iok[1001];

    /** The meter colour at a level (cached in 0.1 dB steps from -90 to +10). */
    private static iok levelColour(double dB) {
        if (!(dB > -90)) dB = -90;
        if (dB > 10) dB = 10;
        int i = (int) Math.round((dB + 90) * 10);
        iok c = GRAD_CACHE[i];
        if (c != null) return c;
        double d = i / 10.0 - 90;
        int k = 0;
        while (k < GRAD_DB.length - 2 && d > GRAD_DB[k + 1]) k++;
        int[] a = GRAD_RGB[k], b = GRAD_RGB[Math.min(k + 1, GRAD_RGB.length - 1)];
        double t = d >= GRAD_DB[GRAD_DB.length - 1] ? 1 : Math.max(0, Math.min(1, (d - GRAD_DB[k]) / (GRAD_DB[k + 1] - GRAD_DB[k])));
        if (d > 0) { a = GRAD_RGB[GRAD_RGB.length - 1]; b = a; t = 0; }
        c = iok.cfM((int) Math.round(a[0] + (b[0] - a[0]) * t), (int) Math.round(a[1] + (b[1] - a[1]) * t),
                (int) Math.round(a[2] + (b[2] - a[2]) * t));
        GRAD_CACHE[i] = c;
        return c;
    }

    /** The hold line: Bitwig's orange, as its mixer's. */
    private iok holdColour(double y, double yBottom, double y0) {
        return theme("Accent (default)", ACCENT);
    }

    private iok meterColour(double dB) {
        return dB > 0 ? theme("Meter Clipping", RED) : dB > -9 ? theme("Meter Yellow", YELLOW) : theme("Meter Normal", GREEN);
    }

    /** One of the theme's colours by its name, as the theme defines them (Bitwig's own, so a custom theme follows). */
    /**
     * A grey between two of the theme's greys (t: 0 the first, 1 the second), so it follows the theme
     * as Bitwig's own greys do; av and bv are their values in the default theme, used if the theme lacks them.
     */
    private iok themeGrey(String a, int av, String b, int bv, double t) {
        return theme(a, iok.cfM(av, av, av)).cfM(theme(b, iok.cfM(bv, bv, bv)), (float) t); // cfM(iok, t): mixed
    }

    private iok theme(String name, iok fallback) {
        try {
            eeY colour = jl_().BAa().cfM(name); // jl_(): the theme; BAa(): its colours; cfM: by name
            return colour != null ? colour.cfM(this) : fallback;
        } catch (Throwable failure) {
            return fallback;
        }
    }

    // ---------- Drawing helpers ----------

    private static void rect(SYo g, double x, double y, double w, double h, iok colour) {
        if (w <= 0 || h <= 0) return;
        g.MRY(x, y, w, h);
        g.Hnt(colour);
        g.wGc(); // fill
    }

    private static void strokeRect(SYo g, double x, double y, double w, double h, iok colour) {
        g.MRY(x, y, w, h);
        g.Hnt(colour);
        g.IwV(1);
        g.hbP(); // stroke
    }

    private static void line(SYo g, double x0, double y0, double x1, double y1, iok colour) {
        line(g, x0, y0, x1, y1, colour, 1);
    }

    private static void line(SYo g, double x0, double y0, double x1, double y1, iok colour, double width) {
        g.MRY(x0, y0);
        g.cfM(x1, y1);
        g.Hnt(colour);
        g.IwV(width);
        g.hbP();
    }

    /** The text, cut with an ellipsis to fit a width. */
    private static String fit(Hpk font, String s, double width) {
        if (width <= 0) return "";
        if (font.cfM(s).IwV() <= width) return s;
        String dots = "…";
        int n = s.length();
        while (n > 0 && font.cfM(s.substring(0, n) + dots).IwV() > width) n--;
        return n == 0 ? "" : s.substring(0, n) + dots;
    }

    // ---------- Keyboard ----------

    /** Takes the keyboard (as a click on a text field does), so the next key press comes here first. */
    private void takeKeyboard() {
        try {
            ju_(); // ju_(): ask the window for the keyboard focus
            MasterBoModel.debug("keyboard: " + (hasKeyboard() ? "taken" : "not taken"));
        } catch (Throwable failure) {
            MasterBoModel.debug("keyboard failed: " + failure);
        }
    }

    /** Whether key presses come to bo Master first (it has the window's keyboard focus). */
    private boolean hasKeyboard() {
        try {
            dsg root = kN_(); // kN_(): the root of this widget's window
            return root != null && root.QSH() == this; // QSH(): the widget with the keyboard focus
        } catch (Throwable failure) {
            return false;
        }
    }

    /**
     * A key press while bo Master has the keyboard: Delete or Backspace (no modifier) deletes the
     * selected insert, as in Bitwig's device panel. Every other key goes on to Bitwig's shortcuts.
     */
    @Override
    public boolean MRY(eoR press) {
        try {
            if (model.selectedInsert != null && isDeleteKey(press)) {
                boolean deleted = model.deleteSelectedInsert();
                model.refresh();
                jbi();
                if (deleted) return true;
            }
        } catch (Throwable failure) {
            MasterBoPanels.MOD.log("Master Bo could not handle a key", failure);
        }
        return super.MRY(press);
    }

    private static boolean isDeleteKey(eoR press) {
        eoV mods = press.TtY(); // TtY(): the modifiers held
        if (mods != null && !mods.equals(eoV.Hnt)) return false; // eoV.Hnt: none
        return isDelete(press.IwV()) || isDelete(press.wGc()); // the key, as typed and as on the keyboard
    }

    private static boolean isDelete(eoO key) {
        // eoU.BAa: Backspace, eoU.Ai: Delete, eoU.bB: Numpad Delete
        return key != null && (key == eoU.BAa || key == eoU.Ai || key == eoU.bB);
    }

    // ---------- Mouse ----------

    private boolean in(double x, double y, double rx, double ry, double rw, double rh) {
        return x >= rx && x < rx + rw && y >= ry && y < ry + rh;
    }

    /**
     * Which gesture a press here would start. Bitwig asks this on a real press and also on every
     * mouse move (for the cursor, hover and the status bar's hints), so it changes nothing: the
     * gesture acts when a real press reaches it.
     */
    @Override
    public drX MRY(eoW button, double x, double y, eoV mods, int clicks) {
        if (button != eoW.cfM) return null; // eoW.cfM: the main button
        if (popupMacro >= 0) { // an open drop-down takes the click: a value chooses it, anywhere else closes it
            // (it stays open until the press is handled: Bitwig may ask again for the same press)
            int index = popupMacro;
            double h = popupNames.length * popupItemH + eLY(4);
            MasterBoModel.debug("popup click " + Math.round(x) + "," + Math.round(y) + " in " + Math.round(popupX) + "," + Math.round(popupY)
                                + " " + Math.round(popupW) + "x" + Math.round(h));
            if (index < model.macros.size() && in(x, y, popupX, popupY, popupW, h)) {
                int item = (int) Math.floor((y - popupY - eLY(2)) / popupItemH);
                MasterBoModel.Macro m = model.macros.get(index);
                int count = popupNames.length;
                MasterBoModel.debug("popup item " + item + " of " + count);
                if (item >= 0 && item < count) return new Act(() -> { popupMacro = -1; popupHover = -1; model.chooseMacro(m, item, count); });
            }
            return new Act(() -> { popupMacro = -1; popupHover = -1; jbi(); });
        }
        try {
            if (!model.hasMaster()) return null;
            layout();
            boolean ctrl = mods.equals(eoV.lYc); // Cmd on macOS, Ctrl elsewhere, alone
            boolean alt = mods.equals(eoV.hUR);  // Alt alone
            // the separator: drag it to size the fader section; double-click for the default
            if (separatorY >= 0 && y >= separatorY - eLY(2) && y < separatorY + eLY(SEPARATOR) + eLY(2)) {
                if (clicks >= 1) return new Act(() -> keepFaderHeight(0));
                return new SeparatorDrag(y);
            }
            // the VU: PRE/POST switches the source, the reference's value the reference, anywhere else folds it;
            // folded, a click opens it
            if (gonioH > 0 && in(x, y, specX, specY + specH - gonioH, specW, gonioH)) { // the goniometer: move, fold, open
                if (gonioMoveHit != null && in(x, y, gonioMoveHit[0], gonioMoveHit[1], gonioMoveHit[2], gonioMoveHit[3])) return new Act(this::moveSpectrum);
                if (gonioCollapsed) return new Act(() -> setGonioCollapsed(false));
                if (y < specY + specH - eLY(SPEC_STRIP)) return new Act(() -> setGonioCollapsed(true)); // the plot: fold it
                return null;
            }
            if (specOwnH > 0 && in(x, y, specX, specY, specW, specOwnH)) { // the spectrum: its strip chooses the slope and moves it
                if (specMoveHit != null && in(x, y, specMoveHit[0], specMoveHit[1], specMoveHit[2], specMoveHit[3])) return new Act(this::moveSpectrum);
                if (specCollapsed) return new Act(() -> setSpecCollapsed(false));
                for (int i = 0; i < slopeHits.size(); i++) {
                    double[] r = slopeHits.get(i);
                    if (in(x, y, r[0], r[1], r[2], r[3]))
                        return new Act(r[4] == 0 ? model::nextSlope : r[4] == 1 ? model::toggleMs : r[4] == 2 ? model::toggleThirdOctave
                                       : r[4] == 3 ? model::toggleHold : model::nextHoldTime);
                }
                for (double[] r : specScaleHits) // the dB or frequency scale: reset the levels and holds
                    if (r != null && in(x, y, r[0], r[1], r[2], r[3])) return new Act(model::resetSpectrum);
                if (y < specY + specOwnH - eLY(SPEC_STRIP)) return new Act(() -> setSpecCollapsed(true)); // the graph: fold it
                return null;
            }
            if (in(x, y, vuX, vuY, vuW, vuH)) {
                for (double[] r : vuMoveHits) if (in(x, y, r[0], r[1], r[2], r[3])) { int d = (int) r[4]; return new Act(() -> moveVu(d)); }
                if (vuCollapsed) return new Act(() -> setCollapsed(false));
                for (double[] r : sourceHits) if (in(x, y, r[0], r[1], r[2], r[3])) return new Act(model::toggleSource);
                for (double[] r : referenceHits) if (in(x, y, r[0], r[1], r[2], r[3])) return new Act(model::nextReference);
                for (double[] r : vuLabelHits) if (in(x, y, r[0], r[1], r[2], r[3])) return new Act(this::nextScheme);
                return new Act(() -> setCollapsed(true));
            }
            // BO LUFS: the triangle moves the section; anywhere else resets the meter
            if (lufsH > 0 && in(x, y, lufsX, lufsY, lufsW, lufsH)) {
                if (x >= lufsX + lufsW - eLY(LUFS_ICON) - eLY(2)) return new Act(this::moveLufs);
                return new Act(model::resetLufs);
            }
            // header
            if (in(x, y, bigIconX, bigIconY, bigIconW, bigIconH)) return new Act(this::toggleBigMeters); // Show Big Meters
            if (in(x, y, macroIconX, bigIconY, bigIconW, bigIconH)) return new Act(this::toggleMacros);  // the remote controls
            if (macrosH > 0) { // the remote controls: the triangle moves them; a knob turns (double-click: its default)
                if (macrosMoveHit != null && in(x, y, macrosMoveHit[0], macrosMoveHit[1], macrosMoveHit[2], macrosMoveHit[3])) return new Act(this::moveMacros);
                for (double[] r : macroHits) {
                    if (!in(x, y, r[0], r[1], r[2], r[3])) continue;
                    int i = (int) r[4];
                    if (i >= model.macros.size()) return null;
                    MasterBoModel.Macro m = model.macros.get(i);
                    if (m.target == null) return null;
                    if (m.kind == 2) { // a choice: its drop-down
                        double bx = r[0] + eLY(2), bh = eLY(20), by = r[1] + (r[3] - bh) / 2;
                        return new Act(() -> openPopup(i, bx, by, eLY(MACRO_CTL), bh));
                    }
                    if (m.kind == 1) return new Act(() -> model.stepMacro(m)); // a button flips
                    if (clicks >= 1 || alt) return new Act(() -> model.resetMacro(m));
                    return new MacroDrag(m, y);
                }
                if (in(x, y, macrosX, macrosY, macrosW, macrosH)) return null;
            }
            if (in(x, y, headerX, headerY, headerW, eLY(HEADER))) return new Act(model::selectMaster);
            if (bigH > 0 && in(x, y, bigX, bigY, bigW, bigH)) return new Act(model::resetPeaks); // the big meters: reset the peaks
            // "+" in the row under the inserts: the pop-up browser, to insert a device at the end of the chain
            if (latencyY >= insertsY && in(x, y, insertsX, latencyY, eLY(22), eLY(ROW))) return new Act(this::openInsertBrowser);
            // inserts
            if (in(x, y, insertsX, insertsY, insertsW, insertsH)) {
                int index = (int) Math.floor((y - insertsY) / eLY(ROW));
                List<MasterBoModel.Insert> inserts = model.inserts;
                if (index < 0 || index >= inserts.size()) return new Act(model::selectMaster);
                MasterBoModel.Insert insert = inserts.get(index);
                if (x < insertsX + eLY(7) + eLY(7) + eLY(4)) return new Act(() -> model.toggle(insert)); // the on/off dot
                if (insert.plugin && x >= insertsX + insertsW - eLY(WINDOW_BUTTON)) return new Act(() -> model.toggleWindow(insert)); // its window
                if (clicks >= 1) return new Act(() -> model.focusInsert(insert)); // a double-click: show it in the device panel
                return new InsertDrag(index, y); // a click selects it; a drag moves the insert
            }
            // the master's solo and mute
            for (double[] r : soloMuteHits)
                if (in(x, y, r[0], r[1], r[2], r[3])) { int i = (int) r[4]; return new Act(() -> model.toggleSoloMute(i)); }
            // pan
            if (in(x, y, panX, panY - eLY(4), panW, eLY(PAN_HEIGHT) + eLY(8))) {
                if (clicks >= 1 || alt) return new Act(model::resetPan);
                return new Drag(false, x, y);
            }
            // readouts and clip lights: reset the peaks
            double readTop = stripY, readBottom = scaleTop;
            if (bigMode == 0 && y >= readTop && y < readBottom && x >= meterX0 - eLY(SCALE_WIDTH) && x < meterX1 + eLY(METER_WIDTH) + eLY(2)) {
                return new Act(model::resetPeaks); // the peak readout and the clip lights
            }
            // fader (its column, the scale's height and a little more)
            if (x >= faderX - eLY(2) && x < faderX + eLY(FADER_WIDTH) + eLY(4) && y >= readTop && y < scaleBottom + eLY(6)) {
                if (clicks >= 1 || ctrl) return new Act(model::resetVolume);
                return new Drag(true, x, y);
            }
            return null;
        } catch (Throwable failure) {
            MasterBoPanels.MOD.log("Master Bo could not handle a click", failure);
            return null;
        }
    }

    /** Bitwig's pop-up browser, inserting at the end of the master's chain (as the chain's own "+" does). */
    private void openInsertBrowser() {
        bitwig.obf.FAu chain = model.chain();
        if (chain == null) return;
        aXu.MRY(this, chain); // MRY(widget, chain): insert after the chain's last device; cfM(...) would insert first
    }

    /** A click that does one thing on the real press. */
    private final class Act extends drX {
        private final Runnable action;
        private boolean pressed;

        Act(Runnable action) {
            this.action = action;
        }

        @Override
        public void cfM(dJH press) {
            if (pressed) return;
            pressed = true;
            try {
                model.selectedInsert = null; // a click on anything else in bo Master ends the insert's selection
                action.run();
                model.refresh();
                jbi();
            } catch (Throwable failure) {
                MasterBoPanels.MOD.log("Master Bo could not handle a click", failure);
                MasterBoModel.debug("click failed: " + failure);
            }
        }
    }

    /**
     * Dragging an insert to another place in the master's chain: a bright line shows the gap it
     * will land in; the release moves it (one undo step, as Bitwig's own drag and drop). Without a
     * drag, the press is a click: it selects the master.
     */
    private final class InsertDrag extends drX {
        private final int from;
        private final double pressY;
        private boolean dragging;

        InsertDrag(int from, double y) {
            this.from = from;
            this.pressY = y;
        }

        @Override
        public void cfM(dJM move) {
            double y = move.Grk();
            if (!dragging && Math.abs(y - pressY) < eLY(4)) return;
            dragging = true;
            int count = model.inserts.size();
            int gap = (int) Math.round((y - insertsY) / eLY(ROW));
            dragFrom = from;
            dragGap = Math.max(0, Math.min(count, gap));
            jbi();
        }

        @Override
        public void cfM(dJI release) {
            try {
                if (!dragging) {
                    if (from < model.inserts.size()) { // a click: select it and take the keyboard, so Delete / Backspace removes it
                        model.selectInsert(model.inserts.get(from));
                        takeKeyboard();
                    }
                } else if (dragGap >= 0 && dragGap != from && dragGap != from + 1 && from < model.inserts.size()) {
                    model.moveInsert(model.inserts.get(from), dragGap);
                }
                model.refresh();
            } catch (Throwable failure) {
                MasterBoPanels.MOD.log("Master Bo could not move an insert", failure);
            }
            end();
            super.cfM(release);
        }

        @Override
        public void FzB() {
            end();
            super.FzB();
        }

        private void end() {
            dragFrom = -1;
            dragGap = -1;
            jbi();
        }

        @Override
        public boolean jC_() {
            return true;
        }
    }

    /** Dragging the separator: the fader section grows as it moves up; kept on release. */
    private final class SeparatorDrag extends drX {
        private final double pressY;
        private double startHeight; // the section's height at the press, in pixels
        private boolean pressed;

        SeparatorDrag(double y) {
            this.pressY = y;
        }

        @Override
        public void cfM(dJH press) {
            if (pressed) return;
            pressed = true;
            startHeight = yiX() - EDGE - colourBarH() - eLY(BOTTOM_GAP) - separatorY - eLY(SEPARATOR); // the fader section, pan included
        }

        @Override
        public void cfM(dJM move) {
            double height = Math.max(eLY(FADER_MIN), startHeight - (move.Grk() - pressY)); // layout() keeps room for the inserts
            faderHeight = (int) Math.round(height / unit()); // shown at once; kept on release
            jbi();
        }

        @Override
        public void cfM(dJI release) {
            keepFaderHeight(faderHeight);
            super.cfM(release);
        }

        @Override
        public boolean jC_() {
            return true;
        }
    }

    /**
     * Dragging the fader (up/down) or the pan (sideways): one adjusting gesture from press to
     * release, as a controller's touch, set, release (one undo step). The value follows the
     * pointer's movement from where it was, so a press doesn't make it jump; with Shift held a
     * move counts a tenth.
     */
    private final class Drag extends drX {
        private final boolean volume;
        private double lastX, lastY;
        private double value; // a position on the scale (volume) or the pan 0..1
        private boolean pressed;
        private boolean adjusting;

        Drag(boolean volume, double x, double y) {
            this.volume = volume;
            this.lastX = x;
            this.lastY = y;
        }

        @Override
        public void cfM(dJH press) {
            if (pressed) return;
            pressed = true;
            try {
                value = volume ? model.volumePos : model.panNorm;
                model.startAdjusting(volume);
                adjusting = true;
            } catch (Throwable failure) {
                MasterBoPanels.MOD.log("Master Bo could not start adjusting", failure);
            }
        }

        @Override
        public void cfM(dJM move) {
            if (!adjusting) return;
            try {
                double x = move.hbP(), y = move.Grk(); // the pointer, in the widget
                double factor = move.E3().cfM() ? 0.1 : 1; // E3(): the held modifiers; cfM(): Shift among them
                if (volume) {
                    value = MasterBoModel.clamp(value - (y - lastY) / Math.max(1, scaleBottom - scaleTop) * factor);
                    model.setVolumePosition(value);
                } else {
                    value = MasterBoModel.clamp(value + (x - lastX) / Math.max(1, panW) * factor);
                    model.setPan(value);
                }
                lastX = x;
                lastY = y;
                jbi();
            } catch (Throwable failure) {
                MasterBoPanels.MOD.log("Master Bo could not adjust", failure);
            }
        }

        /** The release: ends the adjusting gesture, then Bitwig's own release ends the mouse gesture. */
        @Override
        public void cfM(dJI release) {
            finish();
            super.cfM(release);
        }

        /** A cancel (Escape, or Bitwig ending the gesture): the adjusting gesture ends too. */
        @Override
        public void FzB() {
            finish();
            super.FzB();
        }

        private void finish() {
            if (!adjusting) return;
            adjusting = false;
            model.finishAdjusting(volume);
        }

        /** Asks for the mouse moves while the gesture is held. */
        @Override
        public boolean jC_() {
            return true;
        }
    }
}
