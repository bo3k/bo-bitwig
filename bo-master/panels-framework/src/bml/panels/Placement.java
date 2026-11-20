package bml.panels;

import java.util.ArrayList;
import java.util.List;

import bitwig.obf.aBW; // a window's project view
import bitwig.obf.ajF; // a tab group (several panels, one shown)
import bitwig.obf.akC; // a display profile: its windows and their layouts
import bitwig.obf.akQ; // a panel
import bitwig.obf.akW; // the Browser panel
import bitwig.obf.akZ; // a splitter between two parts of a window's layout
import bitwig.obf.akj; // the mixer panel
import bitwig.obf.akn; // a mode (ARRANGE, MIX, EDIT)
import bitwig.obf.akq; // a main area: one layout per mode
import bitwig.obf.akw; // a part of a window's layout
import bitwig.obf.akz; // one mode's layout in a main area
import bitwig.obf.ala; // a splitter between two parts of a layout

/**
 * Puts the registered views into a display profile when Bitwig builds it. Beside Bitwig's own
 * modes, each view becomes one more tab of every tab group inside a mode's layout (Bitwig's lower
 * panel of the mode: in Single Large Monitor the "complementary panel" with Detail Editor, Devices and
 * Mixer), after Bitwig's own tabs, in the views' order. A mode without such a tab group doesn't
 * get the views (logged once per profile).
 *
 * A view that replaces the mixer (View.replaces) is, in a mode whose main content is the mixer
 * (MIX), a choice with it instead (MainChoice: the mixer and the view take turns as the main
 * content), and not also a tab of that mode's lower panel.
 */
final class Placement {
    private Placement() {
    }

    /** The profile's views placement; returns how many tabs were added. */
    static int place(akC profile) {
        List<Registry.Declared> views = new ArrayList<>();
        for (Registry.Declared view : Registry.views()) if (view.side == null) views.add(view); // side views: placeSide
        if (profile == null || views.isEmpty()) return 0;
        int added = 0;
        int modesWithout = 0;
        for (int w = 0; w < profile.uWE(); w++) { // its windows
            for (akq main : mainAreas(profile.cfM(w).TMF())) {
                for (int c = 0; c < main.wGc(); c++) { // its modes' layouts
                    akz mode = main.cfM(c);
                    MainChoice choice = mainChoice(mode, views);
                    if (choice != null) added += choice.lYc().size() - 1;
                    List<ajF> groups = new ArrayList<>();
                    if (mode.TMF() != null) tabGroups(mode.TMF(), groups);
                    groups.remove(choice);
                    if (groups.isEmpty() && choice == null) modesWithout++;
                    for (ajF group : groups) {
                        for (Registry.Declared view : views) {
                            if (choice != null && view.replaces != null) continue; // this mode has it as the main content's choice
                            try {
                                ViewNode node = new ViewNode();
                                node.init(view, false);
                                group.cfM(node); // one more tab, after the group's own (ajL.cfM(akQ), as Bitwig adds its tabs)
                                added++;
                            } catch (Throwable failure) {
                                Registry.LOG.log("view " + view.who() + " could not be placed in " + group.tSS(), failure);
                            }
                        }
                    }
                }
            }
        }
        if (added == 0) {
            Registry.LOG.log("display profile " + profile.MIO() + " has no tab group in a mode's layout; the views aren't shown in it");
        } else if (modesWithout > 0) {
            Registry.LOG.log("display profile " + profile.MIO() + ": " + modesWithout
                    + " mode layout(s) have no tab group; the views aren't shown there");
        }
        return added;
    }

    /**
     * In a mode whose main content is the mixer, and with views that replace the mixer: the
     * mixer's place becomes a choice between the mixer and those views (the mixer first and by
     * default). Null when the mode's main content isn't the mixer or no view replaces it.
     */
    private static MainChoice mainChoice(akz mode, List<Registry.Declared> views) {
        List<Registry.Declared> replacing = new ArrayList<>();
        for (Registry.Declared view : views) if ("mixer".equals(view.replaces)) replacing.add(view);
        if (replacing.isEmpty() || mode.TMF() == null) return null;
        akQ mixer = mainPanel(mode.TMF(), akj.class);
        if (mixer == null || !(mixer.uva() instanceof akZ parent)) return null; // uva(): its parent part
        try {
            boolean first = parent.E3() == mixer;
            MainChoice choice = MainChoice.create(mixer.tSS() + "_choice");
            if (first) parent.MRY(akq.cfM("panel-stand-in")); // takes the mixer out (parts are moved through a stand-in)
            else parent.IwV(akq.cfM("panel-stand-in"));
            choice.cfM(mixer); // the mixer: the first choice
            for (Registry.Declared view : replacing) {
                ViewNode node = new ViewNode();
                node.init(view, false);
                choice.cfM(node);
            }
            choice.MRY(0); // the mixer by default
            if (first) parent.MRY(choice);
            else parent.IwV(choice);
            return choice;
        } catch (Throwable failure) {
            Registry.LOG.log("could not make the mixer's place a choice in a mode's layout", failure);
            return null;
        }
    }

