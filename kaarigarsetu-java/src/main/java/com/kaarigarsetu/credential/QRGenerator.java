package com.kaarigarsetu.credential;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;

/**
 * Renders a QR code PNG with ZXing core (no AWT UI, only BufferedImage + ImageIO).
 *
 * <p>Built to scan reliably from a phone camera: every module is a whole number of pixels (no blurry or uneven edges when the page scales
 * the image), the quiet zone is the 4 modules the QR specification asks for (a smaller one is what makes some scanners fail), and error
 * correction is level Q (about 25 % of the code can be unreadable, which covers glare and a scuffed screen).
 */
public class QRGenerator {
    private static final int QUIET_ZONE_MODULES = 4;
    private static final int MIN_MODULE_PIXELS = 4;

    /** @param size the largest image edge wanted, in pixels; the image is the biggest whole-module size that fits */
    public byte[] png(String text, int size) {
        if (text == null || text.isBlank() || size < 128 || size > 1024) {
            throw new IllegalArgumentException("invalid QR request");
        }
        try {
            // width = height = 0 returns the bare module matrix, so the module size and the quiet zone are ours to choose.
            BitMatrix modules = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0,
                    Map.of(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.Q, EncodeHintType.MARGIN, 0, EncodeHintType.CHARACTER_SET, "UTF-8"));
            int count = modules.getWidth();
            int pixelsPerModule = size / (count + 2 * QUIET_ZONE_MODULES);
            if (pixelsPerModule < MIN_MODULE_PIXELS) throw new IllegalArgumentException("text is too long for a scannable QR at this size");
            int edge = (count + 2 * QUIET_ZONE_MODULES) * pixelsPerModule;
            BufferedImage image = new BufferedImage(edge, edge, BufferedImage.TYPE_INT_RGB);
            java.util.Arrays.fill(((java.awt.image.DataBufferInt) image.getRaster().getDataBuffer()).getData(), 0xFFFFFF);
            for (int x = 0; x < count; x++) {
                for (int y = 0; y < count; y++) {
                    if (!modules.get(x, y)) continue;
                    int left = (x + QUIET_ZONE_MODULES) * pixelsPerModule, top = (y + QUIET_ZONE_MODULES) * pixelsPerModule;
                    for (int dx = 0; dx < pixelsPerModule; dx++) for (int dy = 0; dy < pixelsPerModule; dy++) image.setRGB(left + dx, top + dy, 0x000000);
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            javax.imageio.ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (WriterException | IOException exception) {
            throw new IllegalStateException("Unable to render QR code", exception);
        }
    }
}
