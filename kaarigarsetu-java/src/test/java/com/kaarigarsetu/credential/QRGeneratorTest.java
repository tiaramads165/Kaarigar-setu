package com.kaarigarsetu.credential;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** The QR is decoded the way a phone camera would, so "it renders" can never again be mistaken for "it scans". */
class QRGeneratorTest {
    private static final String URL = "https://192.168.1.20:8443/verify/" + UUID.randomUUID();
    private final QRGenerator generator = new QRGenerator();

    private static BufferedImage image(byte[] png) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(png));
    }

    private static String decode(BufferedImage img) throws NotFoundException {
        int w = img.getWidth(), h = img.getHeight();
        int[] pixels = img.getRGB(0, 0, w, h, null, 0, w);
        return new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(w, h, pixels))),
                Map.of(DecodeHintType.TRY_HARDER, Boolean.TRUE)).getText();
    }

    @Test
    void theImageDecodesToExactlyTheVerificationAddress() throws Exception {
        assertEquals(URL, decode(image(generator.png(URL, 480))));
    }

    @Test
    void itStillDecodesAfterThePageScalesItDownAsTheDashboardDoes() throws Exception {
        BufferedImage original = image(generator.png(URL, 480));
        for (int target : new int[]{300, 240, 200}) {
            BufferedImage scaled = new BufferedImage(target, target, BufferedImage.TYPE_INT_RGB);
            var g = scaled.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(original, 0, 0, target, target, null);
            g.dispose();
            assertEquals(URL, decode(scaled), "scaled to " + target);
        }
    }

    @Test
    void modulesAreWholePixelsAndTheQuietZoneIsTheSpecifiedFourModules() throws Exception {
        BufferedImage img = image(generator.png(URL, 480));
        int edge = img.getWidth();
        assertEquals(edge, img.getHeight());
        // the first dark pixel on the diagonal is the corner of the top-left finder pattern: the quiet zone is everything before it
        int firstDark = 0;
        while (firstDark < edge && (img.getRGB(firstDark, firstDark) & 0xFFFFFF) != 0) firstDark++;
        // the finder pattern's outer ring is 7 modules wide; measure a module as the run of dark pixels along the top row of it
        int run = 0;
        while ((img.getRGB(firstDark + run, firstDark) & 0xFFFFFF) == 0) run++;
        int module = run / 7;
        assertTrue(module >= 4, "modules must be big enough to scan, was " + module + " px");
        assertEquals(0, run % 7, "the finder pattern must be exactly 7 whole modules wide");
        assertEquals(4 * module, firstDark, "quiet zone must be 4 modules");
        assertEquals(0, edge % module, "the image must be a whole number of modules");
        for (int i = 0; i < edge; i++) {            // the whole border band is white
            for (int d = 0; d < 4 * module; d++) {
                assertEquals(0xFFFFFF, img.getRGB(i, d) & 0xFFFFFF);
                assertEquals(0xFFFFFF, img.getRGB(d, i) & 0xFFFFFF);
                assertEquals(0xFFFFFF, img.getRGB(i, edge - 1 - d) & 0xFFFFFF);
                assertEquals(0xFFFFFF, img.getRGB(edge - 1 - d, i) & 0xFFFFFF);
            }
        }
    }

    @Test
    void errorCorrectionSurvivesGlareOrAScuffedPatchOverTheData() throws Exception {
        BufferedImage img = image(generator.png(URL, 480));
        int edge = img.getWidth();
        var g = img.createGraphics();
        g.setColor(java.awt.Color.BLACK);
        g.fillRect(edge / 2, edge / 2 + 10, edge / 6, edge / 8);   // a blot over the data area, away from the corner finder patterns
        g.dispose();
        assertEquals(URL, decode(img));
    }

    @Test
    void aLocalhostAddressStillEncodesButAnOverlongOneIsRefusedRatherThanProducingAnUnscannableCode() throws Exception {
        assertEquals("http://localhost:8090/verify/x", decode(image(generator.png("http://localhost:8090/verify/x", 480))));
        assertThrows(IllegalArgumentException.class, () -> generator.png("https://example.org/" + "a".repeat(900), 300));
        assertThrows(IllegalArgumentException.class, () -> generator.png(" ", 480));
        assertThrows(IllegalArgumentException.class, () -> generator.png(URL, 64));
    }
}
