package com.xrglasses.launcher;
import android.view.Display;
public final class SessionState {
    public enum DisplayStatus { DISCONNECTED, CONNECTED, INVALID }
    public enum DesktopStatus { DISPLAY_DISCONNECTED, DISPLAY_READY, RUNNING_EXTERNAL_APP, WAITING_FOR_RECONNECT, TOUCHPAD_ACTIVE }
    public DisplayStatus displayStatus = DisplayStatus.DISCONNECTED;
    public DesktopStatus desktopStatus = DesktopStatus.DISPLAY_DISCONNECTED;
    public int displayId = Display.DEFAULT_DISPLAY;
    public int displayWidth = 1;
    public int displayHeight = 1;
    public float pointerX = 0;
    public float pointerY = 0;
    public String uniqueId = "";
    public ExternalRuntimeState.Presence externalPresence = ExternalRuntimeState.Presence.UNKNOWN;
    public String runningExternalPackage = "";
    public String runningExternalLabel = "";
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
    public String lastDisconnectAt = "";
    public String lastDisconnectApp = "";
}
