import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/** Prints the text inside a QR code image:  java -cp "$(cat kaarigarsetu-java/target/demo.classpath)" scripts/QrDecode.java code.png   (exit 1 if unreadable). */
public class QrDecode {
    public static void main(String[] args) throws Exception {
        BufferedImage image = ImageIO.read(new File(args[0]));
        int w = image.getWidth(), h = image.getHeight();
        int[] pixels = image.getRGB(0, 0, w, h, null, 0, w);
        try {
            System.out.println(new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(w, h, pixels)))).getText());
        } catch (Exception unreadable) {
            System.err.println("not decodable: " + unreadable);
            System.exit(1);
        }
    }
}
