package bml.panels;

import java.util.function.Function;

/** A declared view. */
public interface View {
    /**
     * Its icon (required): a resource ID from the mod's manifest, either one of Bitwig's icons
     * ({@code "kind": "bitwig"}, a path under {@code icons/}, checked to exist) or the mod's own SVG
     * ({@code "kind": "file"}). Bitwig tints it with the theme: only its shape counts. A view whose
     * icon can't be used is logged and left out.
     */
    View icon(String resourceId);

    /**
     * Its content (required): called each time Bitwig builds a window's panel for the view, once
     * for every place the view is in, returning one of Bitwig's widgets ({@code dve}). The framework
     * puts it in Bitwig's scaled container (Panel Scale) inside Bitwig's panel widget.
     */
    View content(Function<ViewContext, Object> content);

    /**
     * A saved text value of the view's own, declared as Bitwig's panels declare theirs: kept with
     * the project's view settings (per display profile), never in the project. One value per
     * profile, shared by every place the view is in; its full key is
     * {@link ViewContext#savedKey}{@code (key)}.
     */
    View savedText(String key, String defaultValue);

    /**
     * One of Bitwig's panels the view can take the place of: {@code "mixer"}. In a mode whose main
     * content is that panel (MIX's mixer), the main content becomes a choice between the panel and
     * the view (both buttons in the bottom bar's row for that mode, one shown at a time, the panel
     * by default); there the view is not also a tab of the lower panel. Other modes keep it as a
     * lower-panel tab. Without a view stack only (in a stack every view has its own place).
     */
    View replaces(String bitwigPanel);

    /**
     * Where the view goes instead of the main area: {@code "browser"} makes it one more tab of the
     * browser side (the tab group of the Browser, Project, Studio I/O and Mappings panels), after
     * Bitwig's own tabs, with its button after theirs in the bottom bar; it is then neither a tab of
     * the modes' lower panels nor a view of a stack. A display profile without that tab group (or
     * whose browser side a layout mod turned into columns) doesn't show the view; the log says so.
     */
    View side(String place);

    /**
     * With {@link #side}: the view's tab comes first in its side's tab group, before Bitwig's own
     * tabs (its button first in the bottom bar's group); default false (after them). The side's
     * default tab stays Bitwig's own (the Browser). Several first views keep their order.
     */
    View sideFirst(boolean first);

    /**
     * With {@link #side}: asked each time Bitwig builds a display profile (at start-up); true makes
     * the view a column of its own between the main area and the browser side's tab group, instead
     * of one of its tabs. The column keeps its own width (saved with the project, by the view), is
     * shown by default and stays when the browser side is closed; its button, in the bottom bar's
     * right group just before the keyboard's, shows and hides it. Null or false: a tab (the default).
     */
    View sideColumn(java.util.function.BooleanSupplier column);

    /** Whether it has a scale: Panel Scale setting and right-click scale choices (default true). */
    View scale(boolean scalable);

    /**
     * Whether the framework lists its scale in Settings > User Interface > Panel Scale (default
     * true). Pass false to place the setting where the mod wants it, with {@link PanelScale}.
     */
    View scaleSetting(boolean listed);
}
