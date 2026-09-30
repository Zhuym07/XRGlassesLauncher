package com.xrglasses.launcher;
import android.view.Display;
public final class SessionState {
    public enum DisplayStatus { DISCONNECTED, CONNECTED, INVALID }
    public DisplayStatus displayStatus = DisplayStatus.DISCONNECTED;
    public int displayId = Display.DEFAULT_DISPLAY;
    public int displayWidth = 1;
    public int displayHeight = 1;
    public float pointerX = 0;
    public float pointerY = 0;
    public String uniqueId = "";
    public String lastError = "";
    public boolean launcherVisible;
    public boolean touchpadMode;
    public boolean rootAvailable;
    public boolean accessibilityEnabled;
    public String inputBackend = "none";
    public String lastInputResult = "";
    public String lastObservedPackage = "";
    public String lastObservedClass = "";
    public int lastObservedDisplayId = -1;
}
