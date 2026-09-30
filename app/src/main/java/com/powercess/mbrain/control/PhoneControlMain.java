package com.powercess.mbrain.control;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.UiAutomation;
import android.content.ClipData;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import org.json.JSONArray;
import org.json.JSONObject;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Fixed operations executed by app_process as shell/root; never accepts executable code. */
public final class PhoneControlMain {
    private static final int MAX_NODES = 600;
    private UiAutomation automation;
    private HandlerThread callbacks;

    public static void main(String[] args) {
        PhoneControlMain runner = new PhoneControlMain();
        JSONObject reply = new JSONObject();
        try {
            if (args.length != 2) throw new IllegalArgumentException("Expected operation and JSON arguments");
            // Binder framework clients may require a main looper even in app_process.
            if (Looper.getMainLooper() == null) Looper.prepareMainLooper();
            JSONObject params = new JSONObject(args[1]);
            JSONObject data = args[0].equals("net_diagnose") ? runner.network(params)
                    : args[0].startsWith("clipboard_") ? runner.clipboard(args[0], params) : runner.control(args[0], params);
            reply.put("ok", true).put("data", data);
        } catch (Throwable error) {
            while (error.getCause() != null) error = error.getCause();
            try { reply.put("ok", false).put("error", error.getClass().getSimpleName() + ": " + error.getMessage()); }
            catch (Exception ignored) { }
        } finally {
            if (runner.automation != null) {
                try { UiAutomation.class.getMethod("disconnect").invoke(runner.automation); }
                catch (Exception ignored) { }
            }
            if (runner.callbacks != null) runner.callbacks.quitSafely();
        }
        System.out.println("MBRAIN_RESULT:" + reply);
        System.out.flush();
        System.exit(0);
    }

