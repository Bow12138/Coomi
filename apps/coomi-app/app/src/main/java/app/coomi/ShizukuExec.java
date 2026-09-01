package app.coomi;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.Parcel;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import rikka.shizuku.Shizuku;

/**
 * Shizuku 白名单 adb 命令执行器（AI 屏幕操控的执行底座）。
 *
 * <p>借 Shizuku 以 shell（ADB）身份执行有限命令：{@code input tap/swipe/text/
 * keyevent}、{@code screencap}、{@code am ...}。命令在 {@link ShellUserService}
 * （Shizuku UserService，独立进程，shell uid）内运行。</p>
 *
 * <p>安全护栏（强度高）：白名单前缀过滤，高危命令（rm/install/settings put/pm/
 * reboot/su 等）一律拒绝；每次执行结果以 JSON 返回供前端审计。</p>
 */
public final class ShizukuExec {

    private static final String TAG = "ShizukuExec";

    /** 白名单前缀：只允许屏幕操控相关命令。 */
    private static final String[] ALLOWED_PREFIXES = {
        "input tap ", "input swipe ", "input text ", "input keyevent ",
        "screencap ", "am start ", "am force-stop ", "am broadcast ", "settings get "
    };

    private final Context context;
    /** 已绑定的 UserService Binder（volatile，跨线程可见）。 */
    private volatile IBinder sBinder;

    public ShizukuExec(Context context) {
        this.context = context.getApplicationContext();
    }

    /** Shizuku 服务是否在运行（binder 存活）。 */
    public boolean isServiceRunning() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Shizuku 是否已授权（服务在运行 + 权限已授予）。 */
    public boolean isAvailable() {
        try {
            return Shizuku.pingBinder()
                && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 授权状态诊断：返回 service_down / not_permitted / granted。 */
    public String status() {
        try {
            if (!Shizuku.pingBinder()) return "service_down";
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                return "not_permitted";
            }
            return "granted";
        } catch (Throwable t) {
            return "service_down";
        }
    }

    /** 请求 Shizuku 授权（系统弹窗）。 */
    public void requestPermission() {
        try {
            Shizuku.requestPermission(2302);
        } catch (Exception e) {
            Log.e(TAG, "requestPermission failed", e);
        }
    }

    /**
     * 执行一条白名单 adb 命令，返回 JSON：{ok, output|error}。
     * 白名单外命令直接拒绝（安全护栏）。
     */
    public String exec(String command) {
        if (command == null || command.isEmpty()) {
            return "{\"ok\":false,\"error\":\"empty_command\"}";
        }
        if (!isAvailable()) {
            return "{\"ok\":false,\"error\":\"" + status() + "\"}";
        }
        String cmd = command.trim();
        boolean allowed = false;
        for (String prefix : ALLOWED_PREFIXES) {
            if (cmd.startsWith(prefix)) {
                allowed = true;
                break;
            }
        }
        if (!allowed) {
            return "{\"ok\":false,\"error\":\"command_not_allowed\"}";
        }
        try {
            IBinder binder = getBinder();
            if (binder == null) {
                return "{\"ok\":false,\"error\":\"user_service_bind_failed\"}";
            }
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(ShellUserService.DESCRIPTOR);
                data.writeString(cmd);
                boolean ok = binder.transact(ShellUserService.CMD_EXEC, data, reply, 0);
                if (!ok) {
                    return "{\"ok\":false,\"error\":\"transact_failed\"}";
                }
                reply.readException();
                String output = reply.readString();
                JSONObject json = new JSONObject();
                json.put("ok", true);
                json.put("output", output == null ? "" : output);
                return json.toString();
            } finally {
                data.recycle();
                reply.recycle();
            }
        } catch (Exception e) {
            Log.e(TAG, "exec failed: " + cmd, e);
            JSONObject json = new JSONObject();
            try {
                json.put("ok", false);
                json.put("error", e.getMessage() == null ? "exec_failed" : e.getMessage());
            } catch (Exception ignored) {
            }
            return json.toString();
        }
    }

    /** 获取（必要时绑定）UserService Binder，限时等待 5s。 */
    private IBinder getBinder() {
        IBinder binder = sBinder;
        if (binder != null && binder.pingBinder()) return binder;
        sBinder = null;
        final CountDownLatch latch = new CountDownLatch(1);
        ComponentName cn = new ComponentName(context, ShellUserService.class);
        Shizuku.UserServiceArgs args = new Shizuku.UserServiceArgs(cn)
            .daemon(false)
            .tag("coomi-shell");
        try {
            Shizuku.bindUserService(args, new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName name, IBinder service) {
                    sBinder = service;
                    latch.countDown();
                }

                @Override
                public void onServiceDisconnected(ComponentName name) {
                    sBinder = null;
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "bindUserService failed", e);
            return null;
        }
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                Log.e(TAG, "bindUserService timeout");
                return null;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        return sBinder;
    }

    /**
     * 截图（screencap 到 /sdcard，再复制到 app 目录与引擎可见目录），
     * 返回 {ok, path, guestPath}。截图失败时返回原始错误。
     */
    public String screenCapture() {
        // shell 身份无法写 app 私有目录，screencap 输出到 /sdcard（targetSdk 28 legacy 可读写）。
        String remote = "/sdcard/anna_screenshot.png";
        String result = exec("screencap -p " + remote);
        try {
            JSONObject json = new JSONObject(result);
            if (json.optBoolean("ok")) {
                File src = new File(remote);
                if (!src.exists() || src.length() == 0) {
                    return "{\"ok\":false,\"error\":\"screenshot_file_missing\"}";
                }
                String guestPath = "";
                String localPath = "";
                try {
                    // 1) app 私有目录副本（Web 端可直接读）
                    File dir = context.getExternalFilesDir(null);
                    if (dir == null) dir = context.getFilesDir();
                    File local = new File(dir, "anna_screenshot.png");
                    java.nio.file.Files.copy(src.toPath(), local.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    localPath = local.getAbsolutePath();
                    // 2) 引擎可见目录（guest /home/coomi/screenshots/）
                    File home = new File(context.getFilesDir(), "home/screenshots");
                    if (!home.exists()) home.mkdirs();
                    File target = new File(home, "anna_screenshot.png");
                    java.nio.file.Files.copy(src.toPath(), target.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    guestPath = "/home/coomi/screenshots/anna_screenshot.png";
                } catch (Exception e) {
                    Log.e(TAG, "copy screenshot failed", e);
                }
                JSONObject resp = new JSONObject();
                resp.put("ok", true);
                resp.put("path", localPath);
                resp.put("remotePath", remote);
                if (!guestPath.isEmpty()) resp.put("guestPath", guestPath);
                return resp.toString();
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    public void release() {
        IBinder binder = sBinder;
        sBinder = null;
        if (binder != null && binder.pingBinder()) {
            try {
                Parcel data = Parcel.obtain();
                data.writeInterfaceToken(ShellUserService.DESCRIPTOR);
                binder.transact(ShellUserService.CMD_DESTROY, data, null, 0);
                data.recycle();
            } catch (Exception ignored) {
            }
        }
    }
}
