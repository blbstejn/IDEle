package dev.riderlan.core;

import java.util.Locale;

public final class Rules {
    public static final int DEFAULT_PORT = 43137;
    public static final int MAX_PARTICIPANTS = 16;
    public static final int MAX_CHAT = 1200;
    // v2 intentionally refuses plaintext v1 peers; never downgrade encryption.
    // v3 replaced the per-game packets with the generic GAME_* packets in GamePackets.
    public static final String PROTOCOL = "3";

    private Rules() { }

    public static String username(String name) {
        if (name == null) throw new IllegalArgumentException("Enter a username");
        String clean = name.strip();
        if (clean.length() < 1 || clean.length() > 24) {
            throw new IllegalArgumentException("Usernames must contain 1–24 characters");
        }
        for (int i = 0; i < clean.length();) {
            int point = clean.codePointAt(i);
            int category = Character.getType(point);
            // Reject bidi overrides, unpaired surrogates, line separators and
            // other invisible controls that can spoof identities in chat logs.
            if (Character.isISOControl(point) || category == Character.FORMAT
                    || category == Character.SURROGATE
                    || category == Character.LINE_SEPARATOR
                    || category == Character.PARAGRAPH_SEPARATOR) {
                throw new IllegalArgumentException("Usernames cannot contain control or formatting characters");
            }
            i += Character.charCount(point);
        }
        return clean;
    }

    public static String nameKey(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    public static String message(String value) {
        if (value == null) throw new IllegalArgumentException("Missing message");
        String clean = value.strip();
        if (clean.isBlank() || clean.length() > MAX_CHAT) {
            throw new IllegalArgumentException("Messages must contain 1–" + MAX_CHAT + " characters");
        }
        return clean;
    }
}
