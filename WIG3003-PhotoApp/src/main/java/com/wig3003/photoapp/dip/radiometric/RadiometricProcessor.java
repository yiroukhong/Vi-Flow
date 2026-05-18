package com.wig3003.photoapp.dip.radiometric;

import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;
 
/**
 * RadiometricProcessor — handles brightness/contrast and grayscale conversion.
 *
 * CONTRACT (Interface Contracts §4):
 *  - Input Mat must always be BGR (3-channel), never null.
 *  - Returned Mat is always BGR, same size as input.
 *  - Source Mat is never modified.
 *  - Caller (Yirou) guarantees source is BGR via ImageUtils.writableImageToMat().
 */
public class RadiometricProcessor {
 
    /**
     * Adjusts brightness and contrast of a BGR image.
     *
     * Formula applied per pixel: dst = alpha * src + beta
     *
     * @param source BGR Mat — must not be null or empty
     * @param alpha  contrast multiplier, clamped to [0.0, 3.0]
     * @param beta   brightness offset,   clamped to [-100.0, 100.0]
     * @return new BGR Mat, same size as source
     */
    public Mat adjustBrightnessContrast(Mat source, double alpha, double beta) {
        // Validate input
        if (source == null || source.empty()) {
            throw new IllegalArgumentException(
                "source Mat must not be null or empty (RadiometricProcessor.adjustBrightnessContrast)"
            );
        }
 
        // Clamp alpha and beta to valid range — do NOT throw, just clamp
        alpha = Math.max(0.0, Math.min(3.0, alpha));
        beta  = Math.max(-100.0, Math.min(100.0, beta));
 
        // Write result to a new Mat — never modify source
        Mat dst = new Mat();
        Core.convertScaleAbs(source, dst, alpha, beta);
 
        // dst is BGR, same size as source
        return dst;
    }
 
    /**
     * Converts a BGR image to grayscale, then converts back to 3-channel BGR.
     *
     * Why the back-conversion?
     *   ImageUtils.matToWritableImage() (owned by Yirou) expects 3-channel BGR
     *   uniformly. A single-channel grayscale Mat would break the display bridge.
     *
     * @param source BGR Mat — must not be null or empty
     * @return new BGR Mat (visually grayscale but 3-channel), same size as source
     */
    public Mat toGrayscale(Mat source) {
        // Validate input
        if (source == null || source.empty()) {
            throw new IllegalArgumentException(
                "source Mat must not be null or empty (RadiometricProcessor.toGrayscale)"
            );
        }
 
        // Step 1: BGR → single-channel GRAY (CV_8UC1)
        Mat gray = new Mat();
        Imgproc.cvtColor(source, gray, Imgproc.COLOR_BGR2GRAY);
 
        // Step 2: single-channel GRAY → 3-channel BGR
        // Required so ImageUtils bridge works uniformly
        Mat bgr = new Mat();
        Imgproc.cvtColor(gray, bgr, Imgproc.COLOR_GRAY2BGR);
 
        // bgr is now 3-channel BGR, same size as source
        return bgr;
    }
}