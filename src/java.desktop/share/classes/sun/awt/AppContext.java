/*
 * Copyright (c) 1998, 2024, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package sun.awt;

import java.awt.EventQueue;
import java.awt.Window;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.Toolkit;
import java.awt.GraphicsEnvironment;
import java.awt.event.InvocationEvent;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.beans.PropertyChangeSupport;
import java.beans.PropertyChangeListener;
import java.lang.ref.SoftReference;

import sun.util.logging.PlatformLogger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * The AppContext is a table referenced by ThreadGroup which stores
 * application service instances.  (If you are not writing an application
 * service, or don't know what one is, please do not use this class.)
 * The AppContext allows a context access to what would otherwise be
 * potentially dangerous services, such as the ability to peek at
 * EventQueues or change the look-and-feel of a Swing application.<p>
 *
 * Most application services use a singleton object to provide their
 * services, either as a default (such as getSystemEventQueue or
 * getDefaultToolkit) or as static methods with class data (System).
 * The AppContext works with the former method by extending the concept
 * of "default" to be ThreadGroup-specific.  Application services
 * lookup their singleton in the AppContext.<p>
 *
 * For example, here we have a Foo service, with its pre-AppContext
 * code:<p>
 * <pre>{@code
 *    public class Foo {
 *        private static Foo defaultFoo = new Foo();
 *
 *        public static Foo getDefaultFoo() {
 *            return defaultFoo;
 *        }
 *
 *    ... Foo service methods
 *    }
 * }</pre><p>
 *
 * The problem with the above is that the Foo service is global in scope,
 * so that untrusted code can execute methods on the
 * single, shared Foo instance.  The Foo service therefore either needs
 * to block its use by untrusted code using a SecurityManager test, or
 * restrict its capabilities so that it doesn't matter if untrusted code
 * executes it.<p>
 *
 * Here's the Foo class written to use the AppContext:<p>
 * <pre>{@code
 *    public class Foo {
 *        public static Foo getDefaultFoo() {
 *            Foo foo = (Foo)AppContext.getAppContext().get(Foo.class);
 *            if (foo == null) {
 *                foo = new Foo();
 *                getAppContext().put(Foo.class, foo);
 *            }
 *            return foo;
 *        }
 *
 *    ... Foo service methods
 *    }
 * }</pre><p>
 *
 * Since a separate AppContext can exist for each ThreadGroup, trusted
 * and untrusted code have access to different Foo instances.  This allows
 * untrusted code access to "system-wide" services -- the service remains
 * within the AppContext "sandbox".  For example, say malicious code
 * wants to peek all of the key events on the EventQueue to listen for
 * passwords; if separate EventQueues are used for each ThreadGroup
 * using AppContexts, the only key events that code will be able to
 * listen to are its own.  A more reasonable request would be to
 * change the Swing default look-and-feel; with that default stored in
 * an AppContext, the look-and-feel will change without
 * disrupting other contexts.
 *
 * @author  Thomas Ball
 * @author  Fred Ecks
 */
public final class AppContext {
    private static final PlatformLogger log = PlatformLogger.getLogger("sun.awt.AppContext");

    /* Since the contents of an AppContext are unique to each Java
     * session, this class should never be serialized. */

    /*
     * The key to put()/get() the Java EventQueue into/from the AppContext.
     */
    public static final Object EVENT_QUEUE_KEY = new StringBuffer("EventQueue");

    /*
     * The keys to store EventQueue push/pop lock and condition.
     */
    public static final Object EVENT_QUEUE_LOCK_KEY = new StringBuilder("EventQueue.Lock");
    public static final Object EVENT_QUEUE_COND_KEY = new StringBuilder("EventQueue.Condition");

    /* A map of AppContexts, referenced by ThreadGroup.
     */
    private static final Map<ThreadGroup, AppContext> threadGroup2appContext =
            Collections.synchronizedMap(new IdentityHashMap<ThreadGroup, AppContext>());

    /**
     * Returns a set containing all {@code AppContext}s.
     */
    public static Set<AppContext> getAppContexts() {
        synchronized (threadGroup2appContext) {
            return new HashSet<AppContext>(threadGroup2appContext.values());
        }
    }

