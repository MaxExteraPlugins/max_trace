package com.exteraplugins.MaxTrace;

import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Debug;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

public class Main {
    public static final String TAG = "MaxTrace";
    public static final String VERSION = "1.1.9";
    public static final int VERSION_CODE = 15;

    private static volatile boolean sTrackScreens = true;
    private static volatile boolean sTrackLags = true;
    private static volatile int sLagThresholdMs = 500;
    private static volatile boolean sInitialized = false;

    private static WatchdogThread sWatchdog = null;
    private static final List<TrackedFragment> sTrackedFragments = Collections.synchronizedList(new ArrayList<TrackedFragment>());
    private static final List<LagRecord> sLagRecords = Collections.synchronizedList(new ArrayList<LagRecord>());
    private static final int MAX_LAG_RECORDS = 50;

    private static final List<CrashRecord> sCrashRecords = Collections.synchronizedList(new ArrayList<CrashRecord>());
    private static final int MAX_CRASH_RECORDS = 30;
    private static Thread.UncaughtExceptionHandler sPreviousUEH = null;

    private static final List<MemoryPressureRecord> sMemoryPressureRecords = Collections.synchronizedList(new ArrayList<MemoryPressureRecord>());
    private static final int MAX_MEMORY_PRESSURE_RECORDS = 20;

    // Thread ID -> Plugin ID attribution registry
    private static final Map<Long, String> sThreadOwners = new ConcurrentHashMap<Long, String>();

    // NotificationCenter Event ID -> Event Name mapping (built dynamically from NotificationCenter static fields)
    private static final Map<Integer, String> sEventNames = new ConcurrentHashMap<Integer, String>();
    // Plugin ID -> (Event Name -> Count)
    private static final Map<String, Map<String, Integer>> sPluginObserverDetails = new ConcurrentHashMap<String, Map<String, Integer>>();
    // Plugin ID -> Total Observer Count
    private static final Map<String, Integer> sObserverCounts = new ConcurrentHashMap<String, Integer>();

    // Plugin ID -> Network Request Stats (total, inLastMinute, lastRequestType, lastRequestTimestamp)
    public static class NetworkPluginStats {
        public int totalRequests = 0;
        public final List<Long> requestTimestamps = new ArrayList<Long>();
        public String lastRequestType = "none";
        public long lastRequestTime = 0;
    }
    private static final Map<String, NetworkPluginStats> sNetworkStats = new ConcurrentHashMap<String, NetworkPluginStats>();

    // Known Plugin ID -> Plugin Name cache
    private static final Map<String, String> sKnownPlugins = new ConcurrentHashMap<String, String>();

    public static class TrackedFragment {
        public final WeakReference<Object> ref;
        public final String className;
        public final long destroyTime;

        public TrackedFragment(Object fragment) {
            this.ref = new WeakReference<Object>(fragment);
            this.className = fragment.getClass().getName();
            this.destroyTime = System.currentTimeMillis();
        }
    }

    public static class LagRecord {
        public final long timestamp;
        public final long durationMs;
        public final String culprit;
        public final String topStack;
        public final String fullStack;

        public LagRecord(long timestamp, long durationMs, String culprit, String topStack, String fullStack) {
            this.timestamp = timestamp;
            this.durationMs = durationMs;
            this.culprit = culprit;
            this.topStack = topStack;
            this.fullStack = fullStack;
        }
    }

    public static class CrashRecord {
        public final long timestamp;
        public final String threadName;
        public final String culprit;
        public final String exceptionClass;
        public final String message;
        public final String stackTrace;

        public CrashRecord(long timestamp, String threadName, String culprit, String exceptionClass, String message, String stackTrace) {
            this.timestamp = timestamp;
            this.threadName = threadName;
            this.culprit = culprit;
            this.exceptionClass = exceptionClass;
            this.message = message;
            this.stackTrace = stackTrace;
        }
    }

