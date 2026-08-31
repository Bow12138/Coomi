package app.coomi;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
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

    private void sendResult(String json) {
        Intent result = new Intent(ACTION_RESULT);
        result.setPackage(getPackageName());
        result.putExtra(EXTRA_RESULT, json);
        sendBroadcast(result);
    }
}