    /* The main "system" AppContext, used by everything not otherwise
       contained in another AppContext. It is implicitly created for
       standalone apps only.
     */
    private static volatile AppContext mainAppContext;

    private static class GetAppContextLock {}
    private static final Object getAppContextLock = new GetAppContextLock();

    /*
     * The hash map associated with this AppContext.  A private delegate
     * is used instead of subclassing HashMap so as to avoid all of
     * HashMap's potentially risky methods, such as clear(), elements(),
     * putAll(), etc.
     */
    private final Map<Object, Object> table = new HashMap<>();

    private final ThreadGroup threadGroup;

    /**
     * If any {@code PropertyChangeListeners} have been registered,
     * the {@code changeSupport} field describes them.
     *
     * @see #addPropertyChangeListener
     * @see #removePropertyChangeListener
     * @see PropertyChangeSupport#firePropertyChange
     */
    private PropertyChangeSupport changeSupport = null;

    public static final String DISPOSED_PROPERTY_NAME = "disposed";
    public static final String GUI_DISPOSED = "guidisposed";

    private enum State {
        VALID,
        BEING_DISPOSED,
        DISPOSED
    }

    private volatile State state = State.VALID;

    public boolean isDisposed() {
        return state == State.DISPOSED;
    }

    /*
     * The total number of AppContexts, system-wide.  This number is
     * incremented at the beginning of the constructor, and decremented
     * at the end of dispose().  getAppContext() checks to see if this
     * number is 1.  If so, it returns the sole AppContext without
     * checking Thread.currentThread().
     */
    private static final AtomicInteger numAppContexts = new AtomicInteger();


    /*
     * The context ClassLoader that was used to create this AppContext.
     */
    private final ClassLoader contextClassLoader;

    /**
     * Constructor for AppContext.  This method is <i>not</i> public,
     * nor should it ever be used as such.  The proper way to construct
     * an AppContext is through the use of SunToolkit.createNewAppContext.
     * A ThreadGroup is created for the new AppContext, a Thread is
     * created within that ThreadGroup, and that Thread calls
     * SunToolkit.createNewAppContext before calling anything else.
     * That creates both the new AppContext and its EventQueue.
     *
     * @param   threadGroup     The ThreadGroup for the new AppContext
     * @see     sun.awt.SunToolkit
     * @since   1.2
     */
    AppContext(ThreadGroup threadGroup) {
        numAppContexts.incrementAndGet();

        this.threadGroup = threadGroup;
        threadGroup2appContext.put(threadGroup, this);

        this.contextClassLoader = Thread.currentThread().getContextClassLoader();
        // Initialize push/pop lock and its condition to be used by all the
        // EventQueues within this AppContext
        Lock eventQueuePushPopLock = new ReentrantLock();
        put(EVENT_QUEUE_LOCK_KEY, eventQueuePushPopLock);
        Condition eventQueuePushPopCond = eventQueuePushPopLock.newCondition();
        put(EVENT_QUEUE_COND_KEY, eventQueuePushPopCond);
    }

    private static final ThreadLocal<AppContext> threadAppContext =
            new ThreadLocal<AppContext>();

    private static void initMainAppContext() {
        // On the main Thread, we get the ThreadGroup, make a corresponding
        // AppContext, and instantiate the Java EventQueue.  This way, legacy
        // code is unaffected by the move to multiple AppContext ability.
        ThreadGroup currentThreadGroup = Thread.currentThread().getThreadGroup();
        ThreadGroup parentThreadGroup = currentThreadGroup.getParent();
        while (parentThreadGroup != null) {
            // Find the root ThreadGroup to construct our main AppContext
            currentThreadGroup = parentThreadGroup;
            parentThreadGroup = currentThreadGroup.getParent();
        }

        mainAppContext = SunToolkit.createNewAppContext(currentThreadGroup);
    }