    public static class MemoryPressureRecord {
        public final long timestamp;
        public final int level;
        public final long javaUsedBytes;
        public final long javaMaxBytes;
        public final long nativeHeapAllocatedBytes;
        public final String levelName;

        public MemoryPressureRecord(long timestamp, int level, long javaUsedBytes, long javaMaxBytes, long nativeHeapAllocatedBytes, String levelName) {
            this.timestamp = timestamp;
            this.level = level;
            this.javaUsedBytes = javaUsedBytes;
            this.javaMaxBytes = javaMaxBytes;
            this.nativeHeapAllocatedBytes = nativeHeapAllocatedBytes;
            this.levelName = levelName;
        }
    }

    private static class WatchdogThread extends Thread {
        private final Handler mainHandler = new Handler(Looper.getMainLooper());
        private final Thread mainThread = Looper.getMainLooper().getThread();
        private final AtomicBoolean responded = new AtomicBoolean(true);
        private volatile boolean running = true;

        private final Runnable checkRunnable = new Runnable() {
            @Override
            public void run() {
                responded.set(true);
            }
        };

        public WatchdogThread() {
            super("MaxTrace-Watchdog");
            setDaemon(true);
        }

        public void stopWatchdog() {
            running = false;
            interrupt();
        }

        @Override
        public void run() {
            while (running) {
                if (!sTrackLags) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        if (!running) break;
                    }
                    continue;
                }

                responded.set(false);
                mainHandler.post(checkRunnable);

                long startTime = System.currentTimeMillis();
                long threshold = Math.max(200, sLagThresholdMs);

                try {
                    Thread.sleep(threshold);
                } catch (InterruptedException e) {
                    if (!running) break;
                }

                if (!responded.get()) {
                    long stallStart = startTime;
                    StackTraceElement[] stack = mainThread.getStackTrace();
                    String culprit = detectCulprit(stack);
                    String topStack = stack.length > 0 ? stack[0].toString() : "empty";
                    StringBuilder sb = new StringBuilder();
                    int maxLines = Math.min(stack.length, 30);
                    for (int i = 0; i < maxLines; i++) {
                        sb.append(stack[i].toString()).append("\n");
                    }

                    while (!responded.get() && running) {
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException e) {
                            if (!running) break;
                        }
                    }

