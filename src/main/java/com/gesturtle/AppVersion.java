package com.gesturtle;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Gesturtle's version, major.feature.bug, from pom.xml (Maven writes it into
 * version.properties). A "-beta" suffix means it hasn't been released yet.
 */
public final class AppVersion {
    private static final String VERSION = load();

    private AppVersion() {
    }

    /** E.g. "1.0.0" or "1.1.0 beta"; "dev" when run without Maven's resource step. */
    public static String display() {
        return VERSION.replace('-', ' ');
    }

    private static String load() {
        try (InputStream in = AppVersion.class.getResourceAsStream("/version.properties")) {
            if (in == null) return "dev";
            Properties props = new Properties();
            props.load(in);
            String v = props.getProperty("version", "");
            return v.isBlank() || v.startsWith("${") ? "dev" : v;
        } catch (IOException e) {
            return "dev";
        }
    }
}