    /** A panel of that class in a mode's layout, outside its tab groups (its main content), or null. */
    private static akQ mainPanel(akw node, Class<?> panelClass) {
        if (node instanceof ajF || node instanceof akq) return null; // a tab group's tabs aren't main content
        if (panelClass.isInstance(node)) return (akQ) node;
        if (node instanceof ala split) {
            akQ found = split.E3() != null ? mainPanel(split.E3(), panelClass) : null;
            return found != null ? found : split.wGc() != null ? mainPanel(split.wGc(), panelClass) : null;
        }
        return null;
    }

    /**
     * The views with a side (View.side("browser")): one more tab of each window's browser side, the
     * tab group of the Browser, Project, Studio I/O and Mappings panels, after Bitwig's own tabs (or
 * before them with View.sideFirst, keeping the group's default tab).
     * Bitwig builds the group's bottom-bar buttons from its tabs (each tab's ViewNode makes its own),
     * so the view's button sits after Mappings. Returns how many tabs were added.
     */
    static int placeSide(akC profile) {
        List<Registry.Declared> views = new ArrayList<>();
        for (Registry.Declared view : Registry.views()) if ("browser".equals(view.side)) views.add(view);
        if (profile == null || views.isEmpty()) return 0;
        int added = 0;
        for (int w = 0; w < profile.uWE(); w++) { // its windows
            List<ajF> groups = new ArrayList<>();
            browserSides(profile.cfM(w).TMF(), groups);
            for (ajF group : groups) {
                int front = 0; // first views go in at the front, in their order
                for (Registry.Declared view : views) {
                    if (asColumn(view)) {
                        if (placeColumn(group, view)) added++;
                        continue;
                    }
                    try {
                        ViewNode node = new ViewNode();
                        node.init(view, false);
                        if (view.sideFirst) {
                            int defaultTab = group.ejd();   // ejd(): the group's default tab (its index; Bitwig's Browser)
                            group.MRY(node, front++);       // MRY(panel, index): a tab at that place
                            if (defaultTab >= front - 1) group.Hnt(defaultTab + 1); // Hnt(index): the default stays the same tab
                        } else {
                            group.cfM(node);                // one more tab, after the group's own
                        }
                        added++;
                    } catch (Throwable failure) {
                        Registry.LOG.log("view " + view.who() + " could not be placed on the browser side " + group.tSS(), failure);
                    }
                }
            }
        }
        if (added == 0) Registry.LOG.log("display profile " + profile.MIO() + " has no browser side tab group; its side views aren't shown in it");
        return added;
    }

    /** Whether the side view goes in a column of its own this time (View.sideColumn). */
    private static boolean asColumn(Registry.Declared view) {
        try {
            return view.sideColumn != null && view.sideColumn.getAsBoolean();
        } catch (Throwable failure) {
            Registry.LOG.log("view " + view.who() + ": its sideColumn choice failed; a tab", failure);
            return false;
        }
    }

    private static final double SIDE_COLUMN_WIDTH = 260;

