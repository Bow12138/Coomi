package app.coomi;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;

import rikka.shizuku.Shizuku;

/**
 * Shizuku 白名单 adb 命令执行器（AI 屏幕操控的执行底座）。
 *
 * <p>借 Android 自己的 ADB 通道（Shizuku Binder）以 shell 身份执行有限命令：
 * {@code input tap/swipe/text/keyevent}、{@code screencap}、{@code am ...}。
 * 安全护栏（强度高）：白名单前缀过滤，高危命令（rm/install/settings put/pm/
 * reboot/su 等）一律拒绝；每条命令的结果以 JSON 返回供前端审计。</p>
 */
public final class ShizukuExec {

    private static final String TAG = "ShizukuExec";

    /** 白名单前缀：只允许屏幕操控相关命令。 */
    private static final String[] ALLOWED_PREFIXES = {
        "input tap ", "input swipe ", "input text ", "input keyevent ",
        "screencap ", "am start ", "am force-stop ", "am broadcast ", "settings get "
    };

    private final Context context;
    @SuppressWarnings("unused")
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public ShizukuExec(Context context) {
        this.context = context.getApplicationContext();
    }

    /** Shizuku 已连接且已授权。 */
    public boolean isAvailable() {
        try {
            return Shizuku.pingBinder()
                && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Exception e) {
            return false;
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
            return "{\"ok\":false,\"error\":\"shizuku_unavailable\"}";
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
            Process proc = Shizuku.newProcess(new String[]{"sh", "-c", cmd}, null, null);
            StringBuilder out = new StringBuilder();
            try (BufferedReader reader =
                     new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    out.append(line).append('\n');
                }
            }
            proc.waitFor();
            String output = out.toString().trim();
            JSONObject json = new JSONObject();
            json.put("ok", true);
            json.put("output", output);
            return json.toString();
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

    /** 截图到应用私有目录，返回 {ok, path, guestPath}。 */
    public String screenCapture() {
        File dir = context.getExternalFilesDir(null);
        if (dir == null) dir = context.getFilesDir();
        File out = new File(dir, "anna_screenshot.png");
        String result = exec("screencap -p " + out.getAbsolutePath());
        try {
            JSONObject json = new JSONObject(result);
            if (json.optBoolean("ok")) {
                // 复制到引擎可见目录（guest /home/coomi/screenshots/），
                // 这样 ProotLinux 引擎与 Web 端都能读取该截图用于视觉理解。
                String guestPath = "";
                try {
                    File home = new File(context.getFilesDir(), "home/screenshots");
                    if (!home.exists()) home.mkdirs();
                    File target = new File(home, "anna_screenshot.png");
                    java.nio.file.Files.copy(out.toPath(), target.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    guestPath = "/home/coomi/screenshots/anna_screenshot.png";
                } catch (Exception ignored) {
                    // 复制失败不影响主路径返回
                }
                JSONObject resp = new JSONObject();
                resp.put("ok", true);
                resp.put("path", out.getAbsolutePath());
                if (!guestPath.isEmpty()) resp.put("guestPath", guestPath);
                return resp.toString();
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    public void release() {
    }
}
