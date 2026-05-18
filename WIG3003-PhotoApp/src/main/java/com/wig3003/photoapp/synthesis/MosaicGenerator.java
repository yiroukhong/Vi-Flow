package com.wig3003.photoapp.synthesis;

import com.wig3003.photoapp.util.ImageUtils;
import org.opencv.core.*;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Winnie — Multimedia Synthesis
 * Mosaic Generator — Contract §6
 */
public class MosaicGenerator {

    /**
     * Generates a mosaic from a target image and a pool of tile images.
     *
     * @param tilePaths  List of file paths to tile images
     * @param targetPath File path to the target image
     * @param tileSize   Size (width and height) of each tile in pixels; must be >= 10
     * @return Output path of the saved mosaic image
     * @throws IOException              If target or tile images cannot be loaded
     * @throws IllegalArgumentException If inputs are invalid
     */
    public String generateMosaic(List<String> tilePaths, String targetPath, int tileSize)
            throws IOException {

        // 1. Validate inputs
        if (tilePaths == null || tilePaths.isEmpty()) {
            throw new IllegalArgumentException("tilePaths must not be empty");
        }
        if (targetPath == null || targetPath.isBlank()) {
            throw new IllegalArgumentException("targetPath must not be null or blank");
        }
        if (tileSize < 10) {
            throw new IllegalArgumentException("tileSize must be >= 10");
        }

        // 2. Load target image — normalise path for OpenCV on Windows
        String normTarget = normalisePath(targetPath);
        Mat target = ImageUtils.loadMatFromPath(normTarget);
        if (target == null || target.empty()) {
            // Fallback: try Imgcodecs directly with forward-slash path
            target = Imgcodecs.imread(normTarget);
        }
        if (target == null || target.empty()) {
            throw new IOException("Failed to load target image: " + targetPath);
        }

        // 3. Normalise target to BGR 3-channel
        target = normaliseToBGR(target);

        // 4. Compute grid dimensions
        int cols = target.cols() / tileSize;
        int rows = target.rows() / tileSize;

        if (cols == 0 || rows == 0) {
            throw new IllegalArgumentException(
                "tileSize (" + tileSize + ") is larger than the target image dimensions "
                + "(" + target.cols() + "x" + target.rows() + ")");
        }

        // 5. Load, normalise, and resize all tile images to tileSize x tileSize
        List<Mat> tiles = new ArrayList<>();
        for (String p : tilePaths) {
            if (p == null || p.isBlank()) continue;

            String normP = normalisePath(p);
            Mat t = ImageUtils.loadMatFromPath(normP);
            if (t == null || t.empty()) {
                // Fallback: try Imgcodecs directly
                t = Imgcodecs.imread(normP);
            }
            if (t == null || t.empty()) {
                System.err.println("Warning: could not load tile image, skipping: " + p);
                continue;
            }
            t = normaliseToBGR(t);
            Mat resized = new Mat();
            Imgproc.resize(t, resized, new Size(tileSize, tileSize));
            tiles.add(resized);
        }

        if (tiles.isEmpty()) {
            throw new IllegalArgumentException("No valid tile images could be loaded from tilePaths");
        }

        // 6. Build mosaic Mat
        Mat mosaic = Mat.zeros(rows * tileSize, cols * tileSize, CvType.CV_8UC3);

        // 7. For each grid cell, find best-matching tile by average BGR distance
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                Rect roi    = new Rect(c * tileSize, r * tileSize, tileSize, tileSize);
                Mat  region = target.submat(roi);
                Mat  tile   = bestMatchingTile(region, tiles);

                if (tile.type() != CvType.CV_8UC3) {
                    tile = normaliseToBGR(tile);
                }

                tile.copyTo(mosaic.submat(roi));
            }
        }

        // 8. Save mosaic to data/output/
        Files.createDirectories(Paths.get("data/output"));
        String filename = "mosaic_" + System.currentTimeMillis() + ".png";
        String outPath  = "data/output/" + filename;

        if (!Imgcodecs.imwrite(outPath, mosaic)) {
            throw new IOException("Imgcodecs.imwrite failed to save mosaic to: " + outPath);
        }

        return outPath;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Normalises a file path for OpenCV on Windows.
     * Replaces backslashes with forward slashes.
     */
    private String normalisePath(String path) {
        return path.replace("\\", "/");
    }

    /**
     * Finds the best matching tile using mean BGR Euclidean distance.
     */
    private Mat bestMatchingTile(Mat region, List<Mat> tiles) {
        Scalar regionMean = Core.mean(region);

        Mat    bestTile = tiles.get(0);
        double bestDist = Double.MAX_VALUE;

        for (Mat tile : tiles) {
            Scalar tileMean = Core.mean(tile);

            double dist = Math.sqrt(
                Math.pow(regionMean.val[0] - tileMean.val[0], 2) +
                Math.pow(regionMean.val[1] - tileMean.val[1], 2) +
                Math.pow(regionMean.val[2] - tileMean.val[2], 2)
            );

            if (dist < bestDist) {
                bestDist = dist;
                bestTile = tile;
            }
        }

        return bestTile;
    }

    /**
     * Converts a Mat to BGR 3-channel (CV_8UC3).
     * Handles grayscale (1-ch) and BGRA (4-ch) inputs.
     */
    private Mat normaliseToBGR(Mat src) {
        if (src.channels() == 3) return src;

        Mat dst = new Mat();
        if (src.channels() == 4) {
            Imgproc.cvtColor(src, dst, Imgproc.COLOR_BGRA2BGR);
        } else if (src.channels() == 1) {
            Imgproc.cvtColor(src, dst, Imgproc.COLOR_GRAY2BGR);
        } else {
            System.err.println("Warning: unexpected channel count (" + src.channels() + ")");
            Imgproc.cvtColor(src, dst, Imgproc.COLOR_BGRA2BGR);
        }
        return dst;
    }
}