    private void connect() throws Exception {
        callbacks = new HandlerThread("mbrain-ui");
        callbacks.start();
        Class<?> connectionType = Class.forName("android.app.IUiAutomationConnection");
        Object connection = Class.forName("android.app.UiAutomationConnection").getConstructor().newInstance();
        automation = UiAutomation.class.getConstructor(Looper.class, connectionType)
                .newInstance(callbacks.getLooper(), connection);
        UiAutomation.class.getMethod("connect", int.class).invoke(automation,
                UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        AccessibilityServiceInfo info = automation.getServiceInfo();
        info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
        automation.setServiceInfo(info);
    }

    private JSONObject control(String operation, JSONObject params) throws Exception {
        connect();
        switch (operation) {
            case "ui_dump": return dump(params);
            case "ui_wait": return waitFor(params);
            case "ui_click": case "ui_long_click": case "ui_set_text": return nodeAction(operation, params);
            case "ui_swipe": return swipe(params);
            case "ui_navigate": {
                String action = params.getString("action");
                int code;
                switch (action) {
                    case "back": code = AccessibilityService.GLOBAL_ACTION_BACK; break;
                    case "home": code = AccessibilityService.GLOBAL_ACTION_HOME; break;
                    case "recents": code = AccessibilityService.GLOBAL_ACTION_RECENTS; break;
                    case "notifications": code = AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS; break;
                    case "quick_settings": code = AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS; break;
                    default: throw new IllegalArgumentException("Unknown navigation action");
                }
                if (!automation.performGlobalAction(code)) throw new IllegalStateException("Navigation rejected");
                return new JSONObject().put("performed", true).put("action", action);
            }
            default: throw new IllegalArgumentException("Unknown operation: " + operation);
        }
    }

    private static final class Node {
        final AccessibilityNodeInfo info;
        final int parent;
        Node(AccessibilityNodeInfo info, int parent) { this.info = info; this.parent = parent; }
    }

    private static final class UiUnavailable extends IllegalStateException {
        UiUnavailable() { super("ui_unavailable: No active window; unlock the device and retry"); }
    }

    private List<Node> snapshot() {
        AccessibilityNodeInfo root = automation.getRootInActiveWindow();
        // Connection registration can finish before the active window is published.
        long deadline = SystemClock.uptimeMillis() + 1000;
        while (root == null && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(100);
            root = automation.getRootInActiveWindow();
        }
        if (root == null) throw new UiUnavailable();
        List<Node> nodes = new ArrayList<>();
        nodes.add(new Node(root, -1));
        for (int i = 0; i < nodes.size() && nodes.size() < MAX_NODES; i++) {
            AccessibilityNodeInfo node = nodes.get(i).info;
            for (int child = 0; child < node.getChildCount() && nodes.size() < MAX_NODES; child++) {
                AccessibilityNodeInfo next = node.getChild(child);
                if (next != null) nodes.add(new Node(next, i));
            }
        }
        return nodes;
    }

    private static void recycle(List<Node> nodes) {
        for (Node node : nodes) node.info.recycle();
    }

    private JSONObject describe(Node item, int index) throws Exception {
        AccessibilityNodeInfo node = item.info;
        Rect bounds = new Rect(); node.getBoundsInScreen(bounds);
        return new JSONObject().put("index", index).put("parent", item.parent)
                .put("text", node.isPassword() ? "" : bounded(node.getText()))
                .put("description", bounded(node.getContentDescription()))
                .put("resource_id", value(node.getViewIdResourceName()))
                .put("class", value(node.getClassName())).put("package", value(node.getPackageName()))
                .put("bounds", new JSONArray(new int[]{bounds.left, bounds.top, bounds.right, bounds.bottom}))
                .put("clickable", node.isClickable()).put("long_clickable", node.isLongClickable())
                .put("editable", node.isEditable()).put("scrollable", node.isScrollable())
                .put("enabled", node.isEnabled()).put("visible", node.isVisibleToUser())
                .put("focused", node.isFocused()).put("password", node.isPassword());
    }

    private JSONObject dump(JSONObject params) throws Exception {
        List<Node> nodes = snapshot();
        try {
            int limit = integer(params, "limit", 50, 1, 60);
            int offset = integer(params, "offset", 0, 0, MAX_NODES - 1);
            JSONArray result = new JSONArray();
            int end = Math.min(nodes.size(), offset + limit);
            for (int i = offset; i < end; i++) result.put(describe(nodes.get(i), i));
            return new JSONObject().put("nodes", result).put("count", nodes.size())
                    .put("next_offset", end < nodes.size() ? end : JSONObject.NULL)
                    .put("truncated", nodes.size() == MAX_NODES)
                    .put("note", "Indices describe this snapshot only. Select actions by text/resource_id/description.");
        } finally { recycle(nodes); }
    }

    private List<Integer> matches(List<Node> nodes, JSONObject params) throws Exception {
        if (!params.has("text") && !params.has("resource_id") && !params.has("description"))
            throw new IllegalArgumentException("Supply text, resource_id or description selector");
        List<Integer> result = new ArrayList<>();
        for (int i = 0; i < nodes.size(); i++) {
            AccessibilityNodeInfo node = nodes.get(i).info;
            if (!node.isVisibleToUser()) continue;
            if (params.has("text") && (node.isPassword() || !match(value(node.getText()), params.getString("text"), params.optBoolean("contains", false)))) continue;
            if (params.has("resource_id") && !value(node.getViewIdResourceName()).equals(params.getString("resource_id"))) continue;
            if (params.has("description") && !match(value(node.getContentDescription()), params.getString("description"), params.optBoolean("contains", false))) continue;
            if (params.has("package") && !value(node.getPackageName()).equals(params.getString("package"))) continue;
            result.add(i);
        }
        return result;
    }

    private static boolean match(String actual, String expected, boolean contains) {
        return contains ? actual.contains(expected) : actual.equals(expected);
    }

    private JSONObject nodeAction(String operation, JSONObject params) throws Exception {
        if (params.has("x") || params.has("y")) {
            if (operation.equals("ui_set_text")) throw new IllegalArgumentException("Text input requires a node selector");
            for (String key : new String[]{"text", "resource_id", "description", "package", "index", "contains"}) {
                if (params.has(key)) throw new IllegalArgumentException("Use coordinates OR a selector");
            }
            int x = integer(params, "x", -1, 0, 20000), y = integer(params, "y", -1, 0, 20000);
            gesture(x, y, x, y, operation.equals("ui_long_click") ? 800 : 70);
            return new JSONObject().put("performed", true);
        }
        List<Node> nodes = snapshot();
        try {
            List<Integer> found = matches(nodes, params);
            if (found.isEmpty()) throw new IllegalStateException("node_not_found");
            if (found.size() > 1 && !params.has("index")) throw new IllegalArgumentException("ambiguous_selector: " + found.size() + " matches; supply zero-based index");
            int index = integer(params, "index", 0, 0, found.size() - 1);
            int position = found.get(index);
            AccessibilityNodeInfo target = nodes.get(position).info;
            int action = operation.equals("ui_set_text") ? AccessibilityNodeInfo.ACTION_SET_TEXT
                    : operation.equals("ui_long_click") ? AccessibilityNodeInfo.ACTION_LONG_CLICK : AccessibilityNodeInfo.ACTION_CLICK;
            while (position >= 0 && !(operation.equals("ui_set_text") ? target.isEditable()
                    : operation.equals("ui_long_click") ? target.isLongClickable() : target.isClickable())) {
                position = nodes.get(position).parent;
                if (position >= 0) target = nodes.get(position).info;
            }
            if (position < 0) throw new IllegalStateException("No actionable ancestor for " + operation);
            if (!target.isEnabled()) throw new IllegalStateException("Target is disabled");
            Bundle args = new Bundle();
            if (operation.equals("ui_set_text")) {
                String content = params.getString("value");
                if (content.length() > 16000) throw new IllegalArgumentException("Text exceeds 16000 characters");
                args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, content);
            }
            if (!target.performAction(action, args)) throw new IllegalStateException("action_rejected: Refresh ui_dump and retry with another selector");
            return new JSONObject().put("performed", true).put("matched_count", found.size());
        } finally { recycle(nodes); }
    }

