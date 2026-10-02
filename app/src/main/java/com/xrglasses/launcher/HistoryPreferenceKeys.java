package com.xrglasses.launcher;

/** Clearing recents must never erase the independent active-app or text draft. */
public final class HistoryPreferenceKeys {
    private HistoryPreferenceKeys() {}
    public static boolean isRecent(String key) {
        return key != null && (key.startsWith("recent:") || key.startsWith("recent_component:") || key.startsWith("recent_label:"));
    }
}