                    long totalDuration = System.currentTimeMillis() - stallStart;
                    if (totalDuration >= threshold) {
                        addLagRecord(new LagRecord(stallStart, totalDuration, culprit, topStack, sb.toString()));
                        Log.w(TAG, "UI Stall detected: " + totalDuration + "ms, culprit: " + culprit);
                    }
                }
            }
        }
    }

    public static void updateKnownPlugins() {
        try {
            Class<?> pcCls = Class.forName("com.exteragram.messenger.plugins.PluginsController");
            Object companion = pcCls.getField("Companion").get(null);
            Method getInstanceMethod = companion.getClass().getMethod("getInstance");
            Object instance = getInstanceMethod.invoke(companion);
            if (instance != null) {
                Method getPluginsMethod = instance.getClass().getMethod("getPlugins");
                Map<?, ?> plugins = (Map<?, ?>) getPluginsMethod.invoke(instance);
                if (plugins != null) {
                    for (Map.Entry<?, ?> entry : plugins.entrySet()) {
                        String id = String.valueOf(entry.getKey());
                        Object pObj = entry.getValue();
                        if (pObj != null) {
                            try {
                                Method getNameMethod = pObj.getClass().getMethod("getName");
                                String name = (String) getNameMethod.invoke(pObj);
                                sKnownPlugins.put(id, name != null ? name : id);
                            } catch (Throwable ignored) {
                                sKnownPlugins.put(id, id);
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    public static String detectCulprit(StackTraceElement[] stack) {
        if (stack == null) return "Unknown";
        for (StackTraceElement elem : stack) {
            String cls = elem.getClassName();
            if (cls.startsWith("com.exteraplugins.")) {
                String[] parts = cls.split("\\.");
                String pId = parts.length >= 3 ? parts[2] : cls;
                String pName = sKnownPlugins.get(pId);
                return pName != null ? "Plugin: " + pName + " (" + pId + ")" : "Plugin: " + pId;
            }
            if (cls.startsWith("com.ayugram.plugins.")) {
                String[] parts = cls.split("\\.");
                String pId = parts.length >= 4 ? parts[3] : cls;
                String pName = sKnownPlugins.get(pId);
                return pName != null ? "Plugin: " + pName + " (" + pId + ")" : "Plugin: " + pId;
            }
            if (cls.startsWith("plugins.")) {
                String[] parts = cls.split("\\.");
                String pId = parts.length >= 2 ? parts[1] : cls;
                String pName = sKnownPlugins.get(pId);
                return pName != null ? "Plugin: " + pName + " (" + pId + ")" : "Plugin: " + pId;
            }
            if (cls.startsWith("com.exteragram.plugins.")) {
                String[] parts = cls.split("\\.");
                String pId = parts.length >= 4 ? parts[3] : cls;
                String pName = sKnownPlugins.get(pId);
                return pName != null ? "Plugin: " + pName + " (" + pId + ")" : "Plugin: " + pId;
            }
            String file = elem.getFileName();
            if (file != null && file.endsWith(".py")) {
                String base = file;
                if (base.contains("/")) base = base.substring(base.lastIndexOf('/') + 1);
                if (base.contains("\\")) base = base.substring(base.lastIndexOf('\\') + 1);
                if (!base.equals("python.py") && !base.equals("base_plugin.py")) {
                    String pId = base.endsWith(".py") ? base.substring(0, base.length() - 3) : base;
                    String pName = sKnownPlugins.get(pId);
                    return pName != null ? "Python: " + pName + " (" + pId + ")" : "Python: " + pId;
                }
            }
            if (cls.contains("chaquo") || cls.contains("python")) {
                return "Python Plugin Hook (" + elem.getMethodName() + ")";
            }
            if (cls.contains("xposed") || cls.contains("XposedBridge")) {
                return "Xposed Hook: " + elem.getMethodName();
            }
            if (cls.contains("SQLite") || cls.contains("database")) {
                return "Database I/O on UI thread";
            }
            if (cls.contains("FinalizerDaemon") || cls.contains("ReferenceQueueDaemon")) {
                return "GC / Finalizer daemon";
            }
        }
        if (stack.length > 0) {
            return "Host UI: " + stack[0].getClassName() + "." + stack[0].getMethodName();
        }
        return "Unknown";
    }

    public static String detectCulpritFromThrowable(Throwable t) {
        if (t == null) return "Unknown";
        Throwable cur = t;
        while (cur != null) {
            String cul = detectCulprit(cur.getStackTrace());
            if (cul != null && !cul.equals("Unknown") && !cul.startsWith("Host UI")) {
                return cul;
            }
            cur = cur.getCause();
        }
        return detectCulprit(t.getStackTrace());
    }

    private static void addLagRecord(LagRecord record) {
        synchronized (sLagRecords) {
            if (sLagRecords.size() >= MAX_LAG_RECORDS) {
                sLagRecords.remove(0);
            }
            sLagRecords.add(record);
        }
    }

    private static void hookBaseFragment() {
        try {
            Class<?> baseFragmentClass = Class.forName("org.telegram.ui.ActionBar.BaseFragment");
            Method onFragmentDestroyMethod = baseFragmentClass.getDeclaredMethod("onFragmentDestroy");
            XposedBridge.hookMethod(onFragmentDestroyMethod, new XC_MethodHook() {
                @Override
                public void afterHookedMethod(MethodHookParam param) {
                    if (!sTrackScreens) return;
                    Object fragment = param.thisObject;
                    if (fragment != null) {
                        sTrackedFragments.add(new TrackedFragment(fragment));
                    }
                }
            });
            Log.d(TAG, "Hooked BaseFragment.onFragmentDestroy");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook BaseFragment.onFragmentDestroy", t);
        }
    }

    private static void hookThreadStart() {
        try {
            Method startMethod = Thread.class.getDeclaredMethod("start");
            XposedBridge.hookMethod(startMethod, new XC_MethodHook() {
                @Override
                public void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Thread target = (Thread) param.thisObject;
                        if (target == null) return;
                        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
                        String culprit = detectCulprit(stack);
                        if (culprit != null && (culprit.startsWith("Plugin: ") || culprit.startsWith("Python: "))) {
                            sThreadOwners.put(target.getId(), culprit);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
            Log.d(TAG, "Hooked Thread.start for thread-attribution");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook Thread.start", t);
        }
    }

    private static void initEventNamesMap(Class<?> ncClass) {
        try {
            for (Field f : ncClass.getDeclaredFields()) {
                if (f.getType() == int.class && java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                    try {
                        f.setAccessible(true);
                        int id = f.getInt(null);
                        sEventNames.put(id, f.getName());
                    } catch (Throwable ignored) {
                    }
                }
            }
            Log.d(TAG, "Loaded " + sEventNames.size() + " NotificationCenter event names");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to load event names", t);
        }
    }

    private static void hookNotificationCenter() {
        try {
            Class<?> ncClass = Class.forName("org.telegram.messenger.NotificationCenter");
            initEventNamesMap(ncClass);

            for (Method m : ncClass.getDeclaredMethods()) {
                String name = m.getName();
                Class<?>[] pTypes = m.getParameterTypes();
                if ("addObserver".equals(name) && pTypes.length >= 2 && pTypes[1] == int.class) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        public void afterHookedMethod(MethodHookParam param) {
                            try {
                                StackTraceElement[] stack = Thread.currentThread().getStackTrace();
                                String culprit = detectCulprit(stack);
                                if (culprit != null && (culprit.startsWith("Plugin: ") || culprit.startsWith("Python: "))) {
                                    int eventId = (Integer) param.args[1];
                                    String eventName = sEventNames.get(eventId);
                                    if (eventName == null) eventName = "Event#" + eventId;

                                    Integer cur = sObserverCounts.get(culprit);
                                    sObserverCounts.put(culprit, cur == null ? 1 : cur + 1);

                                    Map<String, Integer> details = sPluginObserverDetails.get(culprit);
                                    if (details == null) {
                                        details = new ConcurrentHashMap<String, Integer>();
                                        sPluginObserverDetails.put(culprit, details);
                                    }
                                    Integer eCur = details.get(eventName);
                                    details.put(eventName, eCur == null ? 1 : eCur + 1);
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    });
                } else if ("removeObserver".equals(name) && pTypes.length >= 2 && pTypes[1] == int.class) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        public void afterHookedMethod(MethodHookParam param) {
                            try {
                                StackTraceElement[] stack = Thread.currentThread().getStackTrace();
                                String culprit = detectCulprit(stack);
                                if (culprit != null && (culprit.startsWith("Plugin: ") || culprit.startsWith("Python: "))) {
                                    int eventId = (Integer) param.args[1];
                                    String eventName = sEventNames.get(eventId);
                                    if (eventName == null) eventName = "Event#" + eventId;

                                    Integer cur = sObserverCounts.get(culprit);
                                    if (cur != null && cur > 0) {
                                        sObserverCounts.put(culprit, cur - 1);
                                    }

                                    Map<String, Integer> details = sPluginObserverDetails.get(culprit);
                                    if (details != null) {
                                        Integer eCur = details.get(eventName);
                                        if (eCur != null && eCur > 0) {
                                            details.put(eventName, eCur - 1);
                                        }
                                    }
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    });
                }
            }
            Log.d(TAG, "Hooked NotificationCenter observers with event details");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook NotificationCenter", t);
        }
    }

    private static void hookConnectionsManager() {
        try {
            Class<?> cmClass = Class.forName("org.telegram.tgnet.ConnectionsManager");
            for (Method m : cmClass.getDeclaredMethods()) {
                if (m.getName().startsWith("sendRequest") && m.getParameterTypes().length > 0) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        public void beforeHookedMethod(MethodHookParam param) {
                            try {
                                StackTraceElement[] stack = Thread.currentThread().getStackTrace();
                                String culprit = detectCulprit(stack);
                                if (culprit != null && (culprit.startsWith("Plugin: ") || culprit.startsWith("Python: "))) {
                                    Object tlObj = param.args[0];
                                    String reqType = tlObj != null ? tlObj.getClass().getSimpleName() : "TLObject";

                                    NetworkPluginStats stats = sNetworkStats.get(culprit);
                                    if (stats == null) {
                                        stats = new NetworkPluginStats();
                                        sNetworkStats.put(culprit, stats);
                                    }
                                    long now = System.currentTimeMillis();
                                    synchronized (stats) {
                                        stats.totalRequests++;
                                        stats.lastRequestType = reqType;
                                        stats.lastRequestTime = now;
                                        stats.requestTimestamps.add(now);

                                        // Prune timestamps older than 60s
                                        Iterator<Long> it = stats.requestTimestamps.iterator();
                                        while (it.hasNext()) {
                                            if (now - it.next() > 60000) {
                                                it.remove();
                                            } else {
                                                break;
                                            }
                                        }
                                    }
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    });
                }
            }
            Log.d(TAG, "Hooked ConnectionsManager.sendRequest for network attribution");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook ConnectionsManager.sendRequest", t);
        }
    }

    private static void hookRunOnUIThread() {
        try {
            Class<?> auClass = Class.forName("org.telegram.messenger.AndroidUtilities");
            for (Method m : auClass.getDeclaredMethods()) {
                if ("runOnUIThread".equals(m.getName()) && m.getParameterTypes().length > 0 && m.getParameterTypes()[0] == Runnable.class) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        public void beforeHookedMethod(MethodHookParam param) {
                            try {
                                final Runnable original = (Runnable) param.args[0];
                                if (original == null) return;
                                StackTraceElement[] stack = Thread.currentThread().getStackTrace();
                                final String culprit = detectCulprit(stack);
                                if (culprit != null && (culprit.startsWith("Plugin: ") || culprit.startsWith("Python: "))) {
                                    param.args[0] = new Runnable() {
                                        @Override
                                        public void run() {
                                            long t0 = System.currentTimeMillis();
                                            try {
                                                original.run();
                                            } finally {
                                                long el = System.currentTimeMillis() - t0;
                                                if (el >= 80) {
                                                    String top = original.getClass().getName();
                                                    addLagRecord(new LagRecord(t0, el, culprit, top, "AndroidUtilities.runOnUIThread stall (" + el + " ms)\n" + top));
                                                    Log.w(TAG, "runOnUIThread task stall: " + el + "ms from " + culprit);
                                                }
                                            }
                                        }
                                    };
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    });
                }
            }
            Log.d(TAG, "Hooked AndroidUtilities.runOnUIThread for task stall attribution");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook AndroidUtilities.runOnUIThread", t);
        }
    }

    private static void installUncaughtExceptionHandler() {
        try {
            Thread.UncaughtExceptionHandler defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
            if (defaultHandler != null && defaultHandler.getClass().getName().contains("MaxTrace")) {
                return;
            }
            sPreviousUEH = defaultHandler;
            Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
                @Override
                public void uncaughtException(Thread thread, Throwable throwable) {
                    try {
                        String culprit = detectCulpritFromThrowable(throwable);
                        String stackStr = Log.getStackTraceString(throwable);
                        String excCls = throwable != null ? throwable.getClass().getName() : "UnknownException";
                        String msg = throwable != null ? String.valueOf(throwable.getMessage()) : "";
                        String tName = thread != null ? thread.getName() : "unknown-thread";

                        synchronized (sCrashRecords) {
                            if (sCrashRecords.size() >= MAX_CRASH_RECORDS) {
                                sCrashRecords.remove(0);
                            }
                            sCrashRecords.add(new CrashRecord(System.currentTimeMillis(), tName, culprit, excCls, msg, stackStr));
                        }
                        Log.e(TAG, "MaxTrace intercepted crash in thread [" + tName + "] attributed to: " + culprit);
                    } catch (Throwable ignored) {
                    }
                    if (sPreviousUEH != null) {
                        sPreviousUEH.uncaughtException(thread, throwable);
                    }
                }
            });
            Log.d(TAG, "Installed MaxTrace Java UncaughtExceptionHandler");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to install UncaughtExceptionHandler", t);
        }
    }

    public static void registerComponentCallbacks(Context context) {
        if (context == null) return;
        try {
            context.getApplicationContext().registerComponentCallbacks(new ComponentCallbacks2() {
                @Override
                public void onTrimMemory(int level) {
                    long now = System.currentTimeMillis();
                    Runtime rt = Runtime.getRuntime();
                    long javaUsed = rt.totalMemory() - rt.freeMemory();
                    long javaMax = rt.maxMemory();
                    long nativeHeap = Debug.getNativeHeapAllocatedSize();

                    String lvlName = "TRIM_LEVEL_" + level;
                    if (level == TRIM_MEMORY_COMPLETE) lvlName = "TRIM_COMPLETE";
                    else if (level == TRIM_MEMORY_MODERATE) lvlName = "TRIM_MODERATE";
                    else if (level == TRIM_MEMORY_RUNNING_CRITICAL) lvlName = "TRIM_RUNNING_CRITICAL";
                    else if (level == TRIM_MEMORY_RUNNING_LOW) lvlName = "TRIM_RUNNING_LOW";
                    else if (level == TRIM_MEMORY_UI_HIDDEN) lvlName = "TRIM_UI_HIDDEN";

                    synchronized (sMemoryPressureRecords) {
                        if (sMemoryPressureRecords.size() >= MAX_MEMORY_PRESSURE_RECORDS) {
                            sMemoryPressureRecords.remove(0);
                        }
                        sMemoryPressureRecords.add(new MemoryPressureRecord(now, level, javaUsed, javaMax, nativeHeap, lvlName));
                    }
                    Log.w(TAG, "Memory pressure trimmed: " + lvlName + ", javaUsed=" + (javaUsed / 1048576) + "MB");
                }

                @Override
                public void onConfigurationChanged(Configuration newConfig) {
                }

                @Override
                public void onLowMemory() {
                    onTrimMemory(TRIM_MEMORY_COMPLETE);
                }
            });
            Log.d(TAG, "ComponentCallbacks2 registered successfully");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to register ComponentCallbacks2", t);
        }
    }

    private static void startWatchdog() {
        if (sWatchdog == null || !sWatchdog.isAlive()) {
            sWatchdog = new WatchdogThread();
            sWatchdog.start();
        }
    }

    public static synchronized void pyInit(boolean trackScreens, boolean trackLags, int lagThresholdMs) {
        sTrackScreens = trackScreens;
        sTrackLags = trackLags;
        sLagThresholdMs = lagThresholdMs;

        if (sInitialized) {
            return;
        }
        sInitialized = true;

        updateKnownPlugins();
        hookBaseFragment();
        hookThreadStart();
        hookNotificationCenter();
        hookConnectionsManager();
        hookRunOnUIThread();
        installUncaughtExceptionHandler();
        startWatchdog();

        try {
            Class<?> appLoader = Class.forName("org.telegram.messenger.ApplicationLoader");
            Context ctx = (Context) appLoader.getField("applicationContext").get(null);
            registerComponentCallbacks(ctx);
        } catch (Throwable ignored) {
        }

        Log.i(TAG, "MaxTrace Full-Upgrade Java-layer loaded: screens=" + sTrackScreens + ", lags=" + sTrackLags);
    }

    public static synchronized void pyUpdateSettings(boolean trackScreens, boolean trackLags, int lagThresholdMs) {
        sTrackScreens = trackScreens;
        sTrackLags = trackLags;
        sLagThresholdMs = lagThresholdMs;
    }

    public static synchronized void pyStopWatchdog() {
        if (sWatchdog != null) {
            sWatchdog.stopWatchdog();
            sWatchdog = null;
        }
        sInitialized = false;
        Log.i(TAG, "MaxTrace stopped");
    }

    public static String pyGetThreadOwner(long tid) {
        String owner = sThreadOwners.get(tid);
        return owner != null ? owner : "";
    }

    public static String pyGetThreadStatsJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        boolean first = true;
        for (Map.Entry<Long, String> entry : sThreadOwners.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(entry.getKey()).append("\":\"").append(escapeJson(entry.getValue())).append("\"");
        }
        sb.append("}");
        return sb.toString();
    }

    public static String pyGetNotificationStatsJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        boolean first = true;
        for (Map.Entry<String, Integer> entry : sObserverCounts.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(escapeJson(entry.getKey())).append("\":").append(entry.getValue());
        }
        sb.append("}");
        return sb.toString();
    }

    public static String pyGetNotificationDetailsJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        boolean firstP = true;
        for (Map.Entry<String, Map<String, Integer>> pEntry : sPluginObserverDetails.entrySet()) {
            if (!firstP) sb.append(",");
            firstP = false;
            sb.append("\"").append(escapeJson(pEntry.getKey())).append("\":{");
            boolean firstE = true;
            for (Map.Entry<String, Integer> eEntry : pEntry.getValue().entrySet()) {
                if (eEntry.getValue() > 0) {
                    if (!firstE) sb.append(",");
                    firstE = false;
                    sb.append("\"").append(escapeJson(eEntry.getKey())).append("\":").append(eEntry.getValue());
                }
            }
            sb.append("}");
        }
        sb.append("}");
        return sb.toString();
    }

    public static String pyGetNetworkStatsJson() {
        long now = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        boolean first = true;
        for (Map.Entry<String, NetworkPluginStats> entry : sNetworkStats.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            NetworkPluginStats stats = entry.getValue();
            int reqLastMin = 0;
            synchronized (stats) {
                for (Long t : stats.requestTimestamps) {
                    if (now - t <= 60000) reqLastMin++;
                }
                sb.append("\"").append(escapeJson(entry.getKey())).append("\":{");
                sb.append("\"total\":").append(stats.totalRequests).append(",");
                sb.append("\"lastMinute\":").append(reqLastMin).append(",");
                sb.append("\"lastType\":\"").append(escapeJson(stats.lastRequestType)).append("\",");
                sb.append("\"lastTime\":").append(stats.lastRequestTime);
                sb.append("}");
            }
        }
        sb.append("}");
        return sb.toString();
    }

    public static void pyForceGC() {
        try {
            Runtime.getRuntime().gc();
            System.gc();
            Thread.sleep(50);
            Runtime.getRuntime().gc();
            System.gc();
        } catch (Throwable ignored) {
        }
    }

    public static String pyGetLeaksJson() {
        long now = System.currentTimeMillis();
        long leakThresholdMs = 10000;
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        boolean first = true;

        synchronized (sTrackedFragments) {
            Iterator<TrackedFragment> it = sTrackedFragments.iterator();
            while (it.hasNext()) {
                TrackedFragment tf = it.next();
                Object obj = tf.ref.get();
                if (obj == null) {
                    it.remove();
                    continue;
                }
                long elapsed = now - tf.destroyTime;
                if (elapsed >= leakThresholdMs) {
                    if (!first) sb.append(",");
                    first = false;
                    sb.append("{");
                    sb.append("\"className\":\"").append(escapeJson(tf.className)).append("\",");
                    sb.append("\"elapsedSec\":").append(elapsed / 1000).append(",");
                    sb.append("\"destroyTime\":").append(tf.destroyTime);
                    sb.append("}");
                }
            }
        }
        sb.append("]");
        return sb.toString();
    }

    public static String pyGetLagsJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        boolean first = true;

        synchronized (sLagRecords) {
            for (LagRecord r : sLagRecords) {
                if (!first) sb.append(",");
                first = false;
                sb.append("{");
                sb.append("\"timestamp\":").append(r.timestamp).append(",");
                sb.append("\"durationMs\":").append(r.durationMs).append(",");
                sb.append("\"culprit\":\"").append(escapeJson(r.culprit)).append("\",");
                sb.append("\"topStack\":\"").append(escapeJson(r.topStack)).append("\",");
                sb.append("\"fullStack\":\"").append(escapeJson(r.fullStack)).append("\"");
                sb.append("}");
            }
        }
        sb.append("]");
        return sb.toString();
    }

    public static String pyGetCrashesJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        boolean first = true;

        synchronized (sCrashRecords) {
            for (CrashRecord c : sCrashRecords) {
                if (!first) sb.append(",");
                first = false;
                sb.append("{");
                sb.append("\"timestamp\":").append(c.timestamp).append(",");
                sb.append("\"threadName\":\"").append(escapeJson(c.threadName)).append("\",");
                sb.append("\"culprit\":\"").append(escapeJson(c.culprit)).append("\",");
                sb.append("\"exceptionClass\":\"").append(escapeJson(c.exceptionClass)).append("\",");
                sb.append("\"message\":\"").append(escapeJson(c.message)).append("\",");
                sb.append("\"stackTrace\":\"").append(escapeJson(c.stackTrace)).append("\"");
                sb.append("}");
            }
        }
        sb.append("]");
        return sb.toString();
    }

    public static String pyGetMemoryPressureJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        boolean first = true;

        synchronized (sMemoryPressureRecords) {
            for (MemoryPressureRecord m : sMemoryPressureRecords) {
                if (!first) sb.append(",");
                first = false;
                sb.append("{");
                sb.append("\"timestamp\":").append(m.timestamp).append(",");
                sb.append("\"level\":").append(m.level).append(",");
                sb.append("\"levelName\":\"").append(escapeJson(m.levelName)).append("\",");
                sb.append("\"javaUsedBytes\":").append(m.javaUsedBytes).append(",");
                sb.append("\"javaMaxBytes\":").append(m.javaMaxBytes).append(",");
                sb.append("\"nativeHeapAllocatedBytes\":").append(m.nativeHeapAllocatedBytes);
                sb.append("}");
            }
        }
        sb.append("]");
        return sb.toString();
    }

    public static void pyClearCrashes() {
        sCrashRecords.clear();
    }

    public static void pyClearMemoryPressure() {
        sMemoryPressureRecords.clear();
    }

    public static void pyClearLags() {
        sLagRecords.clear();
    }

    public static void pyClearLeaks() {
        sTrackedFragments.clear();
    }

    public static String pyGetSummaryJson() {
        int leaksCount = 0;
        long now = System.currentTimeMillis();
        synchronized (sTrackedFragments) {
            for (TrackedFragment tf : sTrackedFragments) {
                if (tf.ref.get() != null && (now - tf.destroyTime) >= 10000) {
                    leaksCount++;
                }
            }
        }
        int lagsCount;
        synchronized (sLagRecords) {
            lagsCount = sLagRecords.size();
        }
        int crashesCount;
        synchronized (sCrashRecords) {
            crashesCount = sCrashRecords.size();
        }

        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"leaksCount\":").append(leaksCount).append(",");
        sb.append("\"lagsCount\":").append(lagsCount).append(",");
        sb.append("\"crashesCount\":").append(crashesCount).append(",");
        sb.append("\"networkTrackedPlugins\":").append(sNetworkStats.size()).append(",");
        sb.append("\"trackedScreensCount\":").append(sTrackedFragments.size()).append(",");
        sb.append("\"trackedThreadsCount\":").append(sThreadOwners.size());
        sb.append("}");
        return sb.toString();
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\b", "\\b")
                .replace("\f", "\\f")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
