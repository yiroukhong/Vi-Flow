package com.wig3003.photoapp.dip.geometric;

import javafx.application.Platform;
import javafx.scene.control.ComboBox;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.scene.control.ScrollPane;

/**
 * SmartViewport
 * ═════════════════════════════════════════════════════════════════
 * Owns ALL viewer zoom and scroll-centering logic for the geometric
 * transformation module.
 *
 * Responsibilities:
 *   • Maintain auto-fit vs manual zoom mode
 *   • Calculate comfortable zoom levels after transformations
 *   • Update ImageView + StackPane display sizes
 *   • Re-center the ScrollPane after every render
 *   • Keep the zoom ComboBox label in sync with actual zoom
 *   • Never touch image pixels or TransformState
 *
 * ─────────────────────────────────────────────────────────────────
 * ZOOM MODE RULES
 * ─────────────────────────────────────────────────────────────────
 *
 * AUTO-FIT MODE (default):
 *   • On image load          → fit entire image inside viewport, capped at 100%
 *   • After scale/rotation   → recalculate fit zoom from new result dimensions
 *   • After translation      → zoom unchanged (canvas size doesn't change)
 *   • Viewport resize        → recalculate fit zoom
 *   • Combo shows "Fit (XX%)" so user always knows actual magnification
 *
 * MANUAL MODE (user selected a preset or used Ctrl+scroll):
 *   • Zoom is locked at the selected level
 *   • No automatic adjustment on transformation
 *   • Scrollbars appear if image overflows viewport
 *   • Combo shows the selected preset label or a live "XX%" for scroll zoom
 *   • Auto-fit resumes only when user explicitly selects "Fit Screen"
 *
 * ─────────────────────────────────────────────────────────────────
 * CENTERING
 * ─────────────────────────────────────────────────────────────────
 *   After every display update, ScrollPane h/v values are reset to
 *   0.5 (centre).  This prevents stale scroll offsets from orphaning
 *   the image after a transformation changes its bounding box.
 *
 * ─────────────────────────────────────────────────────────────────
 * MINIMUM DISPLAY SIZE FLOOR
 * ─────────────────────────────────────────────────────────────────
 *   In auto-fit mode, if the calculated fit zoom would render the
 *   image smaller than MIN_DISPLAY_FRACTION of the viewport in
 *   either axis, a floor is applied so tiny scaled results remain
 *   inspectable.  This only triggers at aggressive downscales (≈10%).
 */
public class SmartViewport {

    // ─────────────────────────────────────────────────────────────
    // CONSTANTS
    // ─────────────────────────────────────────────────────────────

    /** Hard lower bound on viewer zoom (4% → won't go smaller). */
    public static final double ZOOM_MIN = 0.04;

    /** Hard upper bound on viewer zoom (800%). */
    public static final double ZOOM_MAX = 8.0;

    /**
     * In auto-fit mode, the displayed image will always occupy at
     * least this fraction of the smaller viewport dimension.
     * Prevents tiny scaled images becoming invisible dots.
     */
    private static final double MIN_DISPLAY_FRACTION = 0.15;

    /**
     * Auto-fit cap: never zoom IN past 100% on initial fit.
     * Prevents small images from being blown up aggressively.
     */
    private static final double FIT_ZOOM_CAP = 1.0;

    /** Padding factor: leave a small margin around the fitted image. */
    private static final double FIT_PADDING = 0.96;

    // Zoom combo labels
    public static final String   LABEL_FIT       = "Fit Screen";
    public static final String[] PRESET_LABELS   = {
            LABEL_FIT, "25%", "50%", "75%", "100%", "150%", "200%", "400%"
    };
    public static final double[] PRESET_VALUES   = {
            -1,        0.25,  0.50,  0.75,  1.00,   1.50,   2.00,   4.00
    };  // -1 = special (fit mode)

    // ─────────────────────────────────────────────────────────────
    // WIRED JAVAFX NODES  (set once in constructor)
    // ─────────────────────────────────────────────────────────────

    private final ScrollPane        scrollPane;
    private final StackPane         checkerPane;
    private final ImageView         imageView;
    private final ComboBox<String>  zoomCombo;

    // ─────────────────────────────────────────────────────────────
    // STATE
    // ─────────────────────────────────────────────────────────────

    /** Current effective viewer zoom (1.0 = 100% = 1 px per screen px). */
    private double  currentZoom  = 1.0;

    /** True while in auto-fit mode; false once user picks a manual level. */
    private boolean autoFitMode  = true;

    /**
     * Dimensions of the most recently displayed result image.
     * Stored so re-centering and viewport-resize recalculations work
     * without needing a reference back to the Mat.
     */
    private double  lastImageW   = 0;
    private double  lastImageH   = 0;