    /**
     * Returns the appropriate AppContext for the caller,
     * as determined by its ThreadGroup.
     *
     * @return  the AppContext for the caller.
     * @see     java.lang.ThreadGroup
     * @since   1.2
     */
    public static AppContext getAppContext() {
        // we are standalone app, return the main app context
        if (numAppContexts.get() == 1 && mainAppContext != null) {
            return mainAppContext;
        }

        AppContext appContext = threadAppContext.get();

        if (null == appContext) {
            // Get the current ThreadGroup, and look for it and its
            // parents in the hash from ThreadGroup to AppContext --
            // it should be found, because we use createNewContext()
            // when new AppContext objects are created.
            ThreadGroup currentThreadGroup = Thread.currentThread().getThreadGroup();
            ThreadGroup threadGroup = currentThreadGroup;

            // Special case: we implicitly create the main app context
            // if no contexts have been created yet.
            synchronized (getAppContextLock) {
                if (numAppContexts.get() == 0) {
                    if (System.getProperty("javaplugin.version") == null &&
                            System.getProperty("javawebstart.version") == null) {
                        initMainAppContext();
                    } else if (System.getProperty("javafx.version") != null &&
                            threadGroup.getParent() != null) {
                        // Swing inside JavaFX case
                        SunToolkit.createNewAppContext();
                    }
                }
            }

            AppContext context = threadGroup2appContext.get(threadGroup);
            while (context == null) {
                threadGroup = threadGroup.getParent();
                if (threadGroup == null) {
                    // We've got up to the root thread group and did not find an AppContext
                    // We have nowhere else to look, and this is not supposed to happen.
                    // return null from this whole method.
                    return null;
                }
                context = threadGroup2appContext.get(threadGroup);
            }

            // In case we did anything in the above while loop, we add
            // all the intermediate ThreadGroups to threadGroup2appContext
            // so we won't spin again.
            for (ThreadGroup tg = currentThreadGroup; tg != threadGroup; tg = tg.getParent()) {
                threadGroup2appContext.put(tg, context);
            }

            // Now we're done, so we cache the latest key/value pair.
            threadAppContext.set(context);

            appContext = context;
        }

        return appContext;
    }

    /**
     * Returns true if the specified AppContext is the main AppContext.
     *
     * @param   ctx the context to compare with the main context
     * @return  true if the specified AppContext is the main AppContext.
     * @since   1.8
     */
    public static boolean isMainContext(AppContext ctx) {
        return (ctx != null && ctx == mainAppContext);
    }

    private long DISPOSAL_TIMEOUT = 5000;  // Default to 5-second timeout
                                           // for disposal of all Frames
                                           // (we wait for this time twice,
                                           // once for dispose(), and once
                                           // to clear the EventQueue).

    private long THREAD_INTERRUPT_TIMEOUT = 1000;
                            // Default to 1-second timeout for all
                            // interrupted Threads to exit, and another
                            // 1 second for all stopped Threads to die.

