//
// $Id$

package coreen.ui;

import com.google.gwt.core.client.GWT;
import com.google.gwt.event.dom.client.ClickHandler;
import com.google.gwt.event.dom.client.HasClickHandlers;
import com.google.gwt.event.shared.HandlerRegistration;
import com.google.gwt.user.client.Window;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.user.client.ui.PopupPanel;
import com.google.gwt.user.client.ui.Widget;

import com.threerings.gwt.ui.Popups;
import com.threerings.gwt.ui.Widgets;

/**
 * Various UI related utilities.
 */
public class UIUtil
{
    /**
     * Shows the supplied popup above the specified target widget.
     */
    public static void showAbove (PopupPanel popup, Widget target)
    {
        popup.setVisible(false);
        popup.show();
        int left = Math.max(0, target.getAbsoluteLeft() + target.getOffsetWidth()/2 -
                            popup.getOffsetWidth()/2);
        int top = Math.max(target.getAbsoluteTop() - popup.getOffsetHeight(), 0);
        if (left + popup.getOffsetWidth() > Window.getClientWidth()) {
            left = Math.max(0, Window.getClientWidth() - popup.getOffsetWidth());
        }
        popup.setPopupPosition(left, top);
        popup.setVisible(true);
    }

    /**
     * Sets the browser title.
     *
     * @param parts a list of parts that will be combined with dashes to form the title.
     */
    public static void setWindowTitle (String... parts)
    {
        StringBuilder buf = new StringBuilder();
        for (String part : parts) {
            if (buf.length() > 0) {
                buf.append(" - ");
            }
            buf.append(part);
        }
        Window.setTitle(buf.toString());
    }

    /**
     * Creates a widget that with CSS style {@code clear: both}.
     */
    public static Widget newClear ()
    {
        return Widgets.newLabel(" ", _rsrc.styles().clear());
    }

    /**
     * Adds a click handler and marks the supplied widget with the `actionable` style to let the
     * user know it can be clicked or hovered.
     */
    public static <T extends Widget & HasClickHandlers> HandlerRegistration makeActionable (
        T widget, ClickHandler handler)
    {
        makeActionable(widget);
        return widget.addClickHandler(handler);
    }

    /**
     * Marks the supplied widget with the `actionable` style to let the user know it can be clicked
     * or hovered.
     */
    public static void makeActionable (Widget widget)
    {
        widget.addStyleName(_rsrc.styles().actionable());
    }

    /**
     * Adds a float left style to the supplied target widget, then returns it.
     */
    public static <T extends Widget> T makeFloatLeft (T target)
    {
        target.addStyleName(_rsrc.styles().floatLeft());
        return target;
    }

    /**
     * Adds a float right style to the supplied target widget, then returns it.
     */
    public static <T extends Widget> T makeFloatRight (T target)
    {
        target.addStyleName(_rsrc.styles().floatRight());
        return target;
    }

    /**
     * Creates an icon that can be used to drag the supplied target popup.
     */
    public static Widget makeDragger (PopupPanel target)
    {
        Label dragger = Widgets.newLabel("▤", _rsrc.styles().noSelect());
        dragger.setTitle("Click and drag to move popup.");
        Popups.makeDraggable(dragger, target);
        return dragger;
    }

    protected static final UIResources _rsrc = GWT.create(UIResources.class);
}
