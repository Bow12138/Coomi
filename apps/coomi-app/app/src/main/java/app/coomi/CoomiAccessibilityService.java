package app.coomi;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.io.File;
import java.io.FileOutputStream;
import java.util.List;

/**
 * CoomiDev accessibility service.
 *
 * <p>Provides an opt-in automation channel for the Coomi engine:
 * <ul>
 *   <li>{@code click} / {@code longClick} / {@code swipe} via
 *       {@link #dispatchGesture} (Android 7.0+).</li>
 *   <li>{@code back} / {@code home} / {@code recents} / {@code notifications}
 *       via {@link #performGlobalAction}.</li>
 *   <li>{@code dump} returns a compact JSON snapshot of the current window
 *       hierarchy (view ids, text, clickable bounds) so the engine can decide
 *       what to tap.</li>
 * </ul>
 *
 * <p>Commands arrive as {@link #ACTION_COMMAND} broadcasts with a
 * {@code command} extra; the service also listens to the system
 * {@link Intent#ACTION_CLOSE_SYSTEM_DIALOGS} to stay responsive. Results are
 * reported through {@link #ACTION_RESULT} broadcasts so the caller (engine or
 * shell) can await them without a bound connection.</p>
 */
public final class CoomiAccessibilityService extends AccessibilityService {

    private static final String TAG = "CoomiA11y";

    /** Broadcast action used to send commands to this service. */
    public static final String ACTION_COMMAND = "app.coomi.ACCESSIBILITY_COMMAND";
    /** Broadcast action used to publish command results. */
    public static final String ACTION_RESULT = "app.coomi.ACCESSIBILITY_RESULT";

    public static final String EXTRA_COMMAND = "command";
    public static final String EXTRA_X = "x";
    public static final String EXTRA_Y = "y";
    public static final String EXTRA_X2 = "x2";
    public static final String EXTRA_Y2 = "y2";
    public static final String EXTRA_DURATION = "duration";
    public static final String EXTRA_RESULT = "result";

