package dev.riderlan.core;

/** Callbacks may occur on networking threads; Swing listeners must marshal events onto the EDT. */
public interface SessionListener {
    void onPacket(Packet packet);
    void onClosed(String reason);
}