    private JSONObject waitFor(JSONObject params) throws Exception {
        int timeout = integer(params, "timeout_ms", 5000, 0, 15000);
        String state = params.optString("state", "present");
        if (!state.equals("present") && !state.equals("absent")) throw new IllegalArgumentException("state must be present or absent");
        long start = SystemClock.uptimeMillis();
        do {
            List<Node> nodes = null;
            try {
                nodes = snapshot();
                List<Integer> found = matches(nodes, params);
                if (state.equals("absent") == found.isEmpty()) {
                    JSONObject result = new JSONObject().put("matched", true).put("state", state)
                            .put("elapsed_ms", SystemClock.uptimeMillis() - start);
                    if (!found.isEmpty()) result.put("node", describe(nodes.get(found.get(0)), found.get(0)));
                    return result;
                }
            } catch (UiUnavailable unavailable) {
                // During activity transitions there may briefly be no root. This is not
                // evidence of an absent selector; continue until a real tree or timeout.
            } finally { if (nodes != null) recycle(nodes); }
            if (SystemClock.uptimeMillis() - start >= timeout) break;
            SystemClock.sleep(Math.min(250, timeout - (SystemClock.uptimeMillis() - start)));
        } while (true);
        throw new IllegalStateException("wait_timeout: condition not met after " + timeout + "ms");
    }

    private JSONObject swipe(JSONObject params) throws Exception {
        gesture(integer(params, "x1", -1, 0, 20000), integer(params, "y1", -1, 0, 20000),
                integer(params, "x2", -1, 0, 20000), integer(params, "y2", -1, 0, 20000),
                integer(params, "duration_ms", 350, 50, 3000));
        return new JSONObject().put("performed", true);
    }

    private void gesture(int x1, int y1, int x2, int y2, int duration) throws Exception {
        // Default display only; use current real display dimensions (including system bars).
        Object manager = Class.forName("android.hardware.display.DisplayManagerGlobal").getMethod("getInstance").invoke(null);
        android.view.Display display = (android.view.Display) manager.getClass().getMethod("getRealDisplay", int.class).invoke(manager, 0);
        android.graphics.Point size = new android.graphics.Point(); display.getRealSize(size);
        if (x1 >= size.x || x2 >= size.x || y1 >= size.y || y2 >= size.y) throw new IllegalArgumentException("Coordinates outside " + size.x + "x" + size.y);
        long down = SystemClock.uptimeMillis();
        inject(down, MotionEvent.ACTION_DOWN, x1, y1);
        boolean completed = false;
        try {
            int steps = Math.max(1, duration / 16);
            for (int step = 1; step <= steps; step++) {
                long delay = down + (long) duration * step / steps - SystemClock.uptimeMillis();
                if (delay > 0) SystemClock.sleep(delay);
                float fraction = (float) step / steps;
                if (x1 != x2 || y1 != y2) inject(down, MotionEvent.ACTION_MOVE, x1 + (x2 - x1) * fraction, y1 + (y2 - y1) * fraction);
            }
            inject(down, MotionEvent.ACTION_UP, x2, y2);
            completed = true;
        } finally {
            if (!completed) inject(down, MotionEvent.ACTION_CANCEL, x2, y2);
        }
    }

    private void inject(long down, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try { if (!automation.injectInputEvent(event, true)) throw new IllegalStateException("Input injection rejected"); }
        finally { event.recycle(); }
    }

