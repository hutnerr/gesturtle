package com.gesturtle.library;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 content hashing, the basis of the content-addressed library layout. */
public class HashService {
    private static final java.util.regex.Pattern HASH = java.util.regex.Pattern.compile("[0-9a-f]{64}");

    /** Whether s looks like a hash this class made: 64 lowercase hex characters, so it's safe in a file path. */
    public static boolean isValidHash(String s) {
        return s != null && HASH.matcher(s).matches();
    }

    public String hashFile(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
