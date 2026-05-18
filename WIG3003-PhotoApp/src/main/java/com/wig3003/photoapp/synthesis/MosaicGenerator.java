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
 * Implementation of the Mosaic Generator as per Contract §6[cite: 3, 7].
 */
public class MosaicGenerator {

    /**
     * Generates a mosaic from a target image and a pool of tiles.
     *
     * @param tilePaths  List of file paths to tile images
     * @param targetPath File path to the target image
     * @param tileSize   Size (width and height) of each tile in pixels; must be >= 10
     * @return Output path of the saved mosaic image
     * @throws IOException              If target or tile images cannot be loaded
     * @throws IllegalArgumentException If inputs are invalid
     */
    public String generateMosaic(List<String> tilePaths, String targetPath, int tileSize) throws IOException {

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

        // 2. Load target image and validate it
        Mat target = ImageUtils.loadMatFromPath(targetPath);
        if (target == null || target.empty()) {
            throw new IOException("Failed to load target image: " + targetPath);
        }

        // 3. Normalise target to BGR 3-channel so downstream comparisons are consistent
        target = normaliseToBGR(target);

        // 4. Compute grid dimensions (floor division keeps ROIs within bounds)
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
            if (p == null || p.isBlank()) {
                continue; // skip invalid entries rather than crashing
            }
            Mat t = ImageUtils.loadMatFromPath(p);
            if (t == null || t.empty()) {
                System.err.println("Warning: could not load tile image, skipping: " + p);
                continue;
            }

            // Normalise channel count before resizing
            t = normaliseToBGR(t);

            Mat resized = new Mat();
            Imgproc.resize(t, resized, new Size(tileSize, tileSize));
            tiles.add(resized);
        }

        if (tiles.isEmpty()) {
            throw new IllegalArgumentException("No valid tile images could be loaded from tilePaths");
        }

        // 6. Build mosaic Mat (3-channel BGR, same type as normalised tiles)
        Mat mosaic = Mat.zeros(rows * tileSize, cols * tileSize, CvType.CV_8UC3);

        // 7. Fill the grid by finding the best-matching tile for each cell
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                Rect roi = new Rect(c * tileSize, r * tileSize, tileSize, tileSize);
                Mat region = target.submat(roi);

                Mat tile = bestMatchingTile(region, tiles);

                // Safety: ensure the tile type matches the mosaic before copying
                if (tile.type() != CvType.CV_8UC3) {
                    tile = normaliseToBGR(tile);
                }

                tile.copyTo(mosaic.submat(roi));
            }
        }

        // 8. Save mosaic to data/output/
        Files.createDirectories(Paths.get("data/output"));
        String filename = "mosaic_" + System.currentTimeMillis() + ".png";
        String outPath = "data/output/" + filename;

        boolean saved = Imgcodecs.imwrite(outPath, mosaic);
        if (!saved) {
            throw new IOException("Imgcodecs.imwrite failed to save mosaic to: " + outPath);
        }

        return outPath;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Converts a Mat to a standard BGR 3-channel (CV_8UC3) Mat.
     * Handles grayscale (1-channel) and BGRA (4-channel) inputs.
     * Returns the original Mat unchanged if it is already CV_8UC3.
     */
    private Mat normaliseToBGR(Mat src) {
        int channels = src.channels();

        if (channels == 3) {
            // Already BGR — return as-is
            return src;
        }

        Mat dst = new Mat();

        if (channels == 4) {
            // BGRA → BGR  (common with PNG tiles that have an alpha channel)
            Imgproc.cvtColor(src, dst, Imgproc.COLOR_BGRA2BGR);
        } else if (channels == 1) {
            // Grayscale → BGR
            Imgproc.cvtColor(src, dst, Imgproc.COLOR_GRAY2BGR);
        } else {
            // Unexpected channel count — attempt a best-effort conversion and warn
            System.err.println("Warning: unexpected channel count (" + channels + "), attempting BGRA2BGR conversion.");
            Imgproc.cvtColor(src, dst, Imgproc.COLOR_BGRA2BGR);
        }

        return dst;
    }

    /**
     * Finds the best matching tile for a target region using mean BGR Euclidean distance.
     *
     * @param region The sub-region of the target image to match
     * @param tiles  List of candidate tile Mats (must be non-empty)
     * @return The tile Mat whose mean colour is closest to the region's mean colour
     */
    private Mat bestMatchingTile(Mat region, List<Mat> tiles) {
        // Compute mean BGR of the target region
        Scalar regionMean = Core.mean(region);

        Mat bestTile = tiles.get(0);
        double bestDist = Double.MAX_VALUE;

        for (Mat tile : tiles) {
            Scalar tileMean = Core.mean(tile);

            // Euclidean distance in BGR colour space
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
}