    /**
     * The view as a column of its own beside the browser side's tab group, on its main-area side
     * (to its left when the browser side is on the right): the splitter's other part (the main
     * area) becomes a split of that part and the view, the view keeping its own width. The browser
     * side stays the splitter's part, so it keeps its width, its buttons and its showing and hiding;
     * the view's width comes from the main area. The split is named by the view's node and its
     * parts keep the paths they would have directly under the splitter (ColumnSplit), so the main
     * area keeps its saved values.
     */
    private static boolean placeColumn(ajF group, Registry.Declared view) {
        if (!(group.uva() instanceof akZ outer)) { // uva(): its parent part
            Registry.LOG.log("view " + view.who() + ": the browser side " + group.tSS() + " isn't in a left/right splitter; no column");
            return false;
        }
        try {
            boolean groupSecond = outer.wGc() == group;     // the browser side on the right
            akw inner = groupSecond ? outer.E3() : outer.wGc(); // the main-area side
            ViewNode node = new ViewNode();
            node.initColumn(view); // its own "is shown" and button (next to the keyboard's)
            if (groupSecond) outer.MRY((akw) akq.cfM("panel-stand-in")); // takes the part out (a part has one parent)
            else outer.IwV((akw) akq.cfM("panel-stand-in"));
            ColumnSplit split = ColumnSplit.create(outer, node);
            if (groupSecond) {
                split.MRY(inner);                      // left: the main area
                split.IwV((akw) node);                 // right: the view, next to the browser side
                split.cfM(bitwig.obf.dVx.MRY);         // the second part (the view) keeps its width
            } else {
                split.MRY((akw) node);                 // left: the view, next to the browser side
                split.IwV(inner);                      // right: the main area
                split.cfM(bitwig.obf.dVx.cfM);         // the first part (the view) keeps its width
            }
            split.cfM(SIDE_COLUMN_WIDTH);
            if (groupSecond) outer.MRY(split);
            else outer.IwV(split);
            COLUMNS.add(split);
            return true;
        } catch (Throwable failure) {
            Registry.LOG.log("view " + view.who() + " could not be placed beside the browser side " + group.tSS(), failure);
            return false;
        }
    }

    /** The splits of side-column views (no strip for a hidden part, as side columns). */
    static final java.util.Set<Object> COLUMNS = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    /**
     * The browser sides of a window's layout: tab groups outside its main areas that hold the
     * Browser panel (akW). Not inside a main area: those are the modes' own tab groups.
     */
    private static void browserSides(akw node, List<ajF> found) {
        if (node == null || node instanceof akq) return; // a main area: the modes' layouts
        if (node instanceof ajF group) {
            for (Object tab : group.lYc()) { // its panels
                if (tab instanceof akW) {
                    found.add(group);
                    return;
                }
            }
            return;
        }
        if (node instanceof ala split) {
            browserSides(split.E3(), found);
            browserSides(split.wGc(), found);
        }
    }

    /** The main areas (mode switches) of a window's layout. */
    private static List<akq> mainAreas(akw root) {
        List<akq> found = new ArrayList<>();
        collectMain(root, found);
        return found;
    }

    private static void collectMain(akw node, List<akq> found) {
        if (node instanceof akq main) {
            found.add(main);
            return;
        }
        if (node instanceof ala split) {
            if (split.E3() != null) collectMain(split.E3(), found);
            if (split.wGc() != null) collectMain(split.wGc(), found);
        }
    }

    /** The tab groups in a mode's layout (not inside a nested main area). */
    private static void tabGroups(akw node, List<ajF> found) {
        if (node instanceof ajF group) {
            found.add(group);
            return;
        }
        if (node instanceof ala split) {
            if (split.E3() != null) tabGroups(split.E3(), found);
            if (split.wGc() != null) tabGroups(split.wGc(), found);
        }
    }

    /** The node of a view in a window's current mode (or on its browser side), or null if the view isn't there. */
    static ViewNode nodeIn(aBW window, Registry.Declared view) {
        akC profile = window.puu();             // the window's display profile
        akn mode = window.RDJ().vpw();          // the current mode (from the window's view settings)
        akw root = profile.cfM(window.sc()).TMF(); // this window's layout
        if (view.side != null) {
            List<ajF> groups = new ArrayList<>();
            browserSides(root, groups);
            for (ajF group : groups) {
                ViewNode node = find(group, view);
                if (node != null) return node;
            }
            return find(root, view); // a side column (View.sideColumn)
        }
        for (akq main : mainAreas(root)) {
            for (int c = 0; c < main.wGc(); c++) {
                akz layout = main.cfM(c);
                if (!layout.cfM(mode) || layout.TMF() == null) continue;
                ViewNode node = find(layout.TMF(), view);
                if (node != null) return node;
            }
        }
        return null;
    }

    private static ViewNode find(akw node, Registry.Declared view) {
        if (node instanceof ViewNode v) return v.view() == view ? v : null; // a side column (View.sideColumn)
        if (node instanceof ajF group) {
            for (Object tab : group.lYc()) {
                if (tab instanceof ViewNode v && v.view() == view) return v;
            }
            return null;
        }
        if (node instanceof ala split) {
            ViewNode first = split.E3() != null ? find(split.E3(), view) : null;
            return first != null ? first : split.wGc() != null ? find(split.wGc(), view) : null;
        }
        return null;
    }
}