    private static volatile CoomiAccessibilityService sInstance;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final BroadcastReceiver commandReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String command = intent.getStringExtra(EXTRA_COMMAND);
            if (command == null) return;
            handleCommand(command, intent);
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        IntentFilter filter = new IntentFilter(ACTION_COMMAND);
        filter.addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        registerReceiver(commandReceiver, filter);
        sInstance = this;
        Log.i(TAG, "service connected");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Event monitoring only; all work is command-driven.
    }

    @Override
    public void onInterrupt() {
        Log.i(TAG, "service interrupted");
    }

    @Override
    public void onDestroy() {
        sInstance = null;
        try {
            unregisterReceiver(commandReceiver);
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }

    private void handleCommand(String command, Intent intent) {
        switch (command) {
            case "back":
                performGlobalAction(GLOBAL_ACTION_BACK);
                break;
            case "home":
                performGlobalAction(GLOBAL_ACTION_HOME);
                break;
            case "recents":
                performGlobalAction(GLOBAL_ACTION_RECENTS);
                break;
            case "notifications":
                performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS);
                break;
            case "click":
                doTap(intent.getIntExtra(EXTRA_X, 0), intent.getIntExtra(EXTRA_Y, 0), 60);
                break;
            case "longClick":
                doTap(intent.getIntExtra(EXTRA_X, 0), intent.getIntExtra(EXTRA_Y, 0),
                    intent.getIntExtra(EXTRA_DURATION, 800));
                break;
            case "swipe": {
                int x = intent.getIntExtra(EXTRA_X, 0);
                int y = intent.getIntExtra(EXTRA_Y, 0);
                int x2 = intent.getIntExtra(EXTRA_X2, 0);
                int y2 = intent.getIntExtra(EXTRA_Y2, 0);
                int duration = intent.getIntExtra(EXTRA_DURATION, 300);
                doSwipe(x, y, x2, y2, duration);
                break;
            }
            case "dump": {
                String json = dumpWindow();
                sendResult(json);
                break;
            }
            case "find": {
                String text = intent.getStringExtra("text");
                sendResult(findByText(text));
                break;
            }
            default:
                Log.w(TAG, "unknown command: " + command);
        }
    }

    private void doTap(int x, int y, int duration) {
        if (x <= 0 || y <= 0) {
            sendResult("{\"ok\":false,\"error\":\"invalid coordinates\"}");
            return;
        }
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.StrokeDescription stroke =
            new GestureDescription.StrokeDescription(path, 0, duration);
        GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
        boolean dispatched = dispatchGesture(gesture, new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                sendResult("{\"ok\":true,\"op\":\"click\",\"x\":" + x + ",\"y\":" + y + "}");
            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                sendResult("{\"ok\":false,\"error\":\"gesture cancelled\"}");
            }
        }, handler);
        if (!dispatched) {
            sendResult("{\"ok\":false,\"error\":\"dispatchGesture rejected\"}");
        }
    }

    private void doSwipe(int x1, int y1, int x2, int y2, int duration) {
        Path path = new Path();
        path.moveTo(x1, y1);
        path.lineTo(x2, y2);
        GestureDescription.StrokeDescription stroke =
            new GestureDescription.StrokeDescription(path, 0, Math.max(50, duration));
        GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
        boolean dispatched = dispatchGesture(gesture, new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                sendResult("{\"ok\":true,\"op\":\"swipe\"}");
            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                sendResult("{\"ok\":false,\"error\":\"gesture cancelled\"}");
            }
        }, handler);
        if (!dispatched) {
            sendResult("{\"ok\":false,\"error\":\"dispatchGesture rejected\"}");
        }
    }

    /** Returns a compact JSON snapshot of the active window. */
    private String dumpWindow() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return "{\"ok\":true,\"nodes\":[]}";
            JSONArray nodes = new JSONArray();
            collectNodes(root, nodes, 0, 64);
            root.recycle();
            JSONObject out = new JSONObject();
            out.put("ok", true);
            out.put("nodes", nodes);
            return out.toString();
        } catch (Exception e) {
            return "{\"ok\":false,\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    private void collectNodes(AccessibilityNodeInfo node, JSONArray out, int depth, int maxDepth) {
        if (node == null || depth > maxDepth) return;
        try {
            JSONObject item = new JSONObject();
            CharSequence text = node.getText();
            CharSequence desc = node.getContentDescription();
            if (text != null && text.length() > 0) item.put("text", text.toString());
            if (desc != null && desc.length() > 0) item.put("desc", desc.toString());
            String id = node.getViewIdResourceName();
            if (id != null) item.put("id", id);
            if (node.isClickable()) item.put("clickable", true);
            if (node.isScrollable()) item.put("scrollable", true);
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            if (!bounds.isEmpty()) {
                JSONObject b = new JSONObject();
                b.put("x", bounds.left);
                b.put("y", bounds.top);
                b.put("w", bounds.width());
                b.put("h", bounds.height());
                item.put("bounds", b);
            }
            if (item.length() > 0) out.put(item);
        } catch (Exception ignored) {
        }
        List<AccessibilityNodeInfo> children = new ArrayList<>();
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) children.add(child);
        }
        for (AccessibilityNodeInfo child : children) {
            collectNodes(child, out, depth + 1, maxDepth);
            child.recycle();
        }
    }

    private String findByText(String text) {
        try {
            if (text == null || text.isEmpty()) return "{\"ok\":false,\"error\":\"empty text\"}";
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return "{\"ok\":true,\"found\":false}";
            JSONArray out = new JSONArray();
            findNodes(root, text, out, 0);
            root.recycle();
            JSONObject result = new JSONObject();
            result.put("ok", true);
            result.put("found", out.length() > 0);
            result.put("nodes", out);
            return result.toString();
        } catch (Exception e) {
            return "{\"ok\":false,\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    private void findNodes(AccessibilityNodeInfo node, String text, JSONArray out, int depth) {
        if (node == null || depth > 40) return;
        try {
            CharSequence nodeText = node.getText();
            if (nodeText != null && nodeText.toString().contains(text)) {
                JSONObject item = new JSONObject();
                item.put("text", nodeText.toString());
                Rect bounds = new Rect();
                node.getBoundsInScreen(bounds);
                JSONObject b = new JSONObject();
                b.put("x", bounds.left + bounds.width() / 2);
                b.put("y", bounds.top + bounds.height() / 2);
                b.put("w", bounds.width());
                b.put("h", bounds.height());
                item.put("center", b);
                out.put(item);
            }
        } catch (Exception ignored) {
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                findNodes(child, text, out, depth + 1);
                child.recycle();
            }
        }
    }


    // ==================== 静态接口（供 CoomiActivity JS 桥调用） ====================

    public static boolean isConnected() {
        return sInstance != null;
    }

    /**
     * 通过无障碍通道截图（API 30+，Shizuku screencap 不可用时的降级路径）。
     * 返回 {ok, path}。
     */
    public static String takeScreenshot(String dir) {
        CoomiAccessibilityService svc = sInstance;
        if (svc == null) return "{\"ok\":false,\"error\":\"accessibility_disabled\"}";
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return "{\"ok\":false,\"error\":\"requires_api_30\"}";
        }
        return svc.captureScreenshot(dir);
    }

    private String captureScreenshot(String dir) {
        final java.util.concurrent.CountDownLatch latch =
            new java.util.concurrent.CountDownLatch(1);
        final String[] result = {"{\"ok\":false,\"error\":\"timeout\"}"};
        try {
            takeScreenshot(android.view.Display.DEFAULT_DISPLAY, r -> handler.post(r),
                new android.accessibilityservice.AccessibilityService.TakeScreenshotCallback() {
                    @Override
                    public void onSuccess(
                        android.accessibilityservice.AccessibilityService.ScreenshotResult screenshot) {
                        try {
                            android.hardware.HardwareBuffer hb = screenshot.getHardwareBuffer();
                            android.graphics.Bitmap bmp =
                                android.graphics.Bitmap.wrapHardwareBuffer(hb, screenshot.getColorSpace());
                            if (bmp == null) {
                                result[0] = "{\"ok\":false,\"error\":\"bitmap_null\"}";
                            } else {
                                File out = new File(dir, "anna_screenshot.png");
                                FileOutputStream fos = new FileOutputStream(out);
                                try {
                                    bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, fos);
                                } finally {
                                    fos.close();
                                }
                                result[0] = "{\"ok\":true,\"path\":\"" + out.getAbsolutePath() + "\"}";
                            }
                        } catch (Exception e) {
                            result[0] = "{\"ok\":false,\"error\":\"" + e.getMessage() + "\"}";
                        } finally {
                            latch.countDown();
                        }
                    }

                    @Override
                    public void onFailure(int errorCode) {
                        result[0] = "{\"ok\":false,\"error\":\"screenshot_failed_" + errorCode + "\"}";
                        latch.countDown();
                    }
                });
            latch.await(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            result[0] = "{\"ok\":false,\"error\":\"" + e.getMessage() + "\"}";
        }
        return result[0];
    }

    /**
     * 执行无障碍操作（tap/swipe/back/home/input_text）。
     * 与 handleCommand 的广播路径等价，但同步返回结果 JSON。
     */
    public static String performAction(String action, float x, float y, float x2, float y2, String text) {
        CoomiAccessibilityService svc = sInstance;
        if (svc == null) return "{\"ok\":false,\"error\":\"accessibility_disabled\"}";
        try {
            if ("back".equals(action)) {
                return svc.performGlobalAction(GLOBAL_ACTION_BACK)
                    ? "{\"ok\":true,\"op\":\"back\"}"
                    : "{\"ok\":false,\"error\":\"global_action_failed\"}";
            }
            if ("home".equals(action)) {
                return svc.performGlobalAction(GLOBAL_ACTION_HOME)
                    ? "{\"ok\":true,\"op\":\"home\"}"
                    : "{\"ok\":false,\"error\":\"global_action_failed\"}";
            }
            if ("tap".equals(action) || "click".equals(action)) {
                svc.doTap((int) x, (int) y, 60);
                return "{\"ok\":true,\"op\":\"tap\",\"x\":" + (int) x + ",\"y\":" + (int) y + "}";
            }
            if ("longClick".equals(action)) {
                svc.doTap((int) x, (int) y, 800);
                return "{\"ok\":true,\"op\":\"longClick\"}";
            }
            if ("swipe".equals(action)) {
                svc.doSwipe((int) x, (int) y, (int) x2, (int) y2, 300);
                return "{\"ok\":true,\"op\":\"swipe\"}";
            }
            if ("input_text".equals(action)) {
                return svc.setTextOnEditable(text == null ? "" : text);
            }
            return "{\"ok\":false,\"error\":\"unknown_action\"}";
        } catch (Exception e) {
            return "{\"ok\":false,\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    /** 读取当前窗口控件树 JSON（截图+视觉理解的补充通道）。 */
    public static String dumpHierarchy() {
        CoomiAccessibilityService svc = sInstance;
        if (svc == null) return "{\"ok\":false,\"error\":\"accessibility_disabled\"}";
        return svc.dumpWindow();
    }

    /** 向当前可编辑节点写入文本（无障碍 ACTION_SET_TEXT）。 */
    private String setTextOnEditable(String text) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return "{\"ok\":false,\"error\":\"no_window\"}";
        AccessibilityNodeInfo target = findEditable(root);
        if (target == null) {
            root.recycle();
            return "{\"ok\":false,\"error\":\"no_editable_node\"}";
        }
        android.os.Bundle args = new android.os.Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        boolean done = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        target.recycle();
        root.recycle();
        return done
            ? "{\"ok\":true,\"op\":\"input_text\"}"
            : "{\"ok\":false,\"error\":\"set_text_failed\"}";
    }

    private AccessibilityNodeInfo findEditable(AccessibilityNodeInfo node) {
        if (node == null) return null;
        try {
            if (node.isEditable()) return node;
        } catch (Exception ignored) {
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                AccessibilityNodeInfo found = findEditable(child);
                if (found != null) return found;
            }
        }
        return null;
    }

    private void sendResult(String json) {
        Intent result = new Intent(ACTION_RESULT);
        result.setPackage(getPackageName());
        result.putExtra(EXTRA_RESULT, json);
        sendBroadcast(result);
    }
}