    /**
     * Disposes of this AppContext, all of its top-level Frames, and
     * all Threads and ThreadGroups contained within it.
     *
     * This method must be called from a Thread which is not contained
     * within this AppContext.
     *
     * @throws  IllegalThreadStateException  if the current thread is
     *                                    contained within this AppContext
     * @since      1.2
     */
    public void dispose() throws IllegalThreadStateException {
        System.err.println(
            """
            WARNING: sun.awt.AppContext.dispose() no longer stops threads.
            Additionally AppContext will be removed in a future release.
            Remove all uses of this internal class as soon as possible.
            There is no replacement.
            """);
        // Check to be sure that the current Thread isn't in this AppContext
        if (this.threadGroup.parentOf(Thread.currentThread().getThreadGroup())) {
            throw new IllegalThreadStateException(
                "Current Thread is contained within AppContext to be disposed."
              );
        }

        synchronized(this) {
            if (this.state != State.VALID) {
                return; // If already disposed or being disposed, bail.
            }

            this.state = State.BEING_DISPOSED;
        }

        final PropertyChangeSupport changeSupport = this.changeSupport;
        if (changeSupport != null) {
            changeSupport.firePropertyChange(DISPOSED_PROPERTY_NAME, false, true);
        }

        // First, we post an InvocationEvent to be run on the
        // EventDispatchThread which disposes of all top-level Frames and TrayIcons

        final Object notificationLock = new Object();

        Runnable runnable = new Runnable() {
            public void run() {
                Window[] windowsToDispose = Window.getOwnerlessWindows();
                for (Window w : windowsToDispose) {
                    try {
                        w.dispose();
                    } catch (Throwable t) {
                        log.finer("exception occurred while disposing app context", t);
                    }
                }
                if (!GraphicsEnvironment.isHeadless() && SystemTray.isSupported()) {
                    SystemTray systemTray = SystemTray.getSystemTray();
                    TrayIcon[] trayIconsToDispose = systemTray.getTrayIcons();
                    for (TrayIcon ti : trayIconsToDispose) {
                        systemTray.remove(ti);
                    }
                }
                // Alert PropertyChangeListeners that the GUI has been disposed.
                if (changeSupport != null) {
                    changeSupport.firePropertyChange(GUI_DISPOSED, false, true);
                }
                synchronized(notificationLock) {
                    notificationLock.notifyAll(); // Notify caller that we're done
                }
            }
        };
        synchronized(notificationLock) {
            SunToolkit.postEvent(this,
                new InvocationEvent(Toolkit.getDefaultToolkit(), runnable));
            try {
                notificationLock.wait(DISPOSAL_TIMEOUT);
            } catch (InterruptedException e) { }
        }

        // Next, we post another InvocationEvent to the end of the
        // EventQueue.  When it's executed, we know we've executed all
        // events in the queue.

        runnable = new Runnable() { public void run() {
            synchronized(notificationLock) {
                notificationLock.notifyAll(); // Notify caller that we're done
            }
        } };
        synchronized(notificationLock) {
            SunToolkit.postEvent(this,
                new InvocationEvent(Toolkit.getDefaultToolkit(), runnable));
            try {
                notificationLock.wait(DISPOSAL_TIMEOUT);
            } catch (InterruptedException e) { }
        }

        // We are done with posting events, so change the state to disposed
        synchronized(this) {
            this.state = State.DISPOSED;
        }

        // Next, we interrupt all Threads in the ThreadGroup
        this.threadGroup.interrupt();
            // Note, the EventDispatchThread we've interrupted may dump an
            // InterruptedException to the console here.  This needs to be
            // fixed in the EventDispatchThread, not here.

        // Next, we sleep 10ms at a time, waiting for all of the active
        // Threads in the ThreadGroup to exit.

        long startTime = System.currentTimeMillis();
        long endTime = startTime + THREAD_INTERRUPT_TIMEOUT;
        while ((this.threadGroup.activeCount() > 0) &&
               (System.currentTimeMillis() < endTime)) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) { }
        }

        // Next, we remove this and all subThreadGroups from threadGroup2appContext
        int numSubGroups = this.threadGroup.activeGroupCount();
        if (numSubGroups > 0) {
            ThreadGroup [] subGroups = new ThreadGroup[numSubGroups];
            numSubGroups = this.threadGroup.enumerate(subGroups);
            for (int subGroup = 0; subGroup < numSubGroups; subGroup++) {
                threadGroup2appContext.remove(subGroups[subGroup]);
            }
        }
        threadGroup2appContext.remove(this.threadGroup);

        threadAppContext.set(null);

        synchronized (table) {
            this.table.clear(); // Clear out the Hashtable to ease garbage collection
        }

        numAppContexts.decrementAndGet();

        mostRecentKeyValue = null;
    }

    static final class PostShutdownEventRunnable implements Runnable {
        private final AppContext appContext;

        PostShutdownEventRunnable(AppContext ac) {
            appContext = ac;
        }

        public void run() {
            final EventQueue eq = (EventQueue)appContext.get(EVENT_QUEUE_KEY);
            if (eq != null) {
                eq.postEvent(AWTAutoShutdown.getShutdownEvent());
            }
        }
    }

    static void stopEventDispatchThreads() {
        for (AppContext appContext: getAppContexts()) {
            if (appContext.isDisposed()) {
                continue;
            }
            Runnable r = new PostShutdownEventRunnable(appContext);
            // For security reasons EventQueue.postEvent should only be called
            // on a thread that belongs to the corresponding thread group.
            if (appContext != AppContext.getAppContext()) {
                // Create a thread that belongs to the thread group associated
                // with the AppContext and invokes EventQueue.postEvent.
                Thread thread = new Thread(appContext.getThreadGroup(),
                                           r, "AppContext Disposer", 0, false);
                thread.setContextClassLoader(appContext.getContextClassLoader());
                thread.setPriority(Thread.NORM_PRIORITY + 1);
                thread.setDaemon(true);
                thread.start();
            } else {
                r.run();
            }
        }
    }

    private MostRecentKeyValue mostRecentKeyValue = null;
    private MostRecentKeyValue shadowMostRecentKeyValue = null;

    /**
     * Returns the value to which the specified key is mapped in this context.
     *
     * @param   key   a key in the AppContext.
     * @return  the value to which the key is mapped in this AppContext;
     *          {@code null} if the key is not mapped to any value.
     * @see     #put(Object, Object)
     * @since   1.2
     */
    public Object get(Object key) {
        /*
         * The most recent reference should be updated inside a synchronized
         * block to avoid a race when put() and get() are executed in
         * parallel on different threads.
         */
        synchronized (table) {
            // Note: this most recent key/value caching is thread-hot.
            // A simple test using SwingSet found that 72% of lookups
            // were matched using the most recent key/value.  By instantiating
            // a simple MostRecentKeyValue object on cache misses, the
            // cache hits can be processed without synchronization.

            MostRecentKeyValue recent = mostRecentKeyValue;
            if ((recent != null) && (recent.key == key)) {
                return recent.value;
            }

            Object value = table.get(key);
            if(mostRecentKeyValue == null) {
                mostRecentKeyValue = new MostRecentKeyValue(key, value);
                shadowMostRecentKeyValue = new MostRecentKeyValue(key, value);
            } else {
                MostRecentKeyValue auxKeyValue = mostRecentKeyValue;
                shadowMostRecentKeyValue.setPair(key, value);
                mostRecentKeyValue = shadowMostRecentKeyValue;
                shadowMostRecentKeyValue = auxKeyValue;
            }
            return value;
        }
    }

    /**
     * Maps the specified {@code key} to the specified
     * {@code value} in this AppContext.  Neither the key nor the
     * value can be {@code null}.
     * <p>
     * The value can be retrieved by calling the {@code get} method
     * with a key that is equal to the original key.
     *
     * @param      key     the AppContext key.
     * @param      value   the value.
     * @return     the previous value of the specified key in this
     *             AppContext, or {@code null} if it did not have one.
     * @throws  NullPointerException  if the key or value is
     *               {@code null}.
     * @see     #get(Object)
     * @since   1.2
     */
    public Object put(Object key, Object value) {
        synchronized (table) {
            MostRecentKeyValue recent = mostRecentKeyValue;
            if ((recent != null) && (recent.key == key))
                recent.value = value;
            return table.put(key, value);
        }
    }

    /**
     * Removes the key (and its corresponding value) from this
     * AppContext. This method does nothing if the key is not in the
     * AppContext.
     *
     * @param   key   the key that needs to be removed.
     * @return  the value to which the key had been mapped in this AppContext,
     *          or {@code null} if the key did not have a mapping.
     * @since   1.2
     */
    public Object remove(Object key) {
        synchronized (table) {
            MostRecentKeyValue recent = mostRecentKeyValue;
            if ((recent != null) && (recent.key == key))
                recent.value = null;
            return table.remove(key);
        }
    }

    /**
     * Returns the root ThreadGroup for all Threads contained within
     * this AppContext.
     * @since   1.2
     */
    public ThreadGroup getThreadGroup() {
        return threadGroup;
    }

    /**
     * Returns the context ClassLoader that was used to create this
     * AppContext.
     *
     * @see java.lang.Thread#getContextClassLoader
     */
    public ClassLoader getContextClassLoader() {
        return contextClassLoader;
    }

    /**
     * Returns a string representation of this AppContext.
     * @since   1.2
     */
    @Override
    public String toString() {
        return getClass().getName() + "[threadGroup=" + threadGroup.getName() + "]";
    }

    /**
     * Returns an array of all the property change listeners
     * registered on this component.
     *
     * @return all of this component's {@code PropertyChangeListener}s
     *         or an empty array if no property change
     *         listeners are currently registered
     *
     * @see      #addPropertyChangeListener
     * @see      #removePropertyChangeListener
     * @see      #getPropertyChangeListeners(java.lang.String)
     * @see      java.beans.PropertyChangeSupport#getPropertyChangeListeners
     * @since    1.4
     */
    public synchronized PropertyChangeListener[] getPropertyChangeListeners() {
        if (changeSupport == null) {
            return new PropertyChangeListener[0];
        }
        return changeSupport.getPropertyChangeListeners();
    }

    /**
     * Adds a PropertyChangeListener to the listener list for a specific
     * property. The specified property may be one of the following:
     * <ul>
     *    <li>if this AppContext is disposed ("disposed")</li>
     * </ul>
     * <ul>
     *    <li>if this AppContext's unowned Windows have been disposed
     *    ("guidisposed").  Code to cleanup after the GUI is disposed
     *    (such as LookAndFeel.uninitialize()) should execute in response to
     *    this property being fired.  Notifications for the "guidisposed"
     *    property are sent on the event dispatch thread.</li>
     * </ul>
     * <p>
     * If listener is null, no exception is thrown and no action is performed.
     *
     * @param propertyName one of the property names listed above
     * @param listener the PropertyChangeListener to be added
     *
     * @see #removePropertyChangeListener(java.lang.String, java.beans.PropertyChangeListener)
     * @see #getPropertyChangeListeners(java.lang.String)
     * @see #addPropertyChangeListener(java.lang.String, java.beans.PropertyChangeListener)
     */
    public synchronized void addPropertyChangeListener(
                             String propertyName,
                             PropertyChangeListener listener) {
        if (listener == null) {
            return;
        }
        if (changeSupport == null) {
            changeSupport = new PropertyChangeSupport(this);
        }
        changeSupport.addPropertyChangeListener(propertyName, listener);
    }

    /**
     * Removes a PropertyChangeListener from the listener list for a specific
     * property. This method should be used to remove PropertyChangeListeners
     * that were registered for a specific bound property.
     * <p>
     * If listener is null, no exception is thrown and no action is performed.
     *
     * @param propertyName a valid property name
     * @param listener the PropertyChangeListener to be removed
     *
     * @see #addPropertyChangeListener(java.lang.String, java.beans.PropertyChangeListener)
     * @see #getPropertyChangeListeners(java.lang.String)
     * @see PropertyChangeSupport#removePropertyChangeListener(java.beans.PropertyChangeListener)
     */
    public synchronized void removePropertyChangeListener(
                             String propertyName,
                             PropertyChangeListener listener) {
        if (listener == null || changeSupport == null) {
            return;
        }
        changeSupport.removePropertyChangeListener(propertyName, listener);
    }

    /**
     * Returns an array of all the listeners which have been associated
     * with the named property.
     *
     * @return all of the {@code PropertyChangeListeners} associated with
     *         the named property or an empty array if no listeners have
     *         been added
     *
     * @see #addPropertyChangeListener(java.lang.String, java.beans.PropertyChangeListener)
     * @see #removePropertyChangeListener(java.lang.String, java.beans.PropertyChangeListener)
     * @see #getPropertyChangeListeners
     * @since 1.4
     */
    public synchronized PropertyChangeListener[] getPropertyChangeListeners(
                                                        String propertyName) {
        if (changeSupport == null) {
            return new PropertyChangeListener[0];
        }
        return changeSupport.getPropertyChangeListeners(propertyName);
    }

    public static <T> T getSoftReferenceValue(Object key,
            Supplier<T> supplier) {

        final AppContext appContext = AppContext.getAppContext();
        @SuppressWarnings("unchecked")
        SoftReference<T> ref = (SoftReference<T>) appContext.get(key);
        if (ref != null) {
            final T object = ref.get();
            if (object != null) {
                return object;
            }
        }
        final T object = supplier.get();
        ref = new SoftReference<>(object);
        appContext.put(key, ref);
        return object;
    }
}

final class MostRecentKeyValue {
    Object key;
    Object value;
    MostRecentKeyValue(Object k, Object v) {
        key = k;
        value = v;
    }
    void setPair(Object k, Object v) {
        key = k;
        value = v;
    }
}
