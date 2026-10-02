package com.xrglasses.launcher;

/** Live connection evidence, deliberately independent of persisted application history. */
public final class ExternalRuntimeState {
    public enum Presence { UNKNOWN, EMPTY, DESKTOP, APPLICATION }
    private int displayId = -1;
    private long generation;
    private boolean pending;
    private Presence presence = Presence.UNKNOWN;

    public void connect(int id) {
        generation++;
        displayId = id;
        pending = false;
        presence = Presence.UNKNOWN;
    }
    public void disconnect() { connect(-1); }
    public void observe(Presence value) { presence = value; }
    public Presence presence() { return presence; }
    public void cancelRequest() { generation++; pending = false; }
    public long beginRequest() { pending = true; return ++generation; }
    public void finishRequest(long token) { if (token == generation) pending = false; }
    public long generation() { return generation; }
    public boolean isCurrent(long token, int id) { return token == generation && id > 0 && id == displayId; }
    public boolean isPending() { return pending; }
    public boolean shouldOpenDesktop() {
        return displayId > 0 && !pending && presence == Presence.EMPTY;
    }
}
