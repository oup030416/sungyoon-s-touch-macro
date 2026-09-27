package com.sungyoon.helper.service;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;

/** Session-only bootstrap endpoint. Only the explicitly started adb shell can register a bridge. */
public final class ShellTouchProvider extends ContentProvider {
    private static volatile IBinder bridge;
    private static final Binder lifetime = new Binder();

    public static IBinder getBridge() {
        IBinder current = bridge;
        return current != null && current.isBinderAlive() ? current : null;
    }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        if (Binder.getCallingUid() != 2000) {
            throw new SecurityException("Only adb shell can register the touch bridge");
        }
        if ("status".equals(method)) {
            Bundle status = new Bundle();
            status.putBoolean("connected", getBridge() != null);
            return status;
        }
        if (!"register".equals(method) || getBridge() != null) {
            throw new IllegalStateException("Invalid registration or bridge already connected");
        }
        IBinder candidate = extras == null ? null : extras.getBinder("bridge");
        if (candidate == null) throw new IllegalArgumentException("Missing bridge");
        bridge = candidate;
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                android.widget.Toast.makeText(getContext(), com.sungyoon.helper.R.string.touch_merge_connected,
                        android.widget.Toast.LENGTH_LONG).show());
        Bundle reply = new Bundle();
        reply.putBinder("lifetime", lifetime);
        return reply;
    }

    @Override public boolean onCreate() { return true; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
