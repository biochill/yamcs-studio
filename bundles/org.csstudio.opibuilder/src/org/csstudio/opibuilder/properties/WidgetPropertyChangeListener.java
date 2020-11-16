/*******************************************************************************
 * Copyright (c) 2010 Oak Ridge National Laboratory.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 ******************************************************************************/
package org.csstudio.opibuilder.properties;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.util.ArrayList;
import java.util.List;

import org.csstudio.opibuilder.datadefinition.WidgetIgnorableUITask;
import org.csstudio.opibuilder.editparts.AbstractBaseEditPart;
import org.csstudio.opibuilder.editparts.ExecutionMode;
import org.csstudio.opibuilder.util.GUIRefreshThread;
import org.eclipse.draw2d.IFigure;
import org.eclipse.swt.widgets.Display;

/**
 * The listener on widget property change.
 *
 * @author Sven Wende (class of same name in SDS)
 * @author Xihui Chen
 *
 */
public class WidgetPropertyChangeListener implements PropertyChangeListener {

    private AbstractBaseEditPart editpart;
    private AbstractWidgetProperty widgetProperty;
    private List<IWidgetPropertyChangeHandler> latestUpdateHandlers;
	private List<IWidgetPropertyChangeHandler> allUpdatesHandlers;

	public enum UpdatePolicy {
		ALL_UPDATES,
		ONLY_LATEST_UPDATE
	}

    /**Constructor.
     * @param editpart backlint to the editpart, which uses this listener.
     */
    public WidgetPropertyChangeListener(AbstractBaseEditPart editpart,
            AbstractWidgetProperty property) {
        this.editpart = editpart;
        this.widgetProperty = property;
		latestUpdateHandlers = new ArrayList<IWidgetPropertyChangeHandler>();
		allUpdatesHandlers = new ArrayList<IWidgetPropertyChangeHandler>();
    }

    @Override
    public void propertyChange(final PropertyChangeEvent evt) {
		boolean isRunMode = editpart.getExecutionMode() == ExecutionMode.RUN_MODE;
		
		if (!latestUpdateHandlers.isEmpty()) {
			Runnable runnable = new Runnable() {
				@Override
				public synchronized void run() {
					if (editpart == null || !editpart.isActive()) {
						return;
					}
					for (IWidgetPropertyChangeHandler h : latestUpdateHandlers) {
						IFigure figure = editpart.getFigure();
						h.handleChange(evt.getOldValue(), evt.getNewValue(), figure);
					}
				}
			};
			Display display = editpart.getViewer().getControl().getDisplay();
			WidgetIgnorableUITask task = new WidgetIgnorableUITask(widgetProperty, runnable, display);
			GUIRefreshThread.getInstance(isRunMode).addIgnorableTask(task);
		}

		if (!allUpdatesHandlers.isEmpty()) {
			Runnable runnable = new Runnable() {
				@Override
				public synchronized void run() {
					if (editpart == null || !editpart.isActive()) {
						return;
					}
					for (IWidgetPropertyChangeHandler h : allUpdatesHandlers) {
						IFigure figure = editpart.getFigure();
						h.handleChange(evt.getOldValue(), evt.getNewValue(), figure);
					}
				}
			};
			Display display = editpart.getViewer().getControl().getDisplay();
			WidgetIgnorableUITask task = new WidgetIgnorableUITask(widgetProperty, runnable, display);
			GUIRefreshThread.getInstance(isRunMode).addNonIgnorableTask(task);
		}
	}

    /**Add handler, which is informed when a property changed.
     * @param handler
     */
    public void addHandler(final IWidgetPropertyChangeHandler handler, UpdatePolicy policy) {
        assert handler != null;
		if (policy == UpdatePolicy.ALL_UPDATES) {
			allUpdatesHandlers.add(handler);
		} else {
			latestUpdateHandlers.add(handler);
		}
    }

    public void removeAllHandlers(){
		allUpdatesHandlers.clear();
		latestUpdateHandlers.clear();
    }

}
