package dev.kb896.aiusage;

import dev.kb896.aiusage.IPortalCallback;

// RearActivity -> the main process, which holds the Shizuku binding and hands the
// callback on to the shell service. null unregisters.
interface IPortalRelay {
    void register(IPortalCallback callback) = 1;
}
