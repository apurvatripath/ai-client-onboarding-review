package com.apurva.onboarding.extraction;

import java.awt.image.BufferedImage;
import java.io.IOException;

public interface OcrEngine {
    record Text(String text, double confidence) {}
    Text read(BufferedImage image) throws IOException, InterruptedException;
}
