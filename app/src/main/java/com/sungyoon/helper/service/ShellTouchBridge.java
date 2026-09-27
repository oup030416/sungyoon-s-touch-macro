package com.sungyoon.helper.service;

import android.content.AttributionSource;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;
import java.util.Timer;
import java.util.TimerTask;
import java.lang.reflect.Method;

/** Started explicitly by adb as shell; never runs with the application's identity. */
@android.annotation.TargetApi(33)
public final class ShellTouchBridge extends Binder {
    public static final int OPEN = IBinder.FIRST_CALL_TRANSACTION;
    public static final int FRAME = OPEN + 1;
    public static final int CLOSE = OPEN + 2;
    private final int allowedUid;
    private final Object manager;
    private final Method inject;
    private MotionEvent last;
    private long lastInput;
    private boolean opened;
    private long session;

    private ShellTouchBridge(int uid, Object manager, Method inject) {
        this.allowedUid = uid;
        this.manager = manager;
        this.inject = inject;
        new Timer("touch-watchdog", true).schedule(new TimerTask() {
            @Override public void run() {
                synchronized (ShellTouchBridge.this) {
                    if (opened && SystemClock.uptimeMillis() - lastInput > 2000) close();
                }
            }
        }, 500, 500);
    }
    public static final int VERSION = 1;

    public static void main(String[] args) throws Exception {
        if (android.os.Process.myUid() != 2000 || args.length != 1) {
            throw new SecurityException("Start with adb and the installed application UID");
        }
        int allowedUid = Integer.parseInt(args[0]);
        if (allowedUid < 10000) throw new SecurityException("Invalid application UID");
        Class<?> managerClass = Class.forName(android.os.Build.VERSION.SDK_INT >= 34
                ? "android.hardware.input.InputManagerGlobal" : "android.hardware.input.InputManager");
        Object manager = managerClass.getMethod("getInstance").invoke(null);
        Method inject = managerClass.getMethod("injectInputEvent", InputEvent.class, int.class);
        Looper.prepareMainLooper();
        ShellTouchBridge bridge = new ShellTouchBridge(allowedUid, manager, inject);
        Bundle extras = new Bundle();
        extras.putBinder("bridge", bridge);
        String authority = "com.sungyoon.helper.touchbridge";
        Object activityManager = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null);
        Binder token = new Binder();
        Object holder = activityManager.getClass().getMethod("getContentProviderExternal",
                String.class, int.class, IBinder.class, String.class)
                .invoke(activityManager, authority, allowedUid / 100000, token, null);
        if (holder == null) throw new IllegalStateException("Application provider unavailable");
        Bundle reply;
        try {
            Object provider = holder.getClass().getField("provider").get(holder);
            AttributionSource source = new AttributionSource.Builder(2000).setPackageName("com.android.shell").build();
            reply = (Bundle) Class.forName("android.content.IContentProvider").getMethod("call",
                    AttributionSource.class, String.class, String.class, String.class, Bundle.class)
                    .invoke(provider, source, authority, "register", null, extras);
        } finally {
            activityManager.getClass().getMethod("removeContentProviderExternal", String.class, IBinder.class)
                    .invoke(activityManager, authority, token);
        }
        if (reply == null || reply.getBinder("lifetime") == null) throw new IllegalStateException("No app connection");
        reply.getBinder("lifetime").linkToDeath(() -> {
            synchronized (bridge) { bridge.close(); }
            System.exit(0);
        }, 0);
        System.out.println("TOUCH_BRIDGE_READY uid=" + allowedUid);
        Looper.loop();
    }

    @Override protected synchronized boolean onTransact(int code, Parcel input, Parcel output, int flags) {
        if (Binder.getCallingUid() != allowedUid) throw new SecurityException("Wrong application UID");
        if (code == OPEN) {
            close();
            opened = true;
            session++;
            lastInput = SystemClock.uptimeMillis();
            output.writeNoException();
            output.writeInt(VERSION);
            output.writeLong(session);
            return true;
        }
        if (code != CLOSE && code != FRAME) return false;
        long requestSession = input.readLong();
        if (code == CLOSE) {
            if (requestSession == session) close();
            output.writeNoException();
            return true;
        }
        if (requestSession != session || !opened) throw new IllegalStateException("No active input session");
        try {
            lastInput = SystemClock.uptimeMillis();
            int action = input.readInt();
            if (action == -1) { // Heartbeat / completion barrier.
                output.writeNoException();
                output.writeInt(1);
                return true;
            }
            long downTime = input.readLong();
            int count = input.readInt();
            if (count < 1 || count > 16) throw new IllegalArgumentException("Invalid contact count");
            MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[count];
            MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[count];
            int usedIds = 0;
            for (int i = 0; i < count; i++) {
                int id = input.readInt();
                float x = input.readFloat();
                float y = input.readFloat();
                if (id < 0 || id > 31 || (usedIds & (1 << id)) != 0 ||
                        !Float.isFinite(x) || !Float.isFinite(y) || x < 0 || y < 0) {
                    throw new IllegalArgumentException("Invalid contact");
                }
                usedIds |= 1 << id;
                properties[i] = new MotionEvent.PointerProperties();
                properties[i].id = id;
                properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
                coords[i] = new MotionEvent.PointerCoords();
                coords[i].x = x;
                coords[i].y = y;
                coords[i].pressure = 1;
                coords[i].size = 1;
            }
            MotionEvent event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                    count, properties, coords, 0, 0, 1, 1, -1, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
            boolean accepted;
            try {
                accepted = (Boolean) inject.invoke(manager, event, 1);
                if (last != null) last.recycle();
                last = MotionEvent.obtain(event);
            } finally {
                event.recycle();
            }
            output.writeNoException();
            output.writeInt(accepted ? 1 : 0);
            if (!accepted) throw new IllegalStateException("Input rejected");
            if ((action & MotionEvent.ACTION_MASK) == MotionEvent.ACTION_UP ||
                    (action & MotionEvent.ACTION_MASK) == MotionEvent.ACTION_CANCEL) {
                last.recycle();
                last = null;
            }
            return true;
        } catch (Exception error) {
            close();
            throw new IllegalStateException("Touch injection failed", error);
        }
    }

    private void close() {
        opened = false;
        if (last != null) {
            try {
                last.setAction(MotionEvent.ACTION_CANCEL);
                inject.invoke(manager, last, 0);
            } catch (Exception error) {
                System.err.println("Could not release input: " + error.getClass().getSimpleName());
            } finally { last.recycle(); last = null; }
        }
    }
}
