package com.wig3003.photoapp.dip.geometric;

import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;

/**
 * Pure-function geometric transformation utilities.
 *
 * Every method:
 *   • accepts a source Mat and returns a NEW Mat
 *   • never mutates the source
 *   • never holds state
 *
 * Callers are responsible for releasing the returned Mat when done.
 *
 * Key fixes vs original:
 *   – rotate() now expands the output canvas so the full rotated
 *     image is always visible (no clipping at corners)
 *   – translate matrix rows written individually (safe for any
 *     OpenCV JNI version)
 *   – interpolation choices match the spec exactly
 */
public final class GeometricProcessor {

    // =========================================================
    // PRIVATE CONSTRUCTOR – utility class, not instantiable
    // =========================================================

    private GeometricProcessor() {}

    // =========================================================
    // SCALE – percentage relative to source dimensions
    // =========================================================

    /**
     * Resize {@code source} by {@code scalePercent} (e.g. 50.0 = half size).
     * Uses INTER_AREA for shrink, INTER_LANCZOS4 for enlarge.
     *
     * @param source       source Mat (not null, not empty)
     * @param scalePercent target scale, must be > 0
     * @return new resized Mat
     */
    public static Mat resize(Mat source, double scalePercent) {
        validate(source, "resize");
        if (scalePercent <= 0)
            throw new IllegalArgumentException("scalePercent must be > 0, got " + scalePercent);

        double scale = scalePercent / 100.0;
        int newW = Math.max(1, (int) Math.round(source.cols() * scale));
        int newH = Math.max(1, (int) Math.round(source.rows() * scale));

        int interp = (scale < 1.0) ? Imgproc.INTER_AREA : Imgproc.INTER_LANCZOS4;

        Mat dst = new Mat();
        Imgproc.resize(source, dst, new Size(newW, newH), 0, 0, interp);
        return dst;
    }

    /**
     * Resize {@code source} to exact pixel dimensions.
     * Interpolation is chosen automatically based on whether the
     * operation shrinks or enlarges in each axis.
     *
     * @param source source Mat
     * @param width  target width  (> 0)
     * @param height target height (> 0)
     * @return new resized Mat
     */
    public static Mat resizeToPixels(Mat source, int width, int height) {
        validate(source, "resizeToPixels");
        if (width <= 0 || height <= 0)
            throw new IllegalArgumentException(
                    "width and height must be > 0, got " + width + "x" + height);

        boolean shrink = (width <= source.cols() && height <= source.rows());
        int interp = shrink ? Imgproc.INTER_AREA : Imgproc.INTER_LANCZOS4;

        Mat dst = new Mat();
        Imgproc.resize(source, dst, new Size(width, height), 0, 0, interp);
        return dst;
    }

    /**
     * Resize {@code source} to exact pixel dimensions with an explicit
     * OpenCV interpolation flag (e.g. {@code Imgproc.INTER_LINEAR} for
     * fast interactive preview).
     */
    public static Mat resizeToPixels(Mat source, int width, int height, int interpFlag) {
        validate(source, "resizeToPixels");
        if (width <= 0 || height <= 0)
            throw new IllegalArgumentException(
                    "width and height must be > 0, got " + width + "x" + height);

        Mat dst = new Mat();
        Imgproc.resize(source, dst, new Size(width, height), 0, 0, interpFlag);
        return dst;
    }

    // =========================================================
    // ROTATE – expands canvas to keep full image visible
    // =========================================================

