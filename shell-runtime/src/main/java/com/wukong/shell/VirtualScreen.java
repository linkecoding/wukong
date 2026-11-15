package com.wukong.shell;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Base64;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Shell-owned independent display. App receives frames, never privileged Surface handles. */
final class VirtualScreen implements AutoCloseable {
    final int width, height, density;
    final int id;
    volatile boolean manual;
    String ownerId = "";
    private final ImageReader reader;
    private final VirtualDisplay display;
    private final HandlerThread events;
    private Bitmap latest;
    private long revision;
    private long encodedRevision = -1;
    private String encoded;
    private boolean closed;
    private static Context shellContext;
    private static HandlerThread contextEvents;

    private static synchronized Context context() throws Exception {
        if (shellContext != null) return shellContext;
        if (contextEvents == null) { contextEvents = new HandlerThread("wukong-system-context"); contextEvents.start(); }
        FutureTask<Context> initialize = new FutureTask<>(() -> {
            Object activityThread = Class.forName("android.app.ActivityThread").getMethod("systemMain").invoke(null);
            Context system = (Context) activityThread.getClass().getMethod("getSystemContext").invoke(activityThread);
            return system.createPackageContext("com.android.shell", Context.CONTEXT_IGNORE_SECURITY);
        });
        new Handler(contextEvents.getLooper()).post(initialize);
        shellContext = initialize.get(10, TimeUnit.SECONDS);
        return shellContext;
    }

    VirtualScreen() throws Exception {
        Object global = Class.forName("android.hardware.display.DisplayManagerGlobal").getMethod("getInstance").invoke(null);
        Object physical = global.getClass().getMethod("getDisplayInfo", int.class).invoke(global, 0);
        width = physical.getClass().getField("logicalWidth").getInt(physical);
        height = physical.getClass().getField("logicalHeight").getInt(physical);
        density = physical.getClass().getField("logicalDensityDpi").getInt(physical);
        Context shell = context();
        DisplayManager manager = (DisplayManager) DisplayManager.class.getConstructor(Context.class).newInstance(shell);
        events = new HandlerThread("wukong-virtual-frames"); events.start();
        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3);
        reader.setOnImageAvailableListener(source -> {
            synchronized (this) {
            if (closed) return;
            try (Image image = source.acquireLatestImage()) {
                if (image == null) return;
                Image.Plane plane = image.getPlanes()[0];
                int paddedWidth = plane.getRowStride() / plane.getPixelStride();
                Bitmap padded = Bitmap.createBitmap(paddedWidth, image.getHeight(), Bitmap.Config.ARGB_8888);
                padded.copyPixelsFromBuffer(plane.getBuffer());
                Bitmap frame = Bitmap.createBitmap(padded, 0, 0, width, height);
                if (frame == padded) frame = padded.copy(Bitmap.Config.ARGB_8888, false);
                padded.recycle();
                synchronized (this) { if (latest != null) latest.recycle(); latest = frame; revision++; }
            } catch (Exception ignored) { /* A failed frame is retried by the next compositor update. */ }
            }
        }, new Handler(events.getLooper()));
        int flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC | DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY;
        for (String flag : new String[]{"VIRTUAL_DISPLAY_FLAG_TRUSTED", "VIRTUAL_DISPLAY_FLAG_OWN_FOCUS", "VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH", "VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL"}) {
            try { flags |= DisplayManager.class.getField(flag).getInt(null); } catch (ReflectiveOperationException ignored) { }
        }
        VirtualDisplay created;
        try { created = manager.createVirtualDisplay("Wukong", width, height, density, reader.getSurface(), flags); }
        catch (Exception error) { reader.close(); events.quitSafely(); throw error; }
        if (created == null) { reader.close(); events.quitSafely(); throw new IllegalStateException("系统未允许创建虚拟屏"); }
        display = created; id = display.getDisplay().getDisplayId();
        try {
            Object windows = Class.forName("android.view.WindowManagerGlobal").getMethod("getWindowManagerService").invoke(null);
            Class.forName("android.view.IWindowManager").getMethod("setDisplayImePolicy", int.class, int.class).invoke(windows, id, 0);
        } catch (ReflectiveOperationException ignored) { /* App-side manual text entry remains available. */ }
    }
    synchronized Bitmap capture() { return latest == null ? null : latest.copy(Bitmap.Config.ARGB_8888, false); }
    synchronized JSONObject status(boolean includeFrame) throws Exception {
        JSONObject result = new JSONObject().put("displayId", id).put("width", width).put("height", height).put("manual", manual).put("revision", revision);
        if (includeFrame && latest != null) {
            if (encodedRevision != revision) {
                double scale = Math.min(1d,1600d/Math.max(width,height));
                Bitmap preview = Bitmap.createScaledBitmap(latest,Math.max(1,(int)(width*scale)),Math.max(1,(int)(height*scale)),true);
                ByteArrayOutputStream bytes = new ByteArrayOutputStream(); preview.compress(Bitmap.CompressFormat.JPEG, 60, bytes);
                if (preview != latest) preview.recycle();
                encoded = Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP); encodedRevision = revision;
            }
            result.put("jpeg", encoded);
        }
        return result;
    }
    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        display.release(); reader.close(); events.quitSafely();
        if (latest != null) latest.recycle(); latest = null; encoded = null;
    }
}
