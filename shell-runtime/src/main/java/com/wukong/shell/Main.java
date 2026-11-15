package com.wukong.shell;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.UiAutomation;
import android.graphics.Rect;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.util.Base64;
import android.os.Bundle;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.InputEvent;
import android.util.SparseArray;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityWindowInfo;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;

/** Executed exclusively by adb shell + app_process. This APK is never installed. */
public final class Main {
    private final String token;
    private final UiAutomation automation;
    private final UiChanges uiChanges = new UiChanges();
    private final ThreadLocal<List<Integer>> observedWindows = ThreadLocal.withInitial(ArrayList::new);
    private final ThreadLocal<String> windowSignature = ThreadLocal.withInitial(() -> "");
    private static final int CHANGE_EVENTS = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED | AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        | AccessibilityEvent.TYPE_WINDOWS_CHANGED | AccessibilityEvent.TYPE_VIEW_SCROLLED | AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED;
    private final Object deviceLock = new Object();
    private final Map<String, Boolean> tasks = new ConcurrentHashMap<>();
    private final Map<String, Frame> frames = new ConcurrentHashMap<>();
    private final Map<String, Integer> taskDisplays = new ConcurrentHashMap<>();
    private final ThreadLocal<Integer> targetDisplay = ThreadLocal.withInitial(() -> 0);
    private VirtualScreen virtualScreen;
    private static final Pattern PACKAGE = Pattern.compile("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+");
    private static final Pattern SENSITIVE = Pattern.compile("密码|验证码|支付口令|password|passcode|one.time.code|\\botp\\b", Pattern.CASE_INSENSITIVE);

