package org.csstudio.opibuilder.widgets.model;

import org.csstudio.opibuilder.model.AbstractPVWidgetModel;
import org.csstudio.opibuilder.properties.BooleanProperty;
//import org.csstudio.opibuilder.properties.StringProperty;
import org.csstudio.opibuilder.properties.WidgetPropertyCategory;
//import org.csstudio.opibuilder.visualparts.BorderStyle;
import org.eclipse.swt.graphics.RGB;

/**
 * The model for the video feed widget.
 *
 * @author Sven Thoennissen - Space Applications Services
 */
public class VideoFeedModel extends AbstractPVWidgetModel {

    public final String ID = "org.csstudio.opibuilder.widgets.videofeed";

	public static final String PROP_SHOW_DETAILS = "video.details";

	/**
	 * Constructor
	 */
    public VideoFeedModel() {
		setBackgroundColor(new RGB(0, 0, 0));
		setForegroundColor(new RGB(255, 255, 255));
    }

	/**
	 * Set up some properties.
	 */
    @Override
    protected void configureProperties() {
		addProperty(new BooleanProperty(PROP_SHOW_DETAILS, "Show Details", WidgetPropertyCategory.Display, false));

		removeProperty(PROP_SCALE_OPTIONS);
		removeProperty(PROP_TOOLTIP);
		removeProperty(PROP_FONT);
		removeProperty(PROP_BACKCOLOR_ALARMSENSITIVE);
		removeProperty(PROP_BORDER_ALARMSENSITIVE);
		removeProperty(PROP_FORECOLOR_ALARMSENSITIVE);
		removeProperty(PROP_ALARM_PULSING);
    }

	public boolean getDetailsVisible() {
		return (Boolean)getPropertyValue(PROP_SHOW_DETAILS);
	}

	/**
	 * Return type identifier.
	 */
    @Override
    public String getTypeID() {
        return ID;
    }

	/**
	 * We don't want the tooltip to appear for this widget.
	 */
	@Override
	public String getTooltip() {
		return "";
	}
}
