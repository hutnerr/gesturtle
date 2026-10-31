package com.gesturtle.library;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;

/**
 * Turns images that don't start as files (a link, a browser drag, a pasted
 * bitmap) into files in a temp folder, so they go through the same staging
 * and import as everything else. Downloads run on the caller's thread; call
 * from a background one.
 */
public final class IncomingImages {
    private static final Map<String, String> EXTENSIONS = Map.of(
            "image/png", "png", "image/jpeg", "jpg", "image/jpg", "jpg", "image/gif", "gif",
            "image/bmp", "bmp", "image/x-ms-bmp", "bmp", "image/webp", "webp");
    private static final long MAX_BYTES = 100L * 1024 * 1024;
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    private static Path tempDir;

    private IncomingImages() {
    }

    /** http(s) links and data: image URIs, which is what a browser hands over when an image is dragged or copied. */
    public static boolean isImageLink(String text) {
        if (text == null) return false;
        String t = text.strip().toLowerCase(Locale.ROOT);
        return t.startsWith("http://") || t.startsWith("https://") || t.startsWith("data:image/");
    }

    /**
     * Fetches the image a link points at. The type comes from the server's
     * Content-Type, falling back to the link's extension, and has to be one
     * Gesturtle imports; a web page rather than an image is refused with a
     * message saying so.
     */
    public static Path download(String link) throws IOException {
        String url = link.strip();
        if (url.toLowerCase(Locale.ROOT).startsWith("data:")) return decodeDataUri(url);
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(60))
                    // Some image hosts refuse requests without a browser-like agent.
                    .header("User-Agent", "Mozilla/5.0 (Gesturtle art reference app)")
                    .header("Accept", "image/*")
                    .GET().build();
        } catch (IllegalArgumentException e) {
            throw new IOException("That doesn't look like a link: " + url);
        }
        HttpResponse<byte[]> response;
        try {
            response = HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Download interrupted");
        }
        if (response.statusCode() / 100 != 2) {
            throw new IOException("The server answered " + response.statusCode() + " for " + url);
        }
        String type = response.headers().firstValue("Content-Type").orElse("")
                .split(";")[0].strip().toLowerCase(Locale.ROOT);
        String ext = EXTENSIONS.get(type);
        if (ext == null) ext = supportedExtension(pathOf(response.uri()));
        if (ext == null) {
            throw new IOException(type.startsWith("text/html")
                    ? "That link is a web page, not an image. Right-click the image itself and copy its address."
                    : "Not an image Gesturtle can import (" + (type.isEmpty() ? "unknown type" : type) + ").");
        }
        byte[] body = response.body();
        if (body.length > MAX_BYTES) throw new IOException("The image is over 100 MB.");
        return write(baseName(pathOf(response.uri())), ext, body);
    }

    /** Writes a bitmap (e.g. from the clipboard) as a PNG. */
    public static Path savePng(BufferedImage image, String baseName) throws IOException {
        Path target = uniqueTarget(baseName, "png");
        if (!ImageIO.write(image, "png", target.toFile())) throw new IOException("Could not encode the image as PNG");
        return target;
    }

    private static Path decodeDataUri(String uri) throws IOException {
        int comma = uri.indexOf(',');
        if (comma < 0) throw new IOException("Malformed image data");
        String header = uri.substring(5, comma).toLowerCase(Locale.ROOT);
        String type = header.split(";")[0];
        String ext = EXTENSIONS.get(type);
        if (ext == null) throw new IOException("Not an image Gesturtle can import (" + type + ").");
        String payload = uri.substring(comma + 1);
        byte[] bytes = header.contains(";base64")
                ? Base64.getMimeDecoder().decode(payload)
                : URLDecoder.decode(payload, StandardCharsets.UTF_8).getBytes(StandardCharsets.ISO_8859_1);
        return write("dropped-image", ext, bytes);
    }

    private static Path write(String baseName, String ext, byte[] bytes) throws IOException {
        Path target = uniqueTarget(baseName, ext);
        Files.write(target, bytes);
        return target;
    }

    private static synchronized Path uniqueTarget(String baseName, String ext) throws IOException {
        if (tempDir == null || !Files.isDirectory(tempDir)) {
            tempDir = Files.createTempDirectory("gesturtle-incoming");
            tempDir.toFile().deleteOnExit();
        }
        String base = baseName == null || baseName.isBlank() ? "image" : baseName;
        Path target = tempDir.resolve(base + "." + ext);
        for (int n = 2; Files.exists(target); n++) target = tempDir.resolve(base + "-" + n + "." + ext);
        target.toFile().deleteOnExit();
        return target;
    }

    private static String pathOf(URI uri) {
        return uri.getPath() == null ? "" : uri.getPath();
    }

    private static String supportedExtension(String path) {
        int dot = path.lastIndexOf('.');
        if (dot < 0 || path.lastIndexOf('/') > dot) return null;
        String ext = path.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (ext.equals("jpeg")) return "jpg";
        return EXTENSIONS.containsValue(ext) ? ext : null;
    }

    /** The link's last path segment without its extension, made safe as a filename. */
    private static String baseName(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        name = URLDecoder.decode(name, StandardCharsets.UTF_8).replaceAll("[^\\p{L}\\p{N}._ -]", "_").strip();
        if (name.length() > 80) name = name.substring(0, 80);
        return name.isEmpty() ? "web-image" : name;
    }
}
