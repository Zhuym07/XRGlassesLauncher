package com.xrglasses.launcher;

import org.junit.Test;
import static org.junit.Assert.*;

public class RootBackTest {
    private static final class FakeRoot implements RootBridge.CommandExecutor {
        final RootBridge bridge = new RootBridge(this);
        int calls; long timeout;
        RootBridge.Status status;
        FakeRoot(RootBridge.Status status) { this.status = status; }
        @Override public RootBridge.Result execute(String command, long timeoutMs) {
            calls++; timeout = timeoutMs;
            return new RootBridge.Result(status, status == RootBridge.Status.SUCCESS ? 0 : -1, "", "test");
        }
    }
    @Test public void everyCommandTargetsExternalDisplayBeforeKeyevent() {
        for (String command : RootBridge.externalBackCommands(47)) {
            assertTrue(command.contains("-d 47 keyevent "));
        }
    }
    @Test public void noInputForInvalidDisplay() {
        FakeRoot r = new FakeRoot(RootBridge.Status.SUCCESS);
        assertFalse(r.bridge.executeExternalBack(0).ok()); assertFalse(r.bridge.executeExternalBack(-1).ok());
        assertEquals(0, r.calls);
    }
    @Test public void successSendsOnlyOneBack() {
        FakeRoot r = new FakeRoot(RootBridge.Status.SUCCESS);
        assertTrue(r.bridge.executeExternalBack(9).ok()); assertEquals(1, r.calls);
    }
    @Test public void deniedDoesNotRetryAuthorization() {
        FakeRoot r = new FakeRoot(RootBridge.Status.ROOT_DENIED);
        assertFalse(r.bridge.executeExternalBack(9).ok()); assertEquals(1, r.calls);
    }
    @Test public void timeoutDoesNotRetryAndAllowsTimeForPrompt() {
        FakeRoot r = new FakeRoot(RootBridge.Status.TIMEOUT);
        assertEquals(RootBridge.Status.TIMEOUT, r.bridge.executeExternalBack(9).status);
        assertEquals(1, r.calls); assertTrue(r.timeout >= 10000);
    }
    @Test public void unsupportedCommandsTryOnlyDisplayTargetedAlternatives() {
        FakeRoot r = new FakeRoot(RootBridge.Status.COMMAND_UNSUPPORTED);
        assertEquals(RootBridge.Status.COMMAND_UNSUPPORTED, r.bridge.executeExternalBack(9).status);
        assertEquals(4, r.calls);
    }
}
