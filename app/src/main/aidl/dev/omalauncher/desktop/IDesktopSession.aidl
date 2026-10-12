package dev.omalauncher.desktop;
import android.view.KeyEvent;

/** Bound to the opening UID, package and signing certificate; invalid after close or revocation. */
interface IDesktopSession {
    void resize(int width, int height);
    void setVisible(boolean visible);
    void search(String query, boolean submit);
    void action(String action);
    boolean key(in KeyEvent event);
    void close();
}
