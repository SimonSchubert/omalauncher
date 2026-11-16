package dev.omalauncher.desktop;
import android.content.ComponentName;
import android.os.UserHandle;

/** Events always identify their originating session. Never launch a work app as the owner. */
oneway interface IDesktopHost {
    void onAndroidLaunch(String sessionId, in ComponentName component, in UserHandle user, long profileSerial);
    void onStatus(String sessionId, String status, String message);
}
