import javax.imageio.ImageIO;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Regenerates every logo asset from logo-files/logo.png. Run from the repo
 * root after changing the logo: {@code java packaging/MakeIcons.java}.
 *
 * The logo is pixel art drawn on a 32x32 grid and exported at 10x, so each
 * output is first reduced to that 1x grid and then scaled back up by whole
 * multiples with nearest-neighbour sampling: any other scale smears the hard
 * pixel edges. Only 16 and 24 (below 1x) fall back to smooth downscaling,
 * since Windows asks for them and there's no crisp way to halve pixel art.
 *
 * Outputs: the in-app mark (trimmed to the art so it lines up with text) and
 * window icons under src/main/resources/logo/, and
 * the jpackage icons (.ico for Windows, .png for Linux, .icns for macOS)
 * under packaging/. The .ico and .icns hold PNG entries, which both formats
 * have accepted since Vista and OS X 10.7, so no image library is needed.
 */
public class MakeIcons {
    private static final int GRID = 32;

    public static void main(String[] args) throws IOException {
        BufferedImage icon = toGrid(ImageIO.read(new File("logo-files/logo.png")));
        BufferedImage mark = trim(icon);

        Path res = Path.of("src/main/resources/logo");
        Files.createDirectories(res);
        ImageIO.write(mark, "png", res.resolve("mark.png").toFile());
        for (int size : new int[]{32, 64, 128, 256}) {
            ImageIO.write(scale(icon, size), "png", res.resolve("icon-" + size + ".png").toFile());
        }

        Path pkg = Path.of("packaging");
        ImageIO.write(scale(icon, 512), "png", pkg.resolve("gesturtle.png").toFile());
        writeIco(icon, pkg.resolve("gesturtle.ico"), 16, 24, 32, 48, 64, 128, 256);
        writeIcns(icon, pkg.resolve("gesturtle.icns"));
        System.out.println("Wrote " + res + " and " + pkg + " icons");
    }

    /** Samples the centre of each exported block, recovering the 1x art regardless of export scale. */
    private static BufferedImage toGrid(BufferedImage src) {
        int block = src.getWidth() / GRID;
        BufferedImage out = new BufferedImage(GRID, GRID, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < GRID; y++) {
            for (int x = 0; x < GRID; x++) {
                out.setRGB(x, y, src.getRGB(x * block + block / 2, y * block + block / 2));
            }
        }
        return out;
    }

    private static BufferedImage trim(BufferedImage src) {
        int minX = src.getWidth(), minY = src.getHeight(), maxX = -1, maxY = -1;
        for (int y = 0; y < src.getHeight(); y++) {
            for (int x = 0; x < src.getWidth(); x++) {
                if ((src.getRGB(x, y) >>> 24) != 0) {
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                    minY = Math.min(minY, y);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        return src.getSubimage(minX, minY, maxX - minX + 1, maxY - minY + 1);
    }

    private static BufferedImage scale(BufferedImage src, int size) {
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        if (size % src.getWidth() == 0) {
            int k = size / src.getWidth();
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) out.setRGB(x, y, src.getRGB(x / k, y / k));
            }
            return out;
        }
        if (size > src.getWidth()) {
            // e.g. 48: nearest-neighbour from the next whole multiple down keeps edges hard.
            return nearest(scale(src, src.getWidth() * (size / src.getWidth())), size);
        }
        var g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, size, size, null);
        g.dispose();
        return out;
    }

    private static BufferedImage nearest(BufferedImage src, int size) {
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                out.setRGB(x, y, src.getRGB(x * src.getWidth() / size, y * src.getHeight() / size));
            }
        }
        return out;
    }

    private static byte[] png(BufferedImage image) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        return bytes.toByteArray();
    }

    /** ICONDIR + one ICONDIRENTRY per size, all little-endian, then the PNG payloads. */
    private static void writeIco(BufferedImage src, Path out, int... sizes) throws IOException {
        List<byte[]> images = new ArrayList<>();
        for (int size : sizes) images.add(png(scale(src, size)));
        int headerSize = 6 + 16 * sizes.length;
        int total = headerSize + images.stream().mapToInt(b -> b.length).sum();
        ByteBuffer buf = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        buf.putShort((short) 0).putShort((short) 1).putShort((short) sizes.length);
        int offset = headerSize;
        for (int i = 0; i < sizes.length; i++) {
            int dim = sizes[i] >= 256 ? 0 : sizes[i]; // 0 means 256
            buf.put((byte) dim).put((byte) dim).put((byte) 0).put((byte) 0);
            buf.putShort((short) 1).putShort((short) 32);
            buf.putInt(images.get(i).length).putInt(offset);
            offset += images.get(i).length;
        }
        for (byte[] image : images) buf.put(image);
        Files.write(out, buf.array());
    }

    /** "icns" + total length, then (type, length, PNG) chunks, all big-endian. */
    private static void writeIcns(BufferedImage src, Path out) throws IOException {
        String[] types = {"icp4", "icp5", "icp6", "ic07", "ic08", "ic09", "ic10"};
        int[] sizes = {16, 32, 64, 128, 256, 512, 1024};
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(body);
        for (int i = 0; i < types.length; i++) {
            byte[] image = png(scale(src, sizes[i]));
            data.writeBytes(types[i]);
            data.writeInt(8 + image.length);
            data.write(image);
        }
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        DataOutputStream header = new DataOutputStream(file);
        header.writeBytes("icns");
        header.writeInt(8 + body.size());
        body.writeTo(file);
        Files.write(out, file.toByteArray());
    }
}
