/*******************************************************************************
 * Copyright (c) 2010 Oak Ridge National Laboratory.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 ******************************************************************************/
package org.csstudio.opibuilder.util;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.LinkedHashSet;
import java.util.logging.Level;

import org.csstudio.opibuilder.OPIBuilderPlugin;
import org.csstudio.opibuilder.datadefinition.WidgetIgnorableUITask;
import org.csstudio.opibuilder.preferences.PreferencesHelper;
import org.eclipse.swt.widgets.Display;

/**
 *
 * A singleton back thread which will help to execute tasks for OPI GUI refreshing. This thread sleeps for a time which
 * can be set in the preference page. It can help throttle the unnecessary repaint caused by fast PV value updating.
 *
 * @author Xihui Chen
 *
 */
public final class GUIRefreshThread implements Runnable {
    /**
     * The singleton instance for Runtime, whose GUI refresh cycle is from preference.
     */
    private static GUIRefreshThread runTimeInstance;

    /**
     * The singleton instance for Editing, whose GUI refresh cycle is fixed 100 ms.
     */
    private static GUIRefreshThread editingInstance;

    /**
     * A LinkedHashset, which contains {@link WidgetIgnorableUITask}. It will be processed by this thread periodically.
     * Use hashset can help to improve the performance.
     */
	private ConcurrentLinkedQueue<WidgetIgnorableUITask> nonIgnorableTasksQueue;
    private LinkedHashSet<WidgetIgnorableUITask> ignorableTasksQueue;
    private Thread thread;

    private int guiRefreshCycle = 100;

    private long start;

    private volatile boolean asyncEmpty = true;

    private Runnable resetAsyncEmpty;

    private Display rcpDisplay;

    private boolean isRuntime;

    /**
     * Standard constructor.
     */
    private GUIRefreshThread(boolean isRuntime) {
        this.isRuntime = isRuntime;
        rcpDisplay = DisplayUtils.getDisplay();
		ignorableTasksQueue = new LinkedHashSet<>();
		nonIgnorableTasksQueue = new ConcurrentLinkedQueue<>();
        resetAsyncEmpty = new Runnable() {
            @Override
            public void run() {
                asyncEmpty = true;
            }
        };
        reLoadGUIRefreshCycle();
        thread = new Thread(this, "OPI GUI Refresh Thread");
        thread.start();
    }

    /**
     * Gets the singleton instance.
     *
     * @return the singleton instance
     */
    public static synchronized GUIRefreshThread getInstance(boolean isRuntime) {
        if (isRuntime) {
            if (runTimeInstance == null)
                runTimeInstance = new GUIRefreshThread(isRuntime);
            return runTimeInstance;
        } else {
            if (editingInstance == null)
                editingInstance = new GUIRefreshThread(isRuntime);
            return editingInstance;
        }
    }

    /**
     * Reschedule this task upon the new GUI refresh cycle.
     */
    public void reLoadGUIRefreshCycle() {
        if (isRuntime)
            guiRefreshCycle = PreferencesHelper.getGUIRefreshCycle();
    }

    /**
     * Set GUI Refresh Cycle. This should be temporarily used only. It must be reset by calling
     * {@link #reLoadGUIRefreshCycle()} to ensure consistency.
     * 
     * @param guiRefreshCycle
     */
    public void setGUIRefreshCycle(int guiRefreshCycle) {
        this.guiRefreshCycle = guiRefreshCycle;
    }

    public int getGUIRefreshCycle() {
        return guiRefreshCycle;
    }

    /**
     * {@inheritDoc}.
     */
    @Override
    public void run() {
        boolean isEmpty;
        while (true) {
            synchronized (this) {
                isEmpty = ignorableTasksQueue.isEmpty() && nonIgnorableTasksQueue.isEmpty();
            }
            if (!isEmpty) {
                start = System.currentTimeMillis();
                rcpProcessQueue();

                try {
                    long current = System.currentTimeMillis();
                    if (current - start < guiRefreshCycle)
                        Thread.sleep(guiRefreshCycle - (current - start));
                } catch (InterruptedException e) {
                    // ignore
                }
            } else
                try {
                    Thread.sleep(guiRefreshCycle);
                } catch (InterruptedException e) {
                    // ignore
                }

        }
    }

    /**
     * Process the complete queue in RCP.
     */
    private void rcpProcessQueue() {
        // avoid add too many stuff to Display async queue.
        if (!asyncEmpty)
            return;
        asyncEmpty = false;
		Object[] ignorableTasksArray;
		Object[] nonIgnorableTasksArray;
        // copy the tasks queue.
        synchronized (this) {
            ignorableTasksArray = ignorableTasksQueue.toArray();
			ignorableTasksQueue.clear();
			nonIgnorableTasksArray = nonIgnorableTasksQueue.toArray();
			nonIgnorableTasksQueue.clear();
        }
        if (rcpDisplay == null || rcpDisplay.isDisposed())
            return;
        for (Object o : ignorableTasksArray) {
            try {
                rcpDisplay.asyncExec(((WidgetIgnorableUITask)o).getRunnableTask());
            } catch (Exception e) {
                OPIBuilderPlugin.getLogger().log(Level.WARNING, "Display has been disposed.", e);
            }
        }
		for (Object o : nonIgnorableTasksArray) {
			try {
				rcpDisplay.asyncExec(((WidgetIgnorableUITask)o).getRunnableTask());
			} catch (Exception e) {
				OPIBuilderPlugin.getLogger().log(Level.WARNING, "Display has been disposed.", e);
			}
		}
        rcpDisplay.asyncExec(resetAsyncEmpty);
    }

    /**
     * Adds the specified runnable to the queue.
     *
     * @param task
     *            the ignorable UI task.
     */
    public synchronized void addIgnorableTask(final WidgetIgnorableUITask task) {
		ignorableTasksQueue.remove(task);
		ignorableTasksQueue.add(task);
    }

	public void addNonIgnorableTask(final WidgetIgnorableUITask task) {
		nonIgnorableTasksQueue.add(task);
	}
}
