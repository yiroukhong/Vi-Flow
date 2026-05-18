package com.wig3003.photoapp.dip.geometric;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ObjectExtractor {

    private static final int SEED_RADIUS = 2;
    private static final int MIN_AREA = 100;
    private static final Size FEATHER_KERNEL = new Size(5, 5);

    private ObjectExtractor() {
    }

    public static Mat selectObject(Mat source, int clickX, int clickY, int tolerance) {
        validate(source);

        Mat bgr = ensureBgr(source);
        clickX = clamp(clickX, 0, bgr.cols() - 1);
        clickY = clamp(clickY, 0, bgr.rows() - 1);
        tolerance = clamp(tolerance, 0, 80);

        Mat hsv = new Mat();
        Mat lab = new Mat();
        Mat hsvMask = null;
        Mat labMask = null;
        Mat floodMask = null;

        try {
            Imgproc.cvtColor(bgr, hsv, Imgproc.COLOR_BGR2HSV);
            Imgproc.cvtColor(bgr, lab, Imgproc.COLOR_BGR2Lab);

            HsvStats stats = sampleNeighbourhood(hsv, clickX, clickY, SEED_RADIUS);
            hsvMask = buildHsvMask(hsv, stats, tolerance);
            labMask = buildLabMask(lab, clickX, clickY, tolerance);

            Core.bitwise_or(hsvMask, labMask, hsvMask);

            floodMask = buildFloodMask(bgr, clickX, clickY, tolerance);
            Core.bitwise_and(hsvMask, floodMask, hsvMask);

            morphCleanup(hsvMask);
            keepClickedComponent(hsvMask, clickX, clickY);
            removeSmallNoise(hsvMask);

            Mat feathered = new Mat();
            Imgproc.GaussianBlur(hsvMask, feathered, FEATHER_KERNEL, 0);
            Core.normalize(feathered, feathered, 0, 255, Core.NORM_MINMAX);
            return feathered;

        } finally {
            if (bgr != source) bgr.release();
            hsv.release();
            lab.release();
            release(hsvMask);
            release(labMask);
            release(floodMask);
        }
    }

    public static Mat buildTransparentResult(Mat source, Mat mask) {
        validate(source);
        if (mask == null || mask.empty()) {
            throw new IllegalArgumentException("Mask is null or empty.");
        }
        if (mask.cols() != source.cols() || mask.rows() != source.rows()) {
            throw new IllegalArgumentException("Mask size must match source size.");
        }

        Mat bgr = ensureBgr(source);
        Mat bgra = new Mat();
        Mat alpha = new Mat();
        List<Mat> channels = new ArrayList<>();

        try {
            Imgproc.cvtColor(bgr, bgra, Imgproc.COLOR_BGR2BGRA);
            Core.split(bgra, channels);

            if (mask.channels() == 1) {
                mask.copyTo(alpha);
            } else {
                Imgproc.cvtColor(mask, alpha, Imgproc.COLOR_BGR2GRAY);
            }

            Mat oldAlpha = channels.get(3);
            oldAlpha.release();
            channels.set(3, alpha);

            Core.merge(channels, bgra);
            return bgra.clone();

        } finally {
            if (bgr != source) bgr.release();
            bgra.release();

            for (Mat channel : channels) {
                release(channel);
            }
        }
    }

    public static Mat cropToContent(Mat image, Mat mask) {
        if (image == null || image.empty()) {
            throw new IllegalArgumentException("Image is null or empty.");
        }
        if (mask == null || mask.empty()) {
            return image.clone();
        }

        Mat binary = new Mat();
        Mat contourSource = new Mat();
        Mat hierarchy = new Mat();
        List<MatOfPoint> contours = new ArrayList<>();

        try {
            Imgproc.threshold(mask, binary, 10, 255, Imgproc.THRESH_BINARY);
            contourSource = binary.clone();

            Imgproc.findContours(
                    contourSource,
                    contours,
                    hierarchy,
                    Imgproc.RETR_EXTERNAL,
                    Imgproc.CHAIN_APPROX_SIMPLE);

            contours.removeIf(c -> Imgproc.contourArea(c) < MIN_AREA);

            if (contours.isEmpty()) {
                return image.clone();
            }

            Rect bounds = Imgproc.boundingRect(contours.get(0));
            for (int i = 1; i < contours.size(); i++) {
                bounds = union(bounds, Imgproc.boundingRect(contours.get(i)));
            }

            bounds = clampRect(bounds, image.cols(), image.rows());

            if (bounds.width <= 0 || bounds.height <= 0) {
                return image.clone();
            }

            return new Mat(image, bounds).clone();

        } finally {
            binary.release();
            contourSource.release();
            hierarchy.release();

            for (MatOfPoint contour : contours) {
                contour.release();
            }
        }
    }

    public static String savePng(Mat image, String outputDirectory) {
        if (image == null || image.empty()) {
            throw new IllegalArgumentException("Image is null or empty.");
        }

        File dir = new File(outputDirectory == null || outputDirectory.isBlank()
                ? "."
                : outputDirectory);

        if (!dir.exists() && !dir.mkdirs()) {
            throw new RuntimeException("Could not create output directory: " + dir.getAbsolutePath());
        }

        String path = new File(dir, "extracted_" + System.currentTimeMillis() + ".png")
                .getAbsolutePath();

        if (!Imgcodecs.imwrite(path, image)) {
            throw new RuntimeException("Failed to write image to: " + path);
        }

        return path;
    }

    private static HsvStats sampleNeighbourhood(Mat hsv, int cx, int cy, int radius) {
        double sumH = 0;
        double sumS = 0;
        double sumV = 0;
        int count = 0;

        double minH = 180;
        double maxH = 0;
        double minS = 255;
        double maxS = 0;
        double minV = 255;
        double maxV = 0;

        for (int dy = -radius; dy <= radius; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                int x = clamp(cx + dx, 0, hsv.cols() - 1);
                int y = clamp(cy + dy, 0, hsv.rows() - 1);

                double[] pixel = hsv.get(y, x);
                if (pixel == null || pixel.length < 3) continue;

                sumH += pixel[0];
                sumS += pixel[1];
                sumV += pixel[2];

                minH = Math.min(minH, pixel[0]);
                maxH = Math.max(maxH, pixel[0]);
                minS = Math.min(minS, pixel[1]);
                maxS = Math.max(maxS, pixel[1]);
                minV = Math.min(minV, pixel[2]);
                maxV = Math.max(maxV, pixel[2]);
                count++;
            }
        }

        if (count == 0) {
            double[] fallback = hsv.get(cy, cx);
            return new HsvStats(
                    fallback[0],
                    fallback[1],
                    fallback[2],
                    0,
                    0,
                    0);
        }

        return new HsvStats(
                sumH / count,
                sumS / count,
                sumV / count,
                (maxH - minH) / 2.0,
                (maxS - minS) / 2.0,
                (maxV - minV) / 2.0);
    }

    private static Mat buildHsvMask(Mat hsv, HsvStats stats, int tolerance) {
        double saturationFactor = 1.0 + (1.0 - stats.meanS / 255.0) * 0.9;
        double valueFactor = 1.0 + (1.0 - stats.meanV / 255.0) * 0.55;

        double hTol = Math.max(3.0, tolerance * 0.8 + stats.spreadH);
        double sTol = Math.min(100.0, Math.max(12.0, tolerance * saturationFactor + stats.spreadS));
        double vTol = Math.min(100.0, Math.max(12.0, tolerance * valueFactor + stats.spreadV));

        double loH = stats.meanH - hTol;
        double hiH = stats.meanH + hTol;

        Mat mask = new Mat();

        Core.inRange(
                hsv,
                new Scalar(Math.max(0, loH),
                        Math.max(0, stats.meanS - sTol),
                        Math.max(0, stats.meanV - vTol)),
                new Scalar(Math.min(179, hiH),
                        Math.min(255, stats.meanS + sTol),
                        Math.min(255, stats.meanV + vTol)),
                mask);

        if (loH < 0) {
            Mat wrap = new Mat();
            Core.inRange(
                    hsv,
                    new Scalar(180 + loH,
                            Math.max(0, stats.meanS - sTol),
                            Math.max(0, stats.meanV - vTol)),
                    new Scalar(179,
                            Math.min(255, stats.meanS + sTol),
                            Math.min(255, stats.meanV + vTol)),
                    wrap);
            Core.bitwise_or(mask, wrap, mask);
            wrap.release();
        }

        if (hiH > 179) {
            Mat wrap = new Mat();
            Core.inRange(
                    hsv,
                    new Scalar(0,
                            Math.max(0, stats.meanS - sTol),
                            Math.max(0, stats.meanV - vTol)),
                    new Scalar(hiH - 179,
                            Math.min(255, stats.meanS + sTol),
                            Math.min(255, stats.meanV + vTol)),
                    wrap);
            Core.bitwise_or(mask, wrap, mask);
            wrap.release();
        }

        return mask;
    }

    private static Mat buildLabMask(Mat lab, int cx, int cy, int tolerance) {
        double[] seed = lab.get(cy, cx);
        if (seed == null || seed.length < 3) {
            return Mat.zeros(lab.size(), CvType.CV_8UC1);
        }

        double lTol = Math.max(8.0, tolerance * 1.0);
        double aTol = Math.max(5.0, tolerance * 0.55);
        double bTol = Math.max(5.0, tolerance * 0.55);

        Mat mask = new Mat();
        Core.inRange(
                lab,
                new Scalar(
                        Math.max(0, seed[0] - lTol),
                        Math.max(0, seed[1] - aTol),
                        Math.max(0, seed[2] - bTol)),
                new Scalar(
                        Math.min(255, seed[0] + lTol),
                        Math.min(255, seed[1] + aTol),
                        Math.min(255, seed[2] + bTol)),
                mask);

        return mask;
    }

    private static Mat buildFloodMask(Mat source, int cx, int cy, int tolerance) {
        Mat copy = source.clone();
        Mat flood = Mat.zeros(source.rows() + 2, source.cols() + 2, CvType.CV_8UC1);

        int floodTolerance = clamp((int) Math.round(12 + tolerance * 0.8), 12, 70);

        Imgproc.floodFill(
                copy,
                flood,
                new Point(cx, cy),
                new Scalar(255, 255, 255),
                null,
                new Scalar(floodTolerance, floodTolerance, floodTolerance),
                new Scalar(floodTolerance, floodTolerance, floodTolerance),
                Imgproc.FLOODFILL_MASK_ONLY | (255 << 8));

        copy.release();

        Mat cropped = flood.submat(new Rect(1, 1, source.cols(), source.rows())).clone();
        flood.release();

        return cropped;
    }

    private static void morphCleanup(Mat mask) {
        Mat closeKernel = Imgproc.getStructuringElement(
                Imgproc.MORPH_ELLIPSE,
                new Size(5, 5));

        Mat openKernel = Imgproc.getStructuringElement(
                Imgproc.MORPH_ELLIPSE,
                new Size(3, 3));

        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, closeKernel);
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_OPEN, openKernel);

        closeKernel.release();
        openKernel.release();
    }

    private static void keepClickedComponent(Mat mask, int x, int y) {
        Mat labels = new Mat();
        Mat stats = new Mat();
        Mat centroids = new Mat();
        Mat selected = Mat.zeros(mask.size(), CvType.CV_8UC1);

        try {
            Imgproc.threshold(mask, mask, 1, 255, Imgproc.THRESH_BINARY);

            int count = Imgproc.connectedComponentsWithStats(mask, labels, stats, centroids);
            double[] labelValue = labels.get(y, x);

            if (labelValue == null || labelValue.length == 0) {
                return;
            }

            int selectedLabel = (int) labelValue[0];

            if (selectedLabel <= 0 || selectedLabel >= count) {
                return;
            }

            Core.compare(labels, new Scalar(selectedLabel), selected, Core.CMP_EQ);
            selected.convertTo(mask, CvType.CV_8UC1, 255.0);

        } finally {
            labels.release();
            stats.release();
            centroids.release();
            selected.release();
        }
    }

    private static void removeSmallNoise(Mat mask) {
        Mat contourSource = mask.clone();
        Mat hierarchy = new Mat();
        Mat clean = Mat.zeros(mask.size(), CvType.CV_8UC1);
        List<MatOfPoint> contours = new ArrayList<>();

        try {
            Imgproc.findContours(
                    contourSource,
                    contours,
                    hierarchy,
                    Imgproc.RETR_EXTERNAL,
                    Imgproc.CHAIN_APPROX_SIMPLE);

            for (MatOfPoint contour : contours) {
                if (Imgproc.contourArea(contour) >= MIN_AREA) {
                    Imgproc.drawContours(
                            clean,
                            Collections.singletonList(contour),
                            -1,
                            new Scalar(255),
                            Imgproc.FILLED);
                }
            }

            clean.copyTo(mask);

        } finally {
            contourSource.release();
            hierarchy.release();
            clean.release();

            for (MatOfPoint contour : contours) {
                contour.release();
            }
        }
    }

    private static Mat ensureBgr(Mat source) {
        if (source.channels() == 3) {
            return source;
        }

        Mat bgr = new Mat();

        if (source.channels() == 4) {
            Imgproc.cvtColor(source, bgr, Imgproc.COLOR_BGRA2BGR);
        } else if (source.channels() == 1) {
            Imgproc.cvtColor(source, bgr, Imgproc.COLOR_GRAY2BGR);
        } else {
            throw new IllegalArgumentException("Unsupported image channel count: " + source.channels());
        }

        return bgr;
    }

    private static Rect union(Rect a, Rect b) {
        int x1 = Math.min(a.x, b.x);
        int y1 = Math.min(a.y, b.y);
        int x2 = Math.max(a.x + a.width, b.x + b.width);
        int y2 = Math.max(a.y + a.height, b.y + b.height);

        return new Rect(x1, y1, x2 - x1, y2 - y1);
    }

    private static Rect clampRect(Rect rect, int width, int height) {
        int x1 = clamp(rect.x, 0, width);
        int y1 = clamp(rect.y, 0, height);
        int x2 = clamp(rect.x + rect.width, 0, width);
        int y2 = clamp(rect.y + rect.height, 0, height);

        return new Rect(x1, y1, Math.max(0, x2 - x1), Math.max(0, y2 - y1));
    }

    private static void validate(Mat source) {
        if (source == null || source.empty()) {
            throw new IllegalArgumentException("Source image is null or empty.");
        }
    }

    private static void release(Mat mat) {
        if (mat != null && !mat.empty()) {
            mat.release();
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class HsvStats {
        final double meanH;
        final double meanS;
        final double meanV;
        final double spreadH;
        final double spreadS;
        final double spreadV;

        HsvStats(double meanH,
                 double meanS,
                 double meanV,
                 double spreadH,
                 double spreadS,
                 double spreadV) {
            this.meanH = meanH;
            this.meanS = meanS;
            this.meanV = meanV;
            this.spreadH = spreadH;
            this.spreadS = spreadS;
            this.spreadV = spreadV;
        }
    }
}