    /**
     * Rotate {@code source} by {@code angleDeg} degrees (positive = clockwise).
     *
     * The output canvas is enlarged so that no part of the rotated image
     * is clipped.  Empty regions are filled with white.
     *
     * @param source   source Mat
     * @param angleDeg rotation angle in degrees; any value is accepted
     * @return new rotated Mat with expanded canvas
     */
    public static Mat rotate(Mat source, double angleDeg) {
        validate(source, "rotate");

        // Normalise to (-180, 180] – cosmetic only, warpAffine handles any angle
        angleDeg = normaliseAngle(angleDeg);

        // ── Compute expanded canvas dimensions ────────────────
        double rad = Math.toRadians(angleDeg);
        double sinA = Math.abs(Math.sin(rad));
        double cosA = Math.abs(Math.cos(rad));

        int srcW = source.cols();
        int srcH = source.rows();

        int newW = (int) Math.round(srcW * cosA + srcH * sinA);
        int newH = (int) Math.round(srcW * sinA + srcH * cosA);

        // ── Build rotation matrix centred on original image ───
        Point srcCenter = new Point(srcW / 2.0, srcH / 2.0);
        Mat R = Imgproc.getRotationMatrix2D(srcCenter, -angleDeg, 1.0);

        // Shift the rotation so the result is centred in the new canvas
        double[] tx = R.get(0, 2);
        double[] ty = R.get(1, 2);
        R.put(0, 2, tx[0] + (newW / 2.0 - srcCenter.x));
        R.put(1, 2, ty[0] + (newH / 2.0 - srcCenter.y));

        Mat dst = new Mat();
        Imgproc.warpAffine(
                source, dst, R,
                new Size(newW, newH),
                Imgproc.INTER_LINEAR,
                Core.BORDER_CONSTANT,
                new Scalar(255, 255, 255));   // white fill

        R.release();
        return dst;
    }

    /**
     * Rotate using the fast interactive interpolation (INTER_LINEAR).
     * Intended for slider drag previews where speed matters more than quality.
     */
    public static Mat rotateFast(Mat source, double angleDeg) {
        return rotateWithInterp(source, angleDeg, Imgproc.INTER_LINEAR);
    }

    /**
     * Rotate using the high-quality interpolation (INTER_CUBIC).
     * Intended for stable/final preview rendering.
     */
    public static Mat rotateQuality(Mat source, double angleDeg) {
        return rotateWithInterp(source, angleDeg, Imgproc.INTER_CUBIC);
    }

    // =========================================================
    // ROTATE – 90° / 180° / 270° lossless shortcuts
    // =========================================================

    /**
     * Fast lossless 90° clockwise rotation via {@code Core.rotate}.
     * No interpolation involved.
     */
    public static Mat rotate90CW(Mat source) {
        validate(source, "rotate90CW");
        Mat dst = new Mat();
        Core.rotate(source, dst, Core.ROTATE_90_CLOCKWISE);
        return dst;
    }

    /** Fast lossless 90° counter-clockwise rotation. */
    public static Mat rotate90CCW(Mat source) {
        validate(source, "rotate90CCW");
        Mat dst = new Mat();
        Core.rotate(source, dst, Core.ROTATE_90_COUNTERCLOCKWISE);
        return dst;
    }

    /** Fast lossless 180° rotation. */
    public static Mat rotate180(Mat source) {
        validate(source, "rotate180");
        Mat dst = new Mat();
        Core.rotate(source, dst, Core.ROTATE_180);
        return dst;
    }

    // =========================================================
    // TRANSLATE – affine shift in image space
    // =========================================================

    /**
     * Translate (reposition) {@code source} by ({@code dx}, {@code dy}) pixels
     * in image space.
     *
     * Direction convention (matches the spec):
     *   positive dx → image content moves RIGHT
     *   positive dy → image content moves DOWN
     *
     * The output size is identical to the source; regions that move
     * out of frame are cropped and incoming empty regions are filled white.
     *
     * @param source source Mat
     * @param dx     horizontal shift in pixels (may be negative)
     * @param dy     vertical shift in pixels (may be negative)
     * @return new translated Mat
     */
    public static Mat translate(Mat source, double dx, double dy) {
        validate(source, "translate");

        // Translation matrix:
        //  | 1  0  dx |
        //  | 0  1  dy |
        Mat M = Mat.eye(2, 3, CvType.CV_64F);
        M.put(0, 2, new double[]{dx});
        M.put(1, 2, new double[]{dy});

        Mat dst = new Mat();
        Imgproc.warpAffine(
                source, dst, M,
                source.size(),
                Imgproc.INTER_LINEAR,
                Core.BORDER_CONSTANT,
                new Scalar(255, 255, 255));   // white fill

        M.release();
        return dst;
    }

    // =========================================================
    // FLIP
    // =========================================================

    /** Mirror the image left ↔ right. */
    public static Mat flipHorizontal(Mat source) {
        validate(source, "flipHorizontal");
        Mat dst = new Mat();
        Core.flip(source, dst, 1);
        return dst;
    }

