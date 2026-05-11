package com.autonavi.companion;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class LogMonitor {
    private static final String TAG = "AmapCompanion";

    private static final String AMAP_PACKAGE = "com.autonavi.amap";

    private static final Pattern NAV_START_PATTERN = Pattern.compile(
            "(?i)(start.*navi|navi.*start|begin.*navigation|navigation.*begin|进入导航|开启导航)");
    private static final Pattern NAV_END_PATTERN = Pattern.compile(
            "(?i)(end.*navi|navi.*end|exit.*navigation|navigation.*exit|退出导航|导航.*结束|导航.*退出|结束导航)");

    public enum NavigationState {
        UNKNOWN,
        NAVIGATING,
        IDLE
    }

    public interface LogCallback {
        void onNavigationStateChanged(NavigationState newState, String logLine);
        void onLogLine(String logLine);
    }

    private volatile boolean isRunning = false;
    private volatile NavigationState currentState = NavigationState.UNKNOWN;
    private Thread logcatThread;
    private Process logcatProcess;
    private LogCallback callback;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<String> targetPackages = new ArrayList<>();

    public LogMonitor() {
        targetPackages.add(AMAP_PACKAGE);
    }

    public void addTargetPackage(String packageName) {
        if (packageName != null && !packageName.isEmpty()) {
            synchronized (targetPackages) {
                if (!targetPackages.contains(packageName)) {
                    targetPackages.add(packageName);
                }
            }
        }
    }

    public void setCallback(LogCallback callback) {
        this.callback = callback;
    }

    public void start() {
        if (isRunning) {
            Log.d(TAG, "LogMonitor already running");
            return;
        }
        isRunning = true;
        currentState = NavigationState.UNKNOWN;
        logcatThread = new Thread(new Runnable() {
            @Override
            public void run() {
                readLogcat();
            }
        }, "LogMonitor-Thread");
        logcatThread.setDaemon(true);
        logcatThread.start();
        Log.d(TAG, "LogMonitor started");
    }

    public void stop() {
        if (!isRunning) {
            return;
        }
        isRunning = false;
        if (logcatProcess != null) {
            logcatProcess.destroy();
            logcatProcess = null;
        }
        if (logcatThread != null) {
            try {
                logcatThread.interrupt();
                logcatThread.join(3000);
            } catch (InterruptedException e) {
                Log.e(TAG, "stop logcat thread interrupted", e);
            }
            logcatThread = null;
        }
        Log.d(TAG, "LogMonitor stopped");
    }

    public boolean isRunning() {
        return isRunning;
    }

    public NavigationState getCurrentState() {
        return currentState;
    }

    private void readLogcat() {
        BufferedReader reader = null;
        try {
            String[] cmd = buildLogcatCommand();
            Log.d(TAG, "starting logcat: " + java.util.Arrays.toString(cmd));
            logcatProcess = Runtime.getRuntime().exec(cmd);
            reader = new BufferedReader(new InputStreamReader(logcatProcess.getInputStream()));

            String line;
            while (isRunning && (line = reader.readLine()) != null) {
                if (!isRunning) {
                    break;
                }
                processLogLine(line);
            }
        } catch (Exception e) {
            Log.e(TAG, "readLogcat error", e);
        } finally {
            try {
                if (reader != null) {
                    reader.close();
                }
            } catch (Exception e) {
                Log.e(TAG, "close reader error", e);
            }
        }
    }

    private String[] buildLogcatCommand() {
        StringBuilder sb = new StringBuilder("logcat -v time *:S ");
        synchronized (targetPackages) {
            for (String pkg : targetPackages) {
                sb.append(pkg).append(":D ");
            }
        }
        sb.append("AutoNavi:D AMAP:D Nav:D ");
        return new String[]{"sh", "-c", sb.toString()};
    }

    private void processLogLine(final String line) {
        if (line == null || line.isEmpty()) {
            return;
        }

        Log.d(TAG, "logcat line: " + line);

        if (callback != null) {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (callback != null) {
                        callback.onLogLine(line);
                    }
                }
            });
        }

        if (NAV_START_PATTERN.matcher(line).find()) {
            if (currentState != NavigationState.NAVIGATING) {
                currentState = NavigationState.NAVIGATING;
                Log.d(TAG, "Navigation started detected");
                if (callback != null) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (callback != null) {
                                callback.onNavigationStateChanged(NavigationState.NAVIGATING, line);
                            }
                        }
                    });
                }
            }
        } else if (NAV_END_PATTERN.matcher(line).find()) {
            if (currentState != NavigationState.IDLE) {
                currentState = NavigationState.IDLE;
                Log.d(TAG, "Navigation ended detected");
                if (callback != null) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (callback != null) {
                                callback.onNavigationStateChanged(NavigationState.IDLE, line);
                            }
                        }
                    });
                }
            }
        }
    }

    public void executeLogcatOnce(final LogcatResultCallback callback) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                Process process = null;
                BufferedReader reader = null;
                StringBuilder output = new StringBuilder();
                try {
                    StringBuilder cmdBuilder = new StringBuilder("logcat -v time -d *:S ");
                    synchronized (targetPackages) {
                        for (String pkg : targetPackages) {
                            cmdBuilder.append(pkg).append(":D ");
                        }
                    }
                    cmdBuilder.append("AutoNavi:D AMAP:D Nav:D ");
                    String[] cmd = new String[]{"sh", "-c", cmdBuilder.toString()};
                    process = Runtime.getRuntime().exec(cmd);
                    reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        output.append(line).append("\n");
                    }
                    process.waitFor();
                    if (callback != null) {
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                if (callback != null) {
                                    callback.onResult(output.toString());
                                }
                            }
                        });
                    }
                } catch (Exception e) {
                    Log.e(TAG, "executeLogcatOnce error", e);
                    if (callback != null) {
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                if (callback != null) {
                                    callback.onError(e.getMessage());
                                }
                            }
                        });
                    }
                } finally {
                    try {
                        if (reader != null) reader.close();
                        if (process != null) process.destroy();
                    } catch (Exception e) {
                        Log.e(TAG, "cleanup error", e);
                    }
                }
            }
        }).start();
    }

    public interface LogcatResultCallback {
        void onResult(String output);
        void onError(String error);
    }
}