    private JSONObject clipboard(String operation, JSONObject params) throws Exception {
        if (!operation.equals("clipboard_get") && !operation.equals("clipboard_set")) throw new IllegalArgumentException("Unknown clipboard operation");
        IBinder binder = (IBinder) Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, "clipboard");
        Object service = Class.forName("android.content.IClipboard$Stub").getMethod("asInterface", IBinder.class).invoke(null, binder);
        String methodName = operation.equals("clipboard_get") ? "getPrimaryClip" : "setPrimaryClip";
        Method selected = null;
        for (Method method : Class.forName("android.content.IClipboard").getMethods()) {
            if (method.getName().equals(methodName)) { selected = method; break; }
        }
        if (selected == null) throw new UnsupportedOperationException("Clipboard interface unavailable");
        int user = (Integer) Class.forName("android.app.ActivityManager").getMethod("getCurrentUser").invoke(null);
        Object[] args = new Object[selected.getParameterCount()];
        int strings = 0, ints = 0;
        for (int i = 0; i < args.length; i++) {
            Class<?> type = selected.getParameterTypes()[i];
            if (type == String.class) args[i] = strings++ == 0 ? "com.android.shell" : null;
            else if (type == int.class) args[i] = ints++ == 0 ? user : 0;
            else if (type == ClipData.class) {
                String text = params.getString("value");
                if (text.length() > 16000) throw new IllegalArgumentException("Clipboard exceeds 16000 characters");
                args[i] = ClipData.newPlainText("MBrain", text);
            } else throw new UnsupportedOperationException("Unsupported clipboard signature");
        }
        Object result = selected.invoke(service, args);
        if (operation.equals("clipboard_set")) return new JSONObject().put("written", true);
        ClipData clip = (ClipData) result;
        if (clip == null) return new JSONObject().put("available", false).put("text", JSONObject.NULL)
                .put("note", "Clipboard is empty or access was denied by the system");
        CharSequence text = clip.getItemCount() > 0 ? clip.getItemAt(0).getText() : null;
        if (text == null) return new JSONObject().put("available", true).put("text", JSONObject.NULL).put("item_count", clip.getItemCount());
        String content = text.toString();
        return new JSONObject().put("available", true).put("text", content.substring(0, Math.min(16000, content.length())))
                .put("truncated", content.length() > 16000).put("item_count", clip.getItemCount());
    }

    private static String value(CharSequence value) { return value == null ? "" : value.toString(); }
    private JSONObject network(JSONObject params) throws Exception {
        String host = params.getString("host");
        if (host.length() > 253 || !host.matches("[A-Za-z0-9:.%-]+") || host.startsWith("-"))
            throw new IllegalArgumentException("Invalid host; supply a hostname or IP without scheme/path");
        int port = integer(params, "port", 443, 1, 65535);
        java.util.concurrent.ExecutorService worker = java.util.concurrent.Executors.newSingleThreadExecutor();
        JSONObject result = new JSONObject().put("host", host).put("port", port);
        long start = SystemClock.uptimeMillis();
        try {
            java.util.concurrent.Future<java.net.InetAddress[]> lookup = worker.submit(() -> java.net.InetAddress.getAllByName(host));
            java.net.InetAddress[] addresses;
            try { addresses = lookup.get(4, java.util.concurrent.TimeUnit.SECONDS); }
            catch (Exception error) {
                lookup.cancel(true);
                return result.put("dns", new JSONObject().put("ok", false).put("error", error.toString()))
                        .put("tcp", new JSONObject().put("attempted", false));
            }
            JSONArray resolved = new JSONArray();
            for (java.net.InetAddress address : addresses) resolved.put(address.getHostAddress());
            result.put("dns", new JSONObject().put("ok", true).put("addresses", resolved)
                    .put("elapsed_ms", SystemClock.uptimeMillis() - start));
            JSONArray attempts = new JSONArray();
            boolean connected = false;
            for (int i = 0; i < Math.min(3, addresses.length); i++) {
                long tcpStart = SystemClock.uptimeMillis();
                JSONObject attempt = new JSONObject().put("address", addresses[i].getHostAddress());
                try (java.net.Socket socket = new java.net.Socket()) {
                    socket.connect(new java.net.InetSocketAddress(addresses[i], port), 2000);
                    attempt.put("ok", true); connected = true;
                } catch (Exception error) { attempt.put("ok", false).put("error", error.toString()); }
                attempt.put("elapsed_ms", SystemClock.uptimeMillis() - tcpStart);
                attempts.put(attempt);
                if (connected) break;
            }
            return result.put("tcp", new JSONObject().put("attempted", true).put("ok", connected).put("attempts", attempts));
        } finally { worker.shutdownNow(); }
    }

    private static String bounded(CharSequence value) { String text = value(value); return text.substring(0, Math.min(200, text.length())); }
    private static int integer(JSONObject params, String key, int fallback, int min, int max) throws Exception {
        Object raw = params.has(key) ? params.get(key) : fallback;
        if (!(raw instanceof Number)) throw new IllegalArgumentException(key + " must be an integer");
        double number = ((Number) raw).doubleValue();
        if (!Double.isFinite(number) || number != Math.rint(number) || number < min || number > max)
            throw new IllegalArgumentException(key + " must be between " + min + " and " + max);
        return (int) number;
    }
}
