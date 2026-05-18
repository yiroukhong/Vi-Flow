package com.wig3003.photoapp.dip.aesthetic;

import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.Scalar;
 
/**
 * AestheticProcessor — handles image border application.
 *
 * CONTRACT (Interface Contracts §4):
 *  - Input Mat must always be BGR (3-channel), never null.
 *  - Returned Mat is always BGR, larger than source by borderWidth on each side.
 *  - Source Mat is never modified.
 *  - borderWidth must be > 0.
 *  - borderType must be "CONSTANT", "REFLECT", or "REPLICATE" (case-insensitive).
 *  - color hex string (e.g. "#FF0000") is only used when borderType is CONSTANT.
 */
public class AestheticProcessor {
 
    /**
     * Applies a border around a BGR image.
     *
     * @param source      BGR Mat — must not be null or empty
     * @param borderWidth pixels to add on each side — must be > 0
     * @param borderType  "CONSTANT", "REFLECT", or "REPLICATE" (case-insensitive)
     * @param color       hex color string e.g. "#FF0000" — only used for CONSTANT
     * @return new BGR Mat, larger than source on all four sides
     */
    public Mat applyBorder(Mat source, int borderWidth, String borderType, String color) {
        // Validate source
        if (source == null || source.empty()) {
            throw new IllegalArgumentException(
                "source Mat must not be null or empty (AestheticProcessor.applyBorder)"
            );
        }
 
        // Validate borderWidth
        if (borderWidth <= 0) {
            throw new IllegalArgumentException(
                "borderWidth must be > 0, got: " + borderWidth
            );
        }
 
        // Parse border type → OpenCV constant
        int type = parseBorderType(borderType);
 
        // Parse hex color → BGR Scalar (only meaningful for CONSTANT)
        // For REFLECT / REPLICATE, OpenCV ignores the scalar — pass Scalar(0) as default
        Scalar scalarColor;
        if ("CONSTANT".equalsIgnoreCase(borderType)) {
            scalarColor = parseHexColor(color);
        } else {
            scalarColor = new Scalar(0); // ignored by OpenCV for non-CONSTANT types
        }
 
        // Write border result to a new Mat — never modify source
        Mat dst = new Mat();
        Core.copyMakeBorder(
            source, dst,
            borderWidth, borderWidth,   // top, bottom
            borderWidth, borderWidth,   // left, right
            type, scalarColor
        );
 
        // dst is BGR, larger than source
        return dst;
    }
 
    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------
 
    /**
     * Converts a border type string to the corresponding OpenCV constant.
     *
     * @param borderType "CONSTANT", "REFLECT", or "REPLICATE" (case-insensitive)
     * @return OpenCV border type integer
     * @throws IllegalArgumentException for unrecognised borderType
     */
    int parseBorderType(String borderType) {
        switch (borderType.toUpperCase()) {
            case "CONSTANT":  return Core.BORDER_CONSTANT;
            case "REFLECT":   return Core.BORDER_REFLECT;
            case "REPLICATE": return Core.BORDER_REPLICATE;
            default:
                throw new IllegalArgumentException(
                    "Unknown borderType: '" + borderType +
                    "'. Must be CONSTANT, REFLECT, or REPLICATE."
                );
        }
    }
 
    /**
     * Parses a hex color string (e.g. "#FF0000") into an OpenCV BGR Scalar.
     *
     * IMPORTANT: OpenCV Scalar channel order is BGR, not RGB.
     * So red "#FF0000" → R=255, G=0, B=0 → Scalar(0, 0, 255).
     *
     * @param hex color string in format "#RRGGBB"
     * @return Scalar in BGR order
     */
    Scalar parseHexColor(String hex) {
        if (hex == null || hex.length() != 7 || hex.charAt(0) != '#') {
            throw new IllegalArgumentException(
                "color must be in '#RRGGBB' format, got: " + hex
            );
        }
 
        int r = Integer.parseInt(hex.substring(1, 3), 16); // characters 1-2
        int g = Integer.parseInt(hex.substring(3, 5), 16); // characters 3-4
        int b = Integer.parseInt(hex.substring(5, 7), 16); // characters 5-6
 
        // OpenCV expects BGR order — swap R and B
        return new Scalar(b, g, r);
    }
}
 