    /** Mirror the image top ↔ bottom. */
    public static Mat flipVertical(Mat source) {
        validate(source, "flipVertical");
        Mat dst = new Mat();
        Core.flip(source, dst, 0);
        return dst;
    }

    // =========================================================
    // FULL PIPELINE – non-destructive, from original
    // =========================================================

    /**
     * Apply all three transformations in order:
     *   1. Scale
     *   2. Rotate (with canvas expansion)
     *   3. Translate
     *
     * Always operates from {@code original}; never modifies it.
     * Intermediate Mats are released immediately after use.
     *
     * @param original   the pristine source image – NEVER modified
     * @param state      current transformation parameters
     * @param highQuality true = INTER_LANCZOS4/INTER_CUBIC (final render)
     *                    false = INTER_AREA/INTER_LINEAR (interactive drag)
     * @return new fully-transformed Mat; caller must release when done
     */
    public static Mat applyPipeline(
            Mat original,
            TransformState state,
            boolean highQuality) {

        validate(original, "applyPipeline");

        // ── 1. SCALE ──────────────────────────────────────────
        double scale = state.scalePercent / 100.0;
        int newW = Math.max(1, (int) Math.round(original.cols() * scale));
        int newH = Math.max(1, (int) Math.round(original.rows() * scale));

        int scaleInterp;
        if (highQuality) {
            scaleInterp = (scale < 1.0) ? Imgproc.INTER_AREA : Imgproc.INTER_LANCZOS4;
        } else {
            scaleInterp = (scale < 1.0) ? Imgproc.INTER_AREA : Imgproc.INTER_LINEAR;
        }

        Mat scaled = new Mat();
        Imgproc.resize(original, scaled, new Size(newW, newH), 0, 0, scaleInterp);

        // ── 2. ROTATE ─────────────────────────────────────────
        int rotInterp = highQuality ? Imgproc.INTER_CUBIC : Imgproc.INTER_LINEAR;
        Mat rotated = rotateWithInterp(scaled, state.rotationAngle, rotInterp);
        scaled.release();

        // ── 3. TRANSLATE ──────────────────────────────────────
        Mat translated = translate(rotated, state.translateX, state.translateY);
        rotated.release();

        return translated;
    }

    // =========================================================
    // PRIVATE HELPERS
    // =========================================================

    /**
     * Core rotate implementation parameterised by interpolation flag.
     * Canvas is always expanded to prevent clipping.
     */
    private static Mat rotateWithInterp(Mat source, double angleDeg, int interp) {
        validate(source, "rotate");

        double rad = Math.toRadians(angleDeg);
        double sinA = Math.abs(Math.sin(rad));
        double cosA = Math.abs(Math.cos(rad));

        int srcW = source.cols();
        int srcH = source.rows();

        int newW = (int) Math.round(srcW * cosA + srcH * sinA);
        int newH = (int) Math.round(srcW * sinA + srcH * cosA);

        // Guard against zero output (can happen at exactly 0° due to rounding)
        newW = Math.max(1, newW);
        newH = Math.max(1, newH);

        Point srcCenter = new Point(srcW / 2.0, srcH / 2.0);
        Mat R = Imgproc.getRotationMatrix2D(srcCenter, -angleDeg, 1.0);

        R.put(0, 2, R.get(0, 2)[0] + (newW / 2.0 - srcCenter.x));
        R.put(1, 2, R.get(1, 2)[0] + (newH / 2.0 - srcCenter.y));

        Mat dst = new Mat();
        Imgproc.warpAffine(
                source, dst, R,
                new Size(newW, newH),
                interp,
                Core.BORDER_CONSTANT,
                new Scalar(255, 255, 255));

        R.release();
        return dst;
    }

    /** Normalise any angle into (-180, 180]. */
    private static double normaliseAngle(double angle) {
        angle = angle % 360.0;
        if (angle > 180.0)  angle -= 360.0;
        if (angle <= -180.0) angle += 360.0;
        return angle;
    }

    /** Throw if {@code src} is null or empty. */
    private static void validate(Mat src, String method) {
        if (src == null || src.empty())
            throw new IllegalArgumentException(
                    "GeometricProcessor." + method + "(): source must not be null or empty");
    }
}