    /**
     * Guard flag: set true while SmartViewport is writing to zoomCombo
     * to prevent the combo's action listener from firing re-entrantly.
     */
    private boolean updatingCombo = false;

    // ─────────────────────────────────────────────────────────────
    // CONSTRUCTOR
    // ─────────────────────────────────────────────────────────────

    public SmartViewport(
            ScrollPane       scrollPane,
            StackPane        checkerPane,
            ImageView        imageView,
            ComboBox<String> zoomCombo) {

        this.scrollPane = scrollPane;
        this.checkerPane = checkerPane;
        this.imageView   = imageView;
        this.zoomCombo   = zoomCombo;

        // Viewport resize → recalculate fit zoom if in auto mode
        scrollPane.viewportBoundsProperty().addListener((obs, ov, nv) -> {
            if (autoFitMode && lastImageW > 0) {
                double zoom = calcFitZoom(lastImageW, lastImageH);
                applyZoom(zoom);
                updateComboLabel(zoom);
            }
        });
    }

    // ─────────────────────────────────────────────────────────────
    // PUBLIC API – called by the controller
    // ─────────────────────────────────────────────────────────────

    /**
     * Call when a new image has been loaded.
     * Always enters auto-fit mode and fits the image to the viewport.
     *
     * @param imageW pixel width of the loaded image
     * @param imageH pixel height of the loaded image
     */
    public void onImageLoaded(double imageW, double imageH) {
        lastImageW  = imageW;
        lastImageH  = imageH;
        autoFitMode = true;

        double zoom = calcFitZoom(imageW, imageH);
        applyZoom(zoom);
        updateComboLabel(zoom);
        centerScrollPane();
    }

    /**
     * Call after every scale or rotation preview render completes.
     *
     * In auto-fit mode: recalculates fit zoom from new result dimensions.
     * In manual mode:   applies the locked zoom unchanged.
     *
     * @param resultW pixel width of the rendered result
     * @param resultH pixel height of the rendered result
     */
    public void onTransformResult(double resultW, double resultH) {
        lastImageW = resultW;
        lastImageH = resultH;

        if (autoFitMode) {
            double zoom = calcFitZoom(resultW, resultH);
            applyZoom(zoom);
            updateComboLabel(zoom);
        } else {
            // Manual mode: just resize the display pane, keep locked zoom
            applyZoom(currentZoom);
        }
        centerScrollPane();
    }

    /**
     * Call after translation preview renders.
     * Translation doesn't change the canvas bounding box, so zoom is
     * not recalculated — only centering is reapplied.
     *
     * @param resultW pixel width of the rendered result
     * @param resultH pixel height of the rendered result
     */
    public void onTranslationResult(double resultW, double resultH) {
        lastImageW = resultW;
        lastImageH = resultH;
        applyZoom(currentZoom);   // size pane correctly, no zoom change
        centerScrollPane();
    }

    /**
     * Called when the user selects a zoom preset from the ComboBox.
     * Switches to manual mode and locks the chosen zoom.
     *
     * @param label the selected combo label (e.g. "100%", "Fit Screen")
     */
    public void onUserSelectedZoomLabel(String label) {
        if (updatingCombo) return;
        if (label == null)  return;

        if (LABEL_FIT.equals(label)) {
            autoFitMode = true;
            if (lastImageW > 0) {
                double zoom = calcFitZoom(lastImageW, lastImageH);
                applyZoom(zoom);
                updateComboLabel(zoom);
                centerScrollPane();
            }
        } else {
            double zoom = parseLabelToZoom(label);
            if (zoom <= 0) return;
            autoFitMode = false;
            applyZoom(zoom);
            centerScrollPane();
            // combo already shows the selected label — no update needed
        }
    }

    /**
     * Called when the user zooms via Ctrl + scroll wheel.
     * Switches to manual mode. Combo label updates to show live percentage.
     *
     * @param delta multiplicative delta (e.g. 1.15 or 1/1.15)
     */
    public void onScrollWheelZoom(double delta) {
        autoFitMode = false;
        double newZoom = clamp(currentZoom * delta, ZOOM_MIN, ZOOM_MAX);
        applyZoom(newZoom);
        centerScrollPane();

        // Show live percentage in combo without triggering action handler
        updatingCombo = true;
        String liveLabel = String.format("%.0f%%", newZoom * 100);
        if (!zoomCombo.getItems().contains(liveLabel)) {
            // Replace any previous live label (non-preset item) or add temporarily
            // Simple approach: just update the editor text via selection index -1
            zoomCombo.getSelectionModel().select(-1);
            zoomCombo.setPromptText(liveLabel);
        } else {
            zoomCombo.setValue(liveLabel);
        }
        updatingCombo = false;
    }

