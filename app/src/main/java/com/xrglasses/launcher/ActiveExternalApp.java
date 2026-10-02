package com.xrglasses.launcher;

public final class ActiveExternalApp {
    public final String packageName;
    public final String className;
    public final String label;
    public final long lastConfirmedAt;

    public ActiveExternalApp(String packageName, String className, String label, long lastConfirmedAt) {
        this.packageName = packageName == null ? "" : packageName;
        this.className = className == null ? "" : className;
        this.label = label == null ? this.packageName : label;
        this.lastConfirmedAt = lastConfirmedAt;
    }

    public String componentKey() {
        return packageName + "/" + className;
    }
}
