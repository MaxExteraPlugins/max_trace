package com.exteraplugins.MaxTrace;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

public class Main {
    public static final String TAG = "MaxTrace";
    public static final String VERSION = "1.1.2";
    public static final int VERSION_CODE = 10;

    private static volatile boolean sTrackScreens = true;
    private static volatile boolean sTrackLags = true;
    private static volatile int sLagThresholdMs = 500;
    private static volatile boolean sInitialized = false;

    private static WatchdogThread sWatchdog = null;
    private static final List<TrackedFragment> sTrackedFragments = Collections.synchronizedList(new ArrayList<TrackedFragment>());
    private static final List<LagRecord> sLagRecords = Collections.synchronizedList(new ArrayList<LagRecord>());
    private static final int MAX_LAG_RECORDS = 50;

    // Thread ID -> Plugin ID attribution registry (replaces removed native Block#3E)
    private static final Map<Long, String> sThreadOwners = new ConcurrentHashMap<Long, String>();

    // Plugin ID -> Active NotificationCenter Observers count
    private static final Map<String, Integer> sObserverCounts = new ConcurrentHashMap<String, Integer>();

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
                    int maxLines = Math.min(stack.length, 25);
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
                        Log.w(TAG, "UI Stall: " + totalDuration + "ms, culprit: " + culprit);
                    }
                }
            }
        }
    }

    public static String detectCulprit(StackTraceElement[] stack) {
        if (stack == null) return "Unknown";
        for (StackTraceElement elem : stack) {
            String cls = elem.getClassName();
            if (cls.startsWith("com.exteraplugins.")) {
                String[] parts = cls.split("\\.");
                if (parts.length >= 3) {
                    return "Plugin: " + parts[2];
                }
                return "Plugin: " + cls;
            }
            if (cls.contains("chaquo") || cls.contains("python")) {
                return "Python Plugin Hook (" + elem.getMethodName() + ")";
            }
            if (cls.contains("xposed") || cls.contains("XposedBridge")) {
                return "Xposed Hook";
            }
            if (cls.contains("SQLite") || cls.contains("database")) {
                return "Database I/O on UI thread";
            }
        }
        if (stack.length > 0) {
            return "Host UI: " + stack[0].getClassName() + "." + stack[0].getMethodName();
        }
        return "Unknown";
    }

    private static void addLagRecord(LagRecord record) {
        synchronized (sLagRecords) {
            if (sLagRecords.size() >= MAX_LAG_RECORDS) {
                sLagRecords.remove(0);
            }
            sLagRecords.add(record);
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

        hookBaseFragment();
        hookThreadStart();
        hookNotificationCenter();
        startWatchdog();

        Log.i(TAG, "MaxTrace Java-layer loaded: trackScreens=" + sTrackScreens + ", trackLags=" + sTrackLags);
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
                        if (culprit != null && culprit.startsWith("Plugin: ")) {
                            sThreadOwners.put(target.getId(), culprit.substring(8));
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

    private static void hookNotificationCenter() {
        try {
            Class<?> ncClass = Class.forName("org.telegram.messenger.NotificationCenter");
            for (Method m : ncClass.getDeclaredMethods()) {
                String name = m.getName();
                if ("addObserver".equals(name)) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        public void afterHookedMethod(MethodHookParam param) {
                            try {
                                StackTraceElement[] stack = Thread.currentThread().getStackTrace();
                                String culprit = detectCulprit(stack);
                                if (culprit != null && culprit.startsWith("Plugin: ")) {
                                    String plugin = culprit.substring(8);
                                    Integer cur = sObserverCounts.get(plugin);
                                    sObserverCounts.put(plugin, cur == null ? 1 : cur + 1);
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    });
                } else if ("removeObserver".equals(name)) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        public void afterHookedMethod(MethodHookParam param) {
                            try {
                                StackTraceElement[] stack = Thread.currentThread().getStackTrace();
                                String culprit = detectCulprit(stack);
                                if (culprit != null && culprit.startsWith("Plugin: ")) {
                                    String plugin = culprit.substring(8);
                                    Integer cur = sObserverCounts.get(plugin);
                                    if (cur != null && cur > 0) {
                                        sObserverCounts.put(plugin, cur - 1);
                                    }
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    });
                }
            }
            Log.d(TAG, "Hooked NotificationCenter observers");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook NotificationCenter", t);
        }
    }

    private static void startWatchdog() {
        if (sWatchdog == null || !sWatchdog.isAlive()) {
            sWatchdog = new WatchdogThread();
            sWatchdog.start();
        }
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

        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"leaksCount\":").append(leaksCount).append(",");
        sb.append("\"lagsCount\":").append(lagsCount).append(",");
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