    public static void main(String[] args) throws Exception {
        if (android.os.Process.myUid() != 2000) throw new SecurityException("Wukong runtime requires adb shell UID 2000");
        if (args.length != 2) throw new IllegalArgumentException("Expected port and token-file");
        int port = Integer.parseInt(args[0]);
        if (port < 1024 || port > 65535) throw new IllegalArgumentException("Invalid port");
        File file = new File(args[1]);
        String token = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).trim();
        if (!token.matches("[a-f0-9]{64}")) throw new SecurityException("Invalid activation credential");
        if (!file.delete()) throw new IOException("Cannot remove temporary activation credential");
        if (Looper.myLooper() == null) Looper.prepareMainLooper();
        new Main(token).serve(port);
    }

    private Main(String token) throws Exception {
        this.token = token;
        HandlerThread events = new HandlerThread("wukong-ui-events");
        events.start();
        Class<?> connectionType = Class.forName("android.app.IUiAutomationConnection");
        Object connection = Class.forName("android.app.UiAutomationConnection").getDeclaredConstructor().newInstance();
        automation = UiAutomation.class.getConstructor(Looper.class, connectionType).newInstance(events.getLooper(), connection);
        UiAutomation.class.getMethod("connect", int.class).invoke(automation, UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        configureAutomation();
    }

    private void configureAutomation() {
        automation.setOnAccessibilityEventListener(event -> {
            if ((event.getEventType() & CHANGE_EVENTS) != 0) uiChanges.record(event.getWindowId(), event.getEventTime());
        });
        AccessibilityServiceInfo info = automation.getServiceInfo();
        if (info == null) throw new IllegalStateException("Cannot initialize shell UI automation");
        info.eventTypes |= CHANGE_EVENTS;
        info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        automation.setServiceInfo(info);
    }

    private void serve(int port) throws Exception {
        ThreadPoolExecutor clients = new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16));
        try (ServerSocket server = new ServerSocket()) {
            server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 16);
            System.out.println("Wukong shell ready; uid=" + android.os.Process.myUid() + "; protocol=2");
            while (true) {
                Socket socket = server.accept();
                try { clients.execute(() -> handle(socket)); }
                catch (RejectedExecutionException e) { socket.close(); }
            }
        } finally { clients.shutdownNow(); }
    }

    private void handle(Socket socket) {
        try (socket) {
            socket.setSoTimeout(10_000);
            String line = readLine(new BufferedInputStream(socket.getInputStream()));
            JSONObject request = new JSONObject(line);
            // Authenticate before dispatch; never echo credentials, node text or request bodies to logs.
            if (!MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), request.optString("token").getBytes(StandardCharsets.UTF_8))) {
                write(socket, new JSONObject().put("ok", false).put("error", "Unauthorized"));
                return;
            }
            try { write(socket, new JSONObject().put("ok", true).put("result", dispatch(request))); }
            catch (Exception e) {
                Throwable detail = e;
                while (detail instanceof java.lang.reflect.InvocationTargetException && detail.getCause() != null) detail = detail.getCause();
                if (request.optString("method").startsWith("virtual")) detail.printStackTrace(System.err);
                String message = detail.getMessage();
                write(socket, new JSONObject().put("ok", false).put("error", message == null ? "Shell operation failed" : message));
            }
        } catch (Exception ignored) { /* A broken client must not terminate the privileged process. */ }
    }

    private Object dispatch(JSONObject request) throws Exception {
        targetDisplay.set(0);
        String method = request.getString("method");
        if (method.equals("status")) return new JSONObject().put("uid", android.os.Process.myUid()).put("protocol", 2).put("engine", "app_process").put("uiEvents", true).put("inspection", true);
        if (method.startsWith("virtual") || method.equals("setControl") || method.equals("manualInput")) {
            synchronized (deviceLock) { return virtualRequest(method, request); }
        }
        String taskId = request.optString("taskId");
        if (method.equals("beginTask")) {
            requireTaskId(taskId);
            if (tasks.size() >= 64) throw new IllegalStateException("Too many active sessions");
            if (tasks.putIfAbsent(taskId, false) != null) throw new IllegalStateException("Session already exists");
            int displayId = request.optInt("displayId", 0);
            if (displayId != 0 && (virtualScreen == null || virtualScreen.id != displayId)) { tasks.remove(taskId); throw new IllegalStateException("虚拟屏已关闭"); }
            taskDisplays.put(taskId, displayId);
            return "started";
        }
        if (method.equals("cancelTask")) { requireTaskId(taskId); tasks.computeIfPresent(taskId, (id, value) -> true); frames.remove(taskId); return "cancelled"; }
        if (method.equals("finishTask")) { requireTaskId(taskId); tasks.remove(taskId); frames.remove(taskId); taskDisplays.remove(taskId); return "closed"; }
        synchronized (deviceLock) {
            checkTask(taskId);
            targetDisplay.set(taskDisplays.getOrDefault(taskId, 0));
            if (targetDisplay.get() != 0 && virtualScreen != null && virtualScreen.manual) throw new IllegalStateException("用户正在接管，Agent 已暂停");
            if (method.equals("observe")) {
                try (Tree tree = read()) { return tree.json; }
            }
            if (method.equals("capture")) {
                try (Tree tree = read()) {
                    verifySnapshot(request, tree);
                    if (tree.incomplete) throw new SecurityException("页面结构读取不完整，请手动处理");
                    if (tree.sensitive) throw new SecurityException("检测到受保护输入，请手动处理");
                    Bitmap image = captureDisplay();
                    if (image == null) throw new IllegalStateException("系统未提供截图，当前画面可能受保护");
                    try {
                        String id = UUID.randomUUID().toString();
                        frames.put(taskId, new Frame(id, tree.json.getString("fingerprint"), image.getWidth(), image.getHeight()));
                        double scale = Math.min(1d, 1600d / Math.max(image.getWidth(), image.getHeight()));
                        Bitmap scaled = Bitmap.createScaledBitmap(image, Math.max(1, (int)(image.getWidth()*scale)), Math.max(1, (int)(image.getHeight()*scale)), true);
                        try {
                            ByteArrayOutputStream jpeg = new ByteArrayOutputStream(); scaled.compress(Bitmap.CompressFormat.JPEG, 65, jpeg);
                            if (jpeg.size() > 650_000) throw new IllegalStateException("截图内容过大，请切换到较简单的画面");
                            return new JSONObject().put("captureId", id).put("width", scaled.getWidth()).put("height", scaled.getHeight())
                                .put("jpeg", Base64.encodeToString(jpeg.toByteArray(), Base64.NO_WRAP));
                        } finally { if (scaled != image) scaled.recycle(); }
                    } finally { image.recycle(); }
                }
            }
            if (!method.equals("execute")) throw new IllegalArgumentException("Unknown method");
            if (targetDisplay.get() != 0 && virtualScreen != null && virtualScreen.manual) throw new IllegalStateException("用户正在接管，Agent 已暂停");
            JSONObject action = request.getJSONObject("action");
            try (Tree tree = read()) {
                verifySnapshot(request, tree);
                if (tree.incomplete) throw new SecurityException("页面结构读取不完整，请手动处理");
                if (tree.sensitive) throw new SecurityException("检测到受保护输入，请手动处理");
                checkTask(taskId);
                execute(action, tree, taskId);
                frames.remove(taskId);
                return "操作已被系统接受；下一步将重新读取界面";
            }
        }
    }

    private Object virtualRequest(String method, JSONObject request) throws Exception {
        if (method.equals("virtualStart")) {
            if (virtualScreen == null) virtualScreen = new VirtualScreen();
            virtualScreen.ownerId = request.optString("ownerId");
            targetDisplay.set(virtualScreen.id);
            command("/system/bin/am", "start", "--display", String.valueOf(virtualScreen.id), "-n", "com.wukong.phone/.VirtualHomeActivity");
            return virtualScreen.status(false);
        }
        if (method.equals("virtualStop")) {
            if (virtualScreen != null && request.has("ownerId") && !virtualScreen.ownerId.equals(request.getString("ownerId"))) return "stale_owner";
            if (virtualScreen != null) {
                int id = virtualScreen.id;
                taskDisplays.forEach((task, display) -> { if (display == id) tasks.put(task, true); });
                // Finish content before removal; do not reparent virtual tasks to the
                // physical display or leave Android's compat recovery targeting a dead ID.
                try {
                    @SuppressWarnings("unchecked") SparseArray<List<AccessibilityWindowInfo>> windows = (SparseArray<List<AccessibilityWindowInfo>>) UiAutomation.class.getMethod("getWindowsOnAllDisplays").invoke(automation);
                    Object manager = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
                    for (AccessibilityWindowInfo window : windows.get(id,Collections.emptyList())) {
                        int task = (Integer) AccessibilityWindowInfo.class.getMethod("getTaskId").invoke(window);
                        if (task >= 0) Class.forName("android.app.IActivityTaskManager").getMethod("removeTask",int.class).invoke(manager,task);
                    }
                    SystemClock.sleep(250);
                } catch (ReflectiveOperationException ignored) { }
                virtualScreen.close(); virtualScreen = null;
            }
            return "closed";
        }
        if (method.equals("virtualStatus") && virtualScreen == null) return new JSONObject().put("displayId", 0);
        if (virtualScreen == null) throw new IllegalStateException("虚拟屏未启动");
        targetDisplay.set(virtualScreen.id);
        if (method.equals("virtualStatus") || method.equals("virtualFrame")) return virtualScreen.status(method.equals("virtualFrame"));
        if (method.equals("setControl")) { virtualScreen.manual = request.getBoolean("manual"); frames.clear(); return virtualScreen.status(false); }
        if (!method.equals("manualInput") || !virtualScreen.manual) throw new IllegalStateException("请先接管虚拟屏");
        String kind = request.getString("kind");
        if (kind.equals("back")) { key(KeyEvent.KEYCODE_BACK); return "accepted"; }
        if (kind.equals("home")) {
            command("/system/bin/am", "start", "--display", String.valueOf(virtualScreen.id), "-n", "com.wukong.phone/.VirtualHomeActivity"); return "accepted";
        }
        if (kind.equals("text")) {
            String text = request.getString("text");
            if (text.length() > 2000) throw new IllegalArgumentException("输入过长");
            try (Tree tree = read()) {
                for (AccessibilityNodeInfo node : tree.nodes.values()) if (node.isEditable() && node.isFocused()) {
                    Bundle args = new Bundle(); args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
                    if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) throw new IllegalStateException("当前输入框未接受文本");
                    return "accepted";
                }
            }
            throw new IllegalStateException("请先点击虚拟屏中的输入框");
        }
        if (!kind.equals("gesture")) throw new IllegalArgumentException("Unknown manual input");
        int x = coordinate(request.getInt("x"), virtualScreen.width), y = coordinate(request.getInt("y"), virtualScreen.height);
        int ex = coordinate(request.getInt("endX"), virtualScreen.width), ey = coordinate(request.getInt("endY"), virtualScreen.height);
        long duration = Math.max(60, Math.min(2000, request.optLong("duration", 60)));
        if (x == ex && y == ey && duration < 400) touch(x, y);
        else gesture(x, y, ex, ey, duration, null);
        return "accepted";
    }

    private Bitmap captureDisplay() { return targetDisplay.get() == 0 ? automation.takeScreenshot() : virtualScreen == null ? null : virtualScreen.capture(); }

    private void verifySnapshot(JSONObject request, Tree tree) throws Exception {
        if (!tree.json.getString("fingerprint").equals(request.getString("fingerprint")) || !tree.json.getString("package").equals(request.getString("package")))
            throw new IllegalArgumentException("界面已更新，本次操作未执行");
    }

    private void execute(JSONObject action, Tree tree, String taskId) throws Exception {
        String kind = action.getString("kind");
        AccessibilityNodeInfo node;
        boolean success;
        switch (kind) {
            case "tap_at": case "swipe": case "long_press":
                Frame frame = frames.get(taskId);
                if (frame == null || !frame.id.equals(action.getString("captureId")) || SystemClock.uptimeMillis() - frame.time > 30_000
                    || !frame.fingerprint.equals(tree.json.getString("fingerprint")) || frame.width != tree.width || frame.height != tree.height)
                    throw new IllegalArgumentException("截图已过期，请重新截图或 OCR");
                int x = coordinate(action.getInt("x"), frame.width), y = coordinate(action.getInt("y"), frame.height);
                int ex = kind.equals("swipe") ? coordinate(action.getInt("endX"), frame.width) : x;
                int ey = kind.equals("swipe") ? coordinate(action.getInt("endY"), frame.height) : y;
                long duration = action.optLong("duration", kind.equals("long_press") ? 600 : 350);
                if (kind.equals("tap_at")) success = touch(x, y);
                else {
                    if (duration < (kind.equals("long_press") ? 400 : 100) || duration > 2000) throw new IllegalArgumentException("手势时长无效");
                    success = gesture(x, y, ex, ey, duration, taskId);
                }
                break;
            case "tap":
                node = requireNode(action, tree);
                if (!node.isClickable() || !node.isEnabled()) throw new IllegalArgumentException("目标不可点击");
                Rect bounds = new Rect(); node.getBoundsInScreen(bounds);
                checkTask(taskId);
                success = touch(bounds.centerX(), bounds.centerY());
                break;
            case "input":
                node = requireNode(action, tree);
                if (!node.isEditable() || !node.isEnabled() || node.isPassword()) throw new IllegalArgumentException("目标不可输入");
                String text = action.getString("text");
                if (text.isEmpty() || text.length() > 2000) throw new IllegalArgumentException("输入文本长度无效");
                Bundle args = new Bundle(); args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
                success = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
                break;
            case "scroll":
                node = requireNode(action, tree);
                if (!node.isScrollable() || !node.isEnabled()) throw new IllegalArgumentException("目标不可滚动");
                success = node.performAction(action.getBoolean("forward") ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
                break;
            case "home":
                if (targetDisplay.get() == 0) success = key(KeyEvent.KEYCODE_HOME);
                else success = !command("/system/bin/am", "start", "--display", String.valueOf(targetDisplay.get()), "-n", "com.wukong.phone/.VirtualHomeActivity").contains("Error:");
                break;
            case "back": success = key(KeyEvent.KEYCODE_BACK); break;
            case "launch":
                String pkg = action.getString("packageName");
                if (!PACKAGE.matcher(pkg).matches()) throw new IllegalArgumentException("应用包名无效");
                String resolved = command("/system/bin/cmd", "package", "resolve-activity", "--brief", "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER", "-p", pkg);
                String component = null;
                for (String line : resolved.split("\n")) {
                    String candidate = line.trim();
                    if (candidate.startsWith(pkg + "/") && candidate.substring(pkg.length() + 1).matches("[A-Za-z0-9_.$]+")) component = candidate;
                }
                if (component == null) throw new IllegalArgumentException("应用没有可启动的界面");
                checkTask(taskId);
                success = !command("/system/bin/am", "start", "--display", String.valueOf(targetDisplay.get()), "-n", component).contains("Error:");
                break;
            default: throw new IllegalArgumentException("Unknown action");
        }
        if (!success) throw new IllegalStateException("系统未接受操作，请手动处理");
    }

    private int coordinate(int value, int size) {
        if (value < 0 || value > 1000) throw new IllegalArgumentException("坐标须在 0–1000 范围内");
        return Math.round(value * (size - 1) / 1000f);
    }

    private boolean gesture(float x, float y, float ex, float ey, long duration, String taskId) throws Exception {
        long down = SystemClock.uptimeMillis();
        boolean complete = false;
        try {
            if (!motion(down, MotionEvent.ACTION_DOWN, x, y)) return false;
            int steps = Math.max(1, (int)(duration / 25));
            for (int i = 1; i <= steps; i++) {
                if (taskId != null) checkTask(taskId); SystemClock.sleep(duration / steps);
                if (!motion(down, MotionEvent.ACTION_MOVE, x + (ex-x)*i/steps, y + (ey-y)*i/steps)) return false;
            }
            complete = motion(down, MotionEvent.ACTION_UP, ex, ey); return complete;
        } finally { if (!complete) motion(down, MotionEvent.ACTION_CANCEL, ex, ey); }
    }

    private boolean motion(long down, int kind, float x, float y) {
        MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), kind, x, y, 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        setDisplay(event);
        try { return automation.injectInputEvent(event, true); } finally { event.recycle(); }
    }

    private String command(String... argv) throws Exception {
        java.lang.Process child = new ProcessBuilder(argv).redirectErrorStream(true).start();
        if (!child.waitFor(5, TimeUnit.SECONDS)) { child.destroy(); throw new IOException("系统命令超时"); }
        try (InputStream stream = child.getInputStream(); ByteArrayOutputStream result = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024]; int count;
            while ((count = stream.read(buffer)) >= 0) { if (result.size() + count > 16_384) throw new IOException("系统响应过大"); result.write(buffer, 0, count); }
            if (child.exitValue() != 0) throw new IOException("系统命令未成功");
            return result.toString(StandardCharsets.UTF_8.name());
        }
    }

    private boolean touch(float x, float y) {
        long now = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent up = MotionEvent.obtain(now, now + 50, MotionEvent.ACTION_UP, x, y, 0);
        down.setSource(InputDevice.SOURCE_TOUCHSCREEN); up.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        setDisplay(down); setDisplay(up);
        try { return automation.injectInputEvent(down, true) && automation.injectInputEvent(up, true); }
        finally { down.recycle(); up.recycle(); }
    }

    private boolean key(int keyCode) {
        long now = SystemClock.uptimeMillis();
        KeyEvent down = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0), up = new KeyEvent(now, now + 40, KeyEvent.ACTION_UP, keyCode, 0);
        setDisplay(down); setDisplay(up);
        return automation.injectInputEvent(down, true) && automation.injectInputEvent(up, true);
    }

    private void setDisplay(InputEvent event) {
        try { InputEvent.class.getMethod("setDisplayId", int.class).invoke(event, targetDisplay.get()); }
        catch (ReflectiveOperationException error) { if (targetDisplay.get() != 0) throw new IllegalStateException("系统不支持定向输入", error); }
    }

    private AccessibilityNodeInfo requireNode(JSONObject action, Tree tree) throws Exception {
        AccessibilityNodeInfo node = tree.nodes.get(action.getInt("nodeId"));
        if (node == null) throw new IllegalArgumentException("节点已失效");
        return node;
    }

    private Tree read() throws Exception {
        try { UiAutomation.class.getMethod("clearCache").invoke(automation); } catch (ReflectiveOperationException ignored) { }
        AccessibilityNodeInfo root = null;
        long deadline = SystemClock.uptimeMillis() + 10_000;
        // Window removal/app transitions can temporarily have no root even on an
        // unlocked phone. Refresh stale caches, then reconnect once if necessary.
        for (int round = 0; round < 2 && root == null; round++) {
            for (int attempt = 0; attempt < 6 && root == null && SystemClock.uptimeMillis() < deadline; attempt++) {
                if (attempt > 0) {
                    SystemClock.sleep(150);
                    try { UiAutomation.class.getMethod("clearCache").invoke(automation); } catch (ReflectiveOperationException ignored) { }
                }
                try { root = activeRoot(); } catch (IllegalStateException ignored) { root = null; }
            }
            if (root == null && round == 0) {
                try { UiAutomation.class.getMethod("disconnect").invoke(automation); } catch (ReflectiveOperationException ignored) { }
                UiAutomation.class.getMethod("connect", int.class).invoke(automation, UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
                configureAutomation();
            }
        }
        if (root == null) throw new IllegalStateException("当前应用暂未提供可读取的界面，请稍后重试；若手机锁屏，请先解锁");
        Tree tree = new Tree();
        try {
            Point size = displaySize(); tree.width = size.x; tree.height = size.y;
            String pkg = String.valueOf(root.getPackageName());
            List<Integer> relevant = new ArrayList<>(observedWindows.get());
            if (!relevant.contains(root.getWindowId())) relevant.add(root.getWindowId());
            StringBuilder signature = new StringBuilder(pkg).append('|').append(windowSignature.get());
            JSONArray nodes = new JSONArray();
            visit(root, tree, nodes, signature, 0, null);
            signature.append(":truncated=").append(tree.truncated);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(signature.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hash = new StringBuilder(); for (byte b : digest) hash.append(String.format(Locale.ROOT, "%02x", b));
            long[] change = uiChanges.snapshot(relevant, SystemClock.uptimeMillis());
            tree.json = new JSONObject().put("package", pkg).put("fingerprint", hash.toString()).put("nodes", nodes)
                .put("uiEvents", true).put("uiRevision", change[0]).put("uiQuietMs", change[1]).put("truncated", tree.truncated);
            return tree;
        } catch (Exception e) { tree.close(); throw e; }
    }

    private Point displaySize() throws Exception {
        Object manager = Class.forName("android.hardware.display.DisplayManagerGlobal").getMethod("getInstance").invoke(null);
        Object info = manager.getClass().getMethod("getDisplayInfo", int.class).invoke(manager, targetDisplay.get());
        return new Point(info.getClass().getField("logicalWidth").getInt(info), info.getClass().getField("logicalHeight").getInt(info));
    }

    private AccessibilityNodeInfo activeRoot() {
        AccessibilityNodeInfo root = null;
        List<AccessibilityWindowInfo> windows = automation.getWindows();
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            try {
                @SuppressWarnings("unchecked") SparseArray<List<AccessibilityWindowInfo>> all = (SparseArray<List<AccessibilityWindowInfo>>) UiAutomation.class.getMethod("getWindowsOnAllDisplays").invoke(automation);
                windows = all.get(targetDisplay.get(), Collections.emptyList());
            } catch (ReflectiveOperationException error) { throw new IllegalStateException("系统不支持虚拟屏读屏", error); }
        }
        List<Integer> ids = new ArrayList<>();
        StringBuilder structure = new StringBuilder();
        for (AccessibilityWindowInfo window : windows) {
            if (window.getType() == AccessibilityWindowInfo.TYPE_APPLICATION || window.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                if (window.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) ids.add(window.getId());
                Rect bounds = new Rect(); window.getBoundsInScreen(bounds);
                structure.append(window.getId()).append(':').append(window.getType()).append(':').append(window.isFocused()).append(':').append(bounds.toShortString()).append('|');
            }
        }
        observedWindows.set(ids); windowSignature.set(structure.toString());
        for (AccessibilityWindowInfo window : windows) {
            if (window.getType() == AccessibilityWindowInfo.TYPE_APPLICATION && window.isFocused()) { root = window.getRoot(); break; }
        }
        if (root == null && android.os.Build.VERSION.SDK_INT >= 30) {
            for (AccessibilityWindowInfo window : windows) if (window.getType() == AccessibilityWindowInfo.TYPE_APPLICATION) { root = window.getRoot(); if (root != null) break; }
        }
        if (root == null && android.os.Build.VERSION.SDK_INT < 30 && targetDisplay.get() == 0) root = automation.getRootInActiveWindow();
        return root;
    }

    private void visit(AccessibilityNodeInfo node, Tree tree, JSONArray output, StringBuilder signature, int depth, Integer parentId) throws Exception {
        tree.all.add(node);
        if (tree.all.size() > 1500 || depth > 40) { tree.truncated = true; tree.incomplete = true; return; }
        Integer currentParent = parentId;
        if (node.isVisibleToUser()) {
            String raw = join(node.getText(), node.getContentDescription(), node.getHintText());
            if (raw.length() >= 300) tree.truncated = true;
            boolean sensitive = node.isPassword() || (node.isEditable() && SENSITIVE.matcher(raw).find());
            String text = sensitive ? "[受保护内容]" : raw;
            tree.sensitive |= sensitive;
            boolean clickable = node.isClickable();
            boolean editable = node.isEditable();
            boolean scrollable = node.isScrollable();
            Rect bounds = new Rect(); node.getBoundsInScreen(bounds);
            signature.append('|').append(parentId).append(':').append(node.isCheckable()).append(':').append(node.getViewIdResourceName()).append(':').append(node.getClassName()).append(':').append(bounds.toShortString()).append(':').append(text).append(':').append(clickable).append(editable).append(scrollable).append(':').append(node.isEnabled()).append(':').append(node.isChecked()).append(':').append(node.isSelected());
            int id = output.length() + 1; tree.nodes.put(id, node); currentParent = id;
            output.put(new JSONObject().put("id", id).put("text", text).put("editable", editable).put("clickable", clickable).put("scrollable", scrollable).put("sensitive", sensitive)
                .put("resourceId", node.getViewIdResourceName() == null ? "" : node.getViewIdResourceName()).put("className", String.valueOf(node.getClassName()))
                .put("parentId", parentId == null ? JSONObject.NULL : parentId).put("enabled", node.isEnabled())
                .put("checked", node.isCheckable() ? node.isChecked() : JSONObject.NULL).put("selected", node.isSelected())
                .put("bounds", new JSONObject().put("left", Math.max(0,bounds.left)*1000/tree.width).put("top", Math.max(0,bounds.top)*1000/tree.height)
                    .put("right", Math.min(tree.width,bounds.right)*1000/tree.width).put("bottom", Math.min(tree.height,bounds.bottom)*1000/tree.height)));
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            if (tree.all.size() >= 1500) { tree.truncated = true; tree.incomplete = true; break; }
            AccessibilityNodeInfo child = node.getChild(i); if (child != null) visit(child, tree, output, signature, depth + 1, currentParent);
        }
    }

    private static String join(CharSequence... values) {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (CharSequence value : values) if (value != null && value.length() > 0) unique.add(value.toString());
        String result = String.join(" ", unique);
        return result.substring(0, Math.min(result.length(), 300));
    }
    private void requireTaskId(String id) { if (!id.matches("[a-f0-9-]{36}")) throw new IllegalArgumentException("Invalid session"); }
    private void checkTask(String id) { requireTaskId(id); if (!Boolean.FALSE.equals(tasks.get(id))) throw new IllegalStateException("任务已停止或连接已失效"); }
    private static String readLine(InputStream input) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int n = 0; n < 65_536; n++) { int b = input.read(); if (b < 0 || b == '\n') return out.toString(StandardCharsets.UTF_8.name()); out.write(b); }
        throw new IOException("Request too large");
    }
    private static void write(Socket socket, JSONObject json) throws IOException {
        socket.getOutputStream().write((json.toString() + "\n").getBytes(StandardCharsets.UTF_8));
        socket.getOutputStream().flush();
    }
    private static final class Tree implements AutoCloseable {
        int width, height;
        JSONObject json;
        boolean sensitive, truncated, incomplete;
        final Map<Integer, AccessibilityNodeInfo> nodes = new HashMap<>();
        final List<AccessibilityNodeInfo> all = new ArrayList<>();
        @SuppressWarnings("deprecation") public void close() { for (AccessibilityNodeInfo node : all) node.recycle(); }
    }
    private static final class Frame {
        final String id, fingerprint; final int width, height; final long time = SystemClock.uptimeMillis();
        Frame(String id, String fingerprint, int width, int height) { this.id=id; this.fingerprint=fingerprint; this.width=width; this.height=height; }
    }
}
