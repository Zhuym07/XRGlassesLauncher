package com.xrglasses.launcher;

import java.util.List;

public interface ExternalAppHistory {
    ActiveExternalApp getActiveApp();
    List<ActiveExternalApp> getRecentApps(int limit);
    void recordConfirmedLaunch(ActiveExternalApp app);
    void clearActiveApp();
}
