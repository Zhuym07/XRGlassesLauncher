package com.xrglasses.launcher;

import org.junit.Test;
import static org.junit.Assert.*;

public class FourFingerTapGestureTest {
    private FourFingerTapGesture start(boolean enabled, int count) {
        FourFingerTapGesture g = new FourFingerTapGesture();
        g.begin(enabled, 1000, 8);
        for (int i = 0; i < count; i++) g.pointerDown(i, i * 100, 100, 1000 + i * 20);
        return g;
    }
    @Test public void fourShortTouchesCapture() { assertTrue(start(true, 4).canCapture(1200)); }
    @Test public void threeTouchesDoNotCapture() { assertFalse(start(true, 3).canCapture(1200)); }
    @Test public void disabledDoesNotCapture() { assertFalse(start(false, 4).canCapture(1200)); }
    @Test public void fiveTouchesDoNotCapture() { assertFalse(start(true, 5).canCapture(1200)); }
    @Test public void holdDoesNotCapture() { assertFalse(start(true, 4).canCapture(1500)); }
    @Test public void movementCancellationIsPermanent() {
        FourFingerTapGesture g = start(true, 4);
        g.move(2, 220, 100);
        g.move(2, 200, 100);
        assertFalse(g.canCapture(1200));
    }
    @Test public void smallJitterDoesNotCancel() {
        FourFingerTapGesture g = start(true, 4); g.move(1, 102, 101);
        assertTrue(g.canCapture(1200));
    }
    @Test public void lateFourthFingerDoesNotCapture() {
        FourFingerTapGesture g = start(true, 3); g.pointerDown(3, 300, 100, 1250);
        assertFalse(g.canCapture(1300));
    }
    @Test public void nextGestureResetsCancellation() {
        FourFingerTapGesture g = start(true, 4); g.cancel();
        g.begin(true, 2000, 8);
        for (int i = 0; i < 4; i++) g.pointerDown(i, i * 100, 100, 2000);
        assertTrue(g.canCapture(2200));
    }
}
