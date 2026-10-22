package dev.omalauncher.packageinstall;

import android.os.Bundle;

interface IAdbShellService {
    void destroy() = 16777114;

    boolean authorize(String requestId, String capability, String commandDigest) = 1;

    Bundle executeOnce(String requestId, String capability, String command, int timeoutMs, int maxOutputBytes) = 2;
}
