package com.autonavi.companion;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStreamReader;

public class AdbPermissionHelper {
    private static final String TAG = "AmapCompanion";
    private static final String READ_LOGS_PERMISSION = "android.permission.READ_LOGS";
    private static final String APP_PACKAGE = "com.autonavi.companion";

    public interface PermissionCallback {
        void onResult(boolean granted, String message);
    }

    public static void grantReadLogsPermissionAsync(final Context context, final PermissionCallback callback) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String pkg = context != null ? context.getPackageName() : APP_PACKAGE;
                    String[] commands = {
                        "pm grant " + pkg + " " + READ_LOGS_PERMISSION
                    };
                    String result = executeShellCommand(commands);
                    if (result != null && result.toLowerCase().contains("exception")) {
                        Log.e(TAG, "grant READ_LOGS failed: " + result);
                        if (callback != null) {
                            callback.onResult(false, "授权失败: " + result.trim());
                        }
                        return;
                    }
                    boolean hasPermission = checkReadLogsPermission(context);
                    Log.d(TAG, "grant READ_LOGS result: " + hasPermission);
                    if (callback != null) {
                        callback.onResult(hasPermission,
                                hasPermission ? "授权成功" : "授权失败，请手动通过ADB执行:\npm grant " + pkg + " android.permission.READ_LOGS");
                    }
                } catch (Exception e) {
                    Log.e(TAG, "grantReadLogsPermission error", e);
                    if (callback != null) {
                        callback.onResult(false, "授权异常: " + e.getMessage());
                    }
                }
            }
        }).start();
    }

    public static boolean checkReadLogsPermission() {
        return checkReadLogsPermission(null);
    }

    public static boolean checkReadLogsPermission(Context context) {
        if (context == null) {
            return false;
        }
        try {
            PackageManager pm = context.getPackageManager();
            String pkg = context.getPackageName();
            int result = pm.checkPermission(READ_LOGS_PERMISSION, pkg);
            return result == PackageManager.PERMISSION_GRANTED;
        } catch (Exception e) {
            Log.e(TAG, "checkReadLogsPermission error", e);
            return false;
        }
    }

    public static String executeShellCommand(String[] commands) {
        Process process = null;
        DataOutputStream os = null;
        BufferedReader reader = null;
        StringBuilder result = new StringBuilder();
        try {
            process = Runtime.getRuntime().exec("su");
            os = new DataOutputStream(process.getOutputStream());
            reader = new BufferedReader(new InputStreamReader(process.getInputStream()));

            if (commands != null) {
                for (String command : commands) {
                    if (command != null) {
                        os.writeBytes(command + "\n");
                        os.flush();
                    }
                }
            }
            os.writeBytes("exit\n");
            os.flush();

            String line;
            while ((line = reader.readLine()) != null) {
                result.append(line).append("\n");
            }

            int exitCode = process.waitFor();
            Log.d(TAG, "shell command exit code: " + exitCode);

            if (exitCode != 0 && result.length() == 0) {
                BufferedReader errorReader = new BufferedReader(
                        new InputStreamReader(process.getErrorStream()));
                String errorLine;
                while ((errorLine = errorReader.readLine()) != null) {
                    result.append(errorLine).append("\n");
                }
                errorReader.close();
            }
        } catch (Exception e) {
            Log.e(TAG, "executeShellCommand error", e);
            result.append("Error: ").append(e.getMessage());
        } finally {
            try {
                if (os != null) os.close();
                if (reader != null) reader.close();
                if (process != null) process.destroy();
            } catch (Exception e) {
                Log.e(TAG, "cleanup error", e);
            }
        }
        return result.toString();
    }

    public static String executeAdbCommand(Context context, String command) {
        if (context == null || command == null) {
            return "";
        }
        Log.d(TAG, "executeAdbCommand: " + command);
        return executeShellCommand(new String[]{command});
    }

    public static boolean hasReadLogsPermission(Context context) {
        if (context == null) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                java.lang.reflect.Method method = android.app.AppOpsManager.class.getMethod(
                        "checkOpNoThrow",
                        int.class, int.class, String.class);
                android.app.AppOpsManager appOps = (android.app.AppOpsManager)
                        context.getSystemService(Context.APP_OPS_SERVICE);
                if (appOps != null) {
                    int mode = (Integer) method.invoke(appOps,
                            43,
                            android.os.Process.myUid(),
                            context.getPackageName());
                    return mode == android.app.AppOpsManager.MODE_ALLOWED;
                }
            } catch (Exception e) {
                Log.e(TAG, "hasReadLogsPermission reflection error", e);
            }
        }
        return checkReadLogsPermission(context);
    }
}
