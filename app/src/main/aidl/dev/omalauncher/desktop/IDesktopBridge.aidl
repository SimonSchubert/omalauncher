package dev.omalauncher.desktop;
import android.os.Bundle;
import android.os.IBinder;
import dev.omalauncher.desktop.IDesktopHost;

/** All replies carry status and protocol. Only openSession may return private content. */
interface IDesktopBridge {
    Bundle negotiate(int protocol);
    Bundle requestConsent();
    void revokeAccess();
    Bundle openSession(int protocol, in Bundle metrics, IBinder hostToken, IDesktopHost host);
}
