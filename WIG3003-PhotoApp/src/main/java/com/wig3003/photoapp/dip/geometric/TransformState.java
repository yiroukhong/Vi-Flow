package com.wig3003.photoapp.dip.geometric;

/**
 * Immutable snapshot of all four transformation parameters.
 *
 * The undo stack holds these records instead of raster Mat copies,
 * keeping memory usage constant regardless of image size.
 *
 * Restoring a state re-runs the non-destructive pipeline from
 * originalMat, so no pixel data is stored here at all.
 */
public final class TransformState {

    // =========================================================
    // FIELDS
    // =========================================================

    public final double scalePercent;   // 10 – 300
    public final double rotationAngle;  // -180 – +180
    public final double translateX;     // image-space pixels
    public final double translateY;     // image-space pixels

    // =========================================================
    // CONSTRUCTOR
    // =========================================================

    public TransformState(
            double scalePercent,
            double rotationAngle,
            double translateX,
            double translateY) {

        this.scalePercent  = scalePercent;
        this.rotationAngle = rotationAngle;
        this.translateX    = translateX;
        this.translateY    = translateY;
    }

    // =========================================================
    // FACTORY – DEFAULT (identity transform)
    // =========================================================

    public static TransformState identity() {
        return new TransformState(100.0, 0.0, 0.0, 0.0);
    }

    // =========================================================
    // COPY-WITH HELPERS
    // =========================================================

    public TransformState withScale(double scalePercent) {
        return new TransformState(scalePercent, rotationAngle, translateX, translateY);
    }

    public TransformState withRotation(double rotationAngle) {
        return new TransformState(scalePercent, rotationAngle, translateX, translateY);
    }

    public TransformState withTranslation(double tx, double ty) {
        return new TransformState(scalePercent, rotationAngle, tx, ty);
    }

    // =========================================================
    // DEBUG
    // =========================================================

    @Override
    public String toString() {
        return String.format(
                "TransformState{scale=%.1f%%, angle=%.1f°, tx=%.0f, ty=%.0f}",
                scalePercent, rotationAngle, translateX, translateY);
    }
}