    /**
     * Force auto-fit mode on (e.g. after reset, after undo).
     * Recalculates zoom from last known image dimensions.
     */
    public void resetToFitMode() {
        autoFitMode = true;
        if (lastImageW > 0) {
            double zoom = calcFitZoom(lastImageW, lastImageH);
            applyZoom(zoom);
            updateComboLabel(zoom);
            centerScrollPane();
        }
    }

    /** @return current effective viewer zoom (1.0 = 100%). */
    public double getCurrentZoom() { return currentZoom; }

    /** @return true if auto-fit mode is currently active. */
    public boolean isAutoFitMode() { return autoFitMode; }

    // ─────────────────────────────────────────────────────────────
    // PRIVATE – ZOOM CALCULATION
    // ─────────────────────────────────────────────────────────────

    /**
     * Calculate the zoom that makes (imageW × imageH) fit inside the
     * current viewport with a small padding margin and the FIT_ZOOM_CAP.
     *
     * A minimum display floor is applied so very small images remain
     * inspectable rather than shrinking to a dot.
     */
    private double calcFitZoom(double imageW, double imageH) {
        double vw = scrollPane.getViewportBounds().getWidth();
        double vh = scrollPane.getViewportBounds().getHeight();

        if (vw <= 0 || vh <= 0 || imageW <= 0 || imageH <= 0) return 1.0;

        // Basic fit with padding
        double zoom = Math.min(vw / imageW, vh / imageH) * FIT_PADDING;

        // Cap: never zoom in past 100% on auto-fit
        zoom = Math.min(zoom, FIT_ZOOM_CAP);

        // Floor: ensure the image takes up at least MIN_DISPLAY_FRACTION
        // of the smaller viewport dimension
        double minSide   = Math.min(vw, vh);
        double imageSide = Math.min(imageW, imageH);
        double floorZoom = (minSide * MIN_DISPLAY_FRACTION) / imageSide;
        zoom = Math.max(zoom, floorZoom);

        return clamp(zoom, ZOOM_MIN, ZOOM_MAX);
    }

    // ─────────────────────────────────────────────────────────────
    // PRIVATE – APPLY ZOOM TO JAVAFX NODES
    // ─────────────────────────────────────────────────────────────

    /**
     * Resize the ImageView and StackPane to reflect {@code zoom}.
     * Does NOT touch image pixels.
     */
    private void applyZoom(double zoom) {
        zoom = clamp(zoom, ZOOM_MIN, ZOOM_MAX);
        currentZoom = zoom;

        if (lastImageW <= 0 || lastImageH <= 0) return;

        double displayW = lastImageW * zoom;
        double displayH = lastImageH * zoom;

        imageView.setFitWidth(displayW);
        imageView.setFitHeight(displayH);
        checkerPane.setPrefWidth(displayW);
        checkerPane.setPrefHeight(displayH);
    }

    // ─────────────────────────────────────────────────────────────
    // PRIVATE – SCROLL CENTERING
    // ─────────────────────────────────────────────────────────────

    /**
     * Reset the ScrollPane scroll position to centre (0.5, 0.5).
     * Called after every display update to prevent stale scroll
     * offsets from orphaning the image after bounding-box changes.
     *
     * Uses Platform.runLater so the layout pass has completed first.
     */
    private void centerScrollPane() {
        Platform.runLater(() -> {
            scrollPane.setHvalue(0.5);
            scrollPane.setVvalue(0.5);
        });
    }

    // ─────────────────────────────────────────────────────────────
    // PRIVATE – COMBO LABEL UPDATE
    // ─────────────────────────────────────────────────────────────

    /**
     * Update the zoom combo to show "Fit (XX%)" while in auto-fit mode,
     * reflecting the actual current magnification so the user always
     * knows what zoom they are at.
     *
     * The updatingCombo flag prevents this write from firing the
     * combo's onAction handler.
     */
    private void updateComboLabel(double zoom) {
        updatingCombo = true;
        String label = String.format("Fit (%d%%)", Math.round(zoom * 100));
        // Replace or add the dynamic label as the first item
        if (!zoomCombo.getItems().isEmpty() &&
                zoomCombo.getItems().get(0).startsWith("Fit")) {
            zoomCombo.getItems().set(0, label);
        }
        zoomCombo.setValue(label);
        zoomCombo.setPromptText("");
        updatingCombo = false;
    }

    // ─────────────────────────────────────────────────────────────
    // PRIVATE – HELPERS
    // ─────────────────────────────────────────────────────────────

    private static double parseLabelToZoom(String label) {
        try {
            return Double.parseDouble(label.replace("%", "").trim()) / 100.0;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}