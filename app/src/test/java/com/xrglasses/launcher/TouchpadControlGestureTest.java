package com.xrglasses.launcher;

import org.junit.Test;
import static org.junit.Assert.*;

public class TouchpadControlGestureTest {
    private TouchpadControlGesture start(TouchSurfaceRegion region) {
        TouchpadControlGesture g = new TouchpadControlGesture();
        g.begin(region, 100, 100, 1000);
        return g;
    }
    @Test public void multitaskShortTap() {
        assertEquals(TouchSurfaceRegion.MULTITASK, start(TouchSurfaceRegion.MULTITASK).finish(100, 100, 1200, 8));
    }
    @Test public void exitShortTap() {
        assertEquals(TouchSurfaceRegion.EXIT, start(TouchSurfaceRegion.EXIT).finish(100, 100, 1200, 8));
    }
    @Test public void longPressCancelled() {
        assertEquals(TouchSurfaceRegion.TOUCHPAD, start(TouchSurfaceRegion.MULTITASK).finish(100, 100, 1500, 8));
    }
    @Test public void movementCancelledEvenAfterReturn() {
        TouchpadControlGesture g = start(TouchSurfaceRegion.MULTITASK);
        g.move(120, 100, 1, TouchSurfaceRegion.MULTITASK, 8);
        assertEquals(TouchSurfaceRegion.TOUCHPAD, g.finish(100, 100, 1200, 8));
    }
    @Test public void extraFingerCancels() {
        TouchpadControlGesture g = start(TouchSurfaceRegion.MULTITASK);
        g.move(100, 100, 2, TouchSurfaceRegion.MULTITASK, 8);
        assertEquals(TouchSurfaceRegion.TOUCHPAD, g.finish(100, 100, 1200, 8));
    }
    @Test public void regionCrossingCancels() {
        TouchpadControlGesture g = start(TouchSurfaceRegion.MULTITASK);
        g.move(101, 100, 1, TouchSurfaceRegion.TOUCHPAD, 8);
        assertEquals(TouchSurfaceRegion.TOUCHPAD, g.finish(101, 100, 1200, 8));
    }
    @Test public void backShortTapTriggers() {
        assertEquals(TouchSurfaceRegion.EXTERNAL_BACK, start(TouchSurfaceRegion.EXTERNAL_BACK).finish(100, 100, 1200, 8));
    }
    @Test public void backSwipeDoesNotTrigger() {
        assertEquals(TouchSurfaceRegion.TOUCHPAD, start(TouchSurfaceRegion.EXTERNAL_BACK).finish(160, 100, 1200, 8));
    }
    @Test public void backLongPressDoesNotTrigger() {
        assertEquals(TouchSurfaceRegion.TOUCHPAD, start(TouchSurfaceRegion.EXTERNAL_BACK).finish(100, 100, 1500, 8));
    }
    @Test public void backExtraFingerDoesNotTrigger() {
        TouchpadControlGesture g = start(TouchSurfaceRegion.EXTERNAL_BACK);
        g.move(100, 100, 2, TouchSurfaceRegion.EXTERNAL_BACK, 8);
        assertEquals(TouchSurfaceRegion.TOUCHPAD, g.finish(100, 100, 1200, 8));
    }
    @Test public void backMovementCancelledEvenAfterReturn() {
        TouchpadControlGesture g = start(TouchSurfaceRegion.EXTERNAL_BACK);
        g.move(120, 100, 1, TouchSurfaceRegion.EXTERNAL_BACK, 8);
        assertEquals(TouchSurfaceRegion.TOUCHPAD, g.finish(100, 100, 1200, 8));
    }
    @Test public void verticalSwipeCancelled() {
        TouchpadControlGesture g = start(TouchSurfaceRegion.EXTERNAL_BACK);
        g.move(200, 140, 1, TouchSurfaceRegion.EXTERNAL_BACK, 8);
        assertEquals(TouchSurfaceRegion.TOUCHPAD, g.finish(200, 100, 1200, 8));
    }
    @Test public void cancelPreventsAction() {
        TouchpadControlGesture g = start(TouchSurfaceRegion.EXIT);
        g.cancel();
        assertEquals(TouchSurfaceRegion.TOUCHPAD, g.finish(100, 100, 1200, 8));
    }
}
