package com.xrglasses.launcher;

public interface TouchpadRegionListener {
    void onMultitaskClick();
    default void onExitClick() { }
    default void onExternalBackClick() { }
}
