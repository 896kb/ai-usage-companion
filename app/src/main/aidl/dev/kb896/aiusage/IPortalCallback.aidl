package dev.kb896.aiusage;

import android.os.ParcelFileDescriptor;

// From the shell service to RearActivity, in the :rear process. oneway: the service's
// touch thread must never wait on the panel.
oneway interface IPortalCallback {
    // A pull relayed from the rear touchscreen while a guest had the display:
    // phase 0 started, 1 moved, 2 lifted, 3 cancelled; y in panel pixels.
    void onPull(int phase, float y) = 1;
    // The frozen frame for the backdrop, a PNG to read to the end and close.
    void onFrame(in ParcelFileDescriptor png) = 2;
    // The guest: its package ("" for none) and whether it is on top of the rear.
    void onGuest(String pkg, boolean onTop) = 3;
}
