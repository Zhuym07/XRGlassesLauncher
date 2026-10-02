package com.xrglasses.launcher;

import org.junit.Test;
import static org.junit.Assert.*;

public class ExternalRuntimeStateTest {
    private ExternalRuntimeState connected() {
        ExternalRuntimeState r = new ExternalRuntimeState(); r.connect(9); return r;
    }
    @Test public void liveAppPreventsAutomaticDesktop() {
        ExternalRuntimeState r = connected(); r.observe(ExternalRuntimeState.Presence.APPLICATION);
        assertFalse(r.shouldOpenDesktop());
    }
    @Test public void emptyDisplayAllowsDesktopRegardlessOfHistoricalApp() {
        ExternalRuntimeState r = connected(); r.observe(ExternalRuntimeState.Presence.EMPTY);
        assertTrue(r.shouldOpenDesktop());
    }
    @Test public void unknownDoesNotMeanEmpty() { assertFalse(connected().shouldOpenDesktop()); }
    @Test public void visibleDesktopDoesNotRelaunch() {
        ExternalRuntimeState r = connected(); r.observe(ExternalRuntimeState.Presence.DESKTOP);
        assertFalse(r.shouldOpenDesktop());
    }
    @Test public void disconnectInvalidatesLiveEvidence() {
        ExternalRuntimeState r = connected(); r.observe(ExternalRuntimeState.Presence.APPLICATION);
        r.disconnect(); assertEquals(ExternalRuntimeState.Presence.UNKNOWN, r.presence());
        assertFalse(r.shouldOpenDesktop());
        r.connect(10); r.observe(ExternalRuntimeState.Presence.EMPTY); assertTrue(r.shouldOpenDesktop());
    }
    @Test public void idReplacementInvalidatesLaunchVerification() {
        ExternalRuntimeState r = connected(); long token = r.beginRequest();
        r.connect(10); assertFalse(r.isCurrent(token, 9)); assertFalse(r.isPending());
    }
    @Test public void sameIdReconnectionAlsoInvalidatesOldVerification() {
        ExternalRuntimeState r = connected(); long token = r.beginRequest();
        r.disconnect(); r.connect(9); assertFalse(r.isCurrent(token, 9));
    }
    @Test public void restorationPendingPreventsDesktop() {
        ExternalRuntimeState r = connected(); r.observe(ExternalRuntimeState.Presence.EMPTY);
        long token = r.beginRequest(); assertFalse(r.shouldOpenDesktop());
        r.finishRequest(token); assertTrue(r.shouldOpenDesktop());
    }
    @Test public void oldCompletionDoesNotFinishNewLaunch() {
        ExternalRuntimeState r = connected(); long old = r.beginRequest(); long current = r.beginRequest();
        r.finishRequest(old); assertTrue(r.isPending());
        r.finishRequest(current); assertFalse(r.isPending());
    }
    @Test public void manualRequestSupersedesDeferredRestore() {
        ExternalRuntimeState r = connected(); long restore = r.beginRequest();
        r.cancelRequest(); assertFalse(r.isCurrent(restore, 9)); assertFalse(r.isPending());
    }
    @Test public void disconnectedDisplayCannotMatchToken() {
        ExternalRuntimeState r = connected(); long token = r.beginRequest();
        assertFalse(r.isCurrent(token, 0)); assertFalse(r.isCurrent(token, -1));
    }
}
