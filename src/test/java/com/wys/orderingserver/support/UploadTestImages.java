package com.wys.orderingserver.support;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Random;

/** 在内存中生成真实图片，上传测试不依赖外部图片文件。 */
public final class UploadTestImages {
    private UploadTestImages() {}

    public static byte[] image(String format, int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(17);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) image.setRGB(x, y, random.nextInt(0x1000000));
        }
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, format, output)) throw new IOException("测试图片编码器不可用：" + format);
            return output.toByteArray();
        } finally { image.flush(); }
    }
}
