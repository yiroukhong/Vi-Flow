package com.wig3003.photoapp.dip.geometric;

import com.wig3003.photoapp.model.MetadataStore;
import com.wig3003.photoapp.util.ImageUtils;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import org.opencv.core.Mat;
import org.opencv.imgcodecs.Imgcodecs;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;


/**
 * Controller for the Geometric Transformation module.
 *
 * ═══════════════════════════════════════════════════════════════
 * KEY FIXES vs the original broken implementation
 * ═══════════════════════════════════════════════════════════════
 *
 * 1. NON-DESTRUCTIVE PIPELINE
 *    renderPipeline() now always rebuilds from originalMat.
 *    The original used currentMat which accumulated transforms.
 *
 * 2. STATE-BASED UNDO
 *    The undo stack holds TransformState records, not Mat copies.
 *    No pixel data is duplicated; restoring a state re-runs the
 *    pipeline from originalMat.  Undo also restores slider/field
 *    positions correctly.
 *
 * 3. DEBOUNCED ASYNC RENDERING
 *    A generation counter prevents stale tasks from overwriting
 *    newer previews.  A 40 ms debounce suppresses excessive
 *    queuing during rapid slider drag.  The thread pool is a
 *    single daemon thread so the JVM can exit cleanly.
 *
 * 4. FXML METHOD NAME ALIGNMENT
 *    handleReset / handleUndo match FXML onAction="#…" exactly.
 *    The original had resetAll / undo which caused runtime errors.
 *
 * 5. VIEWER ZOOM SYSTEM
 *    Separate from transformation state.
 *    ComboBox: Fit Screen, 25%, 50%, 100%, 200%, 400%.
 *    Ctrl + scroll wheel zooms incrementally.
 *    Zoom never touches image pixels or transformation values.
 *
 * 6. TRANSFORMATION INDEPENDENCE
 *    Each transformation slider/field updates only its own state
 *    variable.  Changing rotation never resets scale, etc.
 *
 * 7. SYNC FLAGS are local to each listener to prevent infinite
 *    update loops while still keeping all controls in sync.
 *
 * 8. WHITE FILL for rotation and translation empty regions
 *    (was black in the original).
 */
public class DipGeometricController {

    // =========================================================
    // CONSTANTS
    // =========================================================

    private static final int  MAX_UNDO    = 20;
    private static final long DEBOUNCE_MS = 40;   // ms to wait before rendering

    // =========================================================
    // FXML – VIEWER
    // =========================================================

    @FXML private ImageView    resultView;
    @FXML private StackPane    resultCheckerPane;
    @FXML private ScrollPane   resultScrollPane;
    @FXML private Canvas       checkerCanvas;
    @FXML private Label        resultLabel;
    @FXML private Label        statusBar;
    @FXML private Label        imageInfoLabel;
    @FXML private Button       undoBtn;
    @FXML private ComboBox<String> zoomCombo;

    // =========================================================
    // FXML – SCALE
    // =========================================================

    @FXML private Slider    scaleSlider;
    @FXML private Label     scaleValueLabel;
    @FXML private TextField resizeW;
    @FXML private TextField resizeH;
    @FXML private CheckBox  lockAspect;

    // =========================================================
    // FXML – ROTATE
    // =========================================================

    @FXML private Slider    rotateSlider;
    @FXML private Label     rotateValueLabel;
    @FXML private TextField rotateField;

    // =========================================================
    // FXML – TRANSLATE
    // =========================================================

    @FXML private Slider sliderDx;
    @FXML private Slider sliderDy;
    @FXML private Label  dxValueLabel;
    @FXML private Label  dyValueLabel;
    @FXML private TextField fieldDx;
    @FXML private TextField fieldDy;

    // =========================================================
    // IMAGE STATE
    // =========================================================

    /**
     * The pristine source image loaded from disk.
     * NEVER modified.  Every pipeline run clones from this.
     */
    private Mat originalMat;

    /**
     * The last fully-rendered preview Mat.
     * Used only for display and export; never fed back into the pipeline.
     */
    private Mat latestPreviewMat;

    /** Last directory opened – remembered across load dialogs. */
    private File lastDirectory;

    /**
     * Tracks the scale and rotation of the most recently displayed render.
     * Used by displayPreview() to distinguish translation-only updates
     * (which don't change bounding dimensions) from scale/rotate updates.
     */
    private double lastRenderedScale = 100.0;
    private double lastRenderedAngle = 0.0;

    // =========================================================
    // TRANSFORMATION STATE
    // =========================================================

    /**
     * The single authoritative transformation state.
     * Replaced atomically on every slider / field change.
     * Stored on the undo stack before any intentional commit.
     */
    private TransformState state = TransformState.identity();

    /** Undo stack – holds previous TransformState records only. */
    private final ArrayDeque<TransformState> undoStack = new ArrayDeque<>();

    // =========================================================
    // SMART VIEWPORT  (owns all viewer zoom + centering logic)
    // =========================================================

    /**
     * Initialized in initialize() once FXML nodes are injected.
     * All viewer zoom decisions delegate here; the controller
     * never sets ImageView sizes directly.
     */
    private SmartViewport viewport;

    // =========================================================
    // SYNC FLAGS  (prevent listener → update → listener loops)
    // =========================================================

    private boolean syncingScale     = false;
    private boolean syncingRotate    = false;
    private boolean syncingTranslate = false;

    // =========================================================
    // ASYNC RENDERING
    // =========================================================

    /**
     * Monotonically increasing generation counter.
     * Each new render request increments this; completed tasks
     * whose generation is older than current are silently discarded.
     */
    private final AtomicLong renderGeneration = new AtomicLong(0);

    /**
     * Set to true when shutdown() is called.
     * All background tasks check this before touching the UI so a
     * closed/replaced controller never receives stale callbacks.
     */
    private volatile boolean disposed = false;

    /**
     * Single-threaded executor for preview rendering.
     * Daemon threads – JVM can exit without explicitly shutting down.
     */
    private final ExecutorService renderExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "geometric-render");
                t.setDaemon(true);
                return t;
            });

    /**
     * Executor for background I/O (image load + save).
     * Separate from renderExecutor so a slow save never blocks previews.
     */
    private final ExecutorService ioExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "geometric-io");
                t.setDaemon(true);
                return t;
            });

    /**
     * Scheduled executor for debouncing rapid slider input.
     * Replaces the previous pending render task rather than stacking them.
     */
    private final ScheduledExecutorService debouncer =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "geometric-debounce");
                t.setDaemon(true);
                return t;
            });

    /** Handle to the pending debounced render task so it can be cancelled. */
    private ScheduledFuture<?> pendingRender;

    // =========================================================
    // INITIALIZE
    // =========================================================

    @FXML
    public void initialize() {

        // ── SmartViewport wiring ──────────────────────────────
        // Build preset list from SmartViewport constants so the
        // combo and the viewport class always agree on labels.
        zoomCombo.setItems(
                javafx.collections.FXCollections.observableArrayList(
                        SmartViewport.PRESET_LABELS));
        zoomCombo.getSelectionModel().select(SmartViewport.LABEL_FIT);

        viewport = new SmartViewport(
                resultScrollPane,
                resultCheckerPane,
                resultView,
                zoomCombo);

        // ── Checkerboard background ───────────────────────────
        // Bind to the outer StackPane (viewer container) so the checker
        // fills the ENTIRE viewer area at all times, not just the image pane.
        // The inner resultCheckerPane resizes with the image; the canvas
        // must cover the full background independently.
        checkerCanvas.widthProperty().bind(resultCheckerPane.widthProperty());
        checkerCanvas.heightProperty().bind(resultCheckerPane.heightProperty());
        checkerCanvas.widthProperty().addListener( (o, ov, nv) -> drawChecker());
        checkerCanvas.heightProperty().addListener((o, ov, nv) -> drawChecker());

        // ── Control wiring ────────────────────────────────────
        setupScaleControls();
        setupRotateControls();
        setupTranslateControls();
        setupScrollZoom();

        // ── Initial UI state ──────────────────────────────────
        setControlsDisabled(true);
        updateUndoButton();
        setStatus("No image loaded.");
    }

    // =========================================================
    // SCALE CONTROLS
    // =========================================================

    private void setupScaleControls() {

        // Slider → state + fields
        scaleSlider.valueProperty().addListener((obs, ov, nv) -> {
            if (syncingScale || !imageLoaded()) return;

            state = state.withScale(nv.doubleValue());
            updateScaleLabels(state.scalePercent, true);
            schedulePreview();
        });

        scaleSlider.setOnMouseReleased(e -> {
            if (!imageLoaded()) return;
            setStatus(String.format("Scale %.0f%%", state.scalePercent));
        });

        // W field → aspect-locked H, then preview
        resizeW.textProperty().addListener((obs, ov, nv) -> {
            if (syncingScale || !imageLoaded()) return;
            try {
                int w = Integer.parseInt(nv.trim());
                if (w <= 0) return;
                double pct = w * 100.0 / originalMat.cols();
                pct = clamp(pct, 10, 300);
                state = state.withScale(pct);

                syncingScale = true;
                scaleSlider.setValue(pct);
                scaleValueLabel.setText(String.format("%.0f%%", pct));
                if (lockAspect.isSelected()) {
                    int h = Math.max(1, (int) Math.round(originalMat.rows() * pct / 100.0));
                    resizeH.setText(String.valueOf(h));
                }
                syncingScale = false;

                schedulePreview();
            } catch (NumberFormatException ignored) {}
        });

        // H field → aspect-locked W, then preview
        resizeH.textProperty().addListener((obs, ov, nv) -> {
            if (syncingScale || !imageLoaded()) return;
            try {
                int h = Integer.parseInt(nv.trim());
                if (h <= 0) return;
                double pct = h * 100.0 / originalMat.rows();
                pct = clamp(pct, 10, 300);
                state = state.withScale(pct);

                syncingScale = true;
                scaleSlider.setValue(pct);
                scaleValueLabel.setText(String.format("%.0f%%", pct));
                if (lockAspect.isSelected()) {
                    int w = Math.max(1, (int) Math.round(originalMat.cols() * pct / 100.0));
                    resizeW.setText(String.valueOf(w));
                }
                syncingScale = false;

                schedulePreview();
            } catch (NumberFormatException ignored) {}
        });

        resizeW.setOnKeyPressed(e -> { if (e.getCode() == KeyCode.ENTER) applyResize(); });
        resizeH.setOnKeyPressed(e -> { if (e.getCode() == KeyCode.ENTER) applyResize(); });
    }

    @FXML
    private void applyResize() {
        if (!imageLoaded()) return;
        try {
            int w = Integer.parseInt(resizeW.getText().trim());
            int h = Integer.parseInt(resizeH.getText().trim());
            if (w <= 0 || h <= 0) return;

            // Use W to derive the percentage; lock will have already synced H
            double pct = clamp(w * 100.0 / originalMat.cols(), 10, 300);
            pushUndo();
            state = state.withScale(pct);
            updateScaleLabels(pct, false);
            schedulePreview();
            setStatus(String.format("Scale applied: %d × %d px", w, h));
        } catch (NumberFormatException ignored) {}
    }

    // =========================================================
    // ROTATE CONTROLS
    // =========================================================

    private void setupRotateControls() {

        rotateSlider.valueProperty().addListener((obs, ov, nv) -> {
            if (syncingRotate || !imageLoaded()) return;
            state = state.withRotation(nv.doubleValue());

            syncingRotate = true;
            rotateValueLabel.setText(String.format("%.1f°", state.rotationAngle));
            rotateField.setText(String.format("%.1f", state.rotationAngle));
            syncingRotate = false;

            schedulePreview();
        });

        rotateSlider.setOnMouseReleased(e -> {
            if (!imageLoaded()) return;
            setStatus(String.format("Rotation %.1f°", state.rotationAngle));
        });

        rotateField.setOnKeyPressed(e -> { if (e.getCode() == KeyCode.ENTER) applyRotate(); });
    }

    @FXML
    private void applyRotate() {
        if (!imageLoaded()) return;
        try {
            double angle = Double.parseDouble(rotateField.getText().trim());
            angle = clamp(angle, -180, 180);
            pushUndo();
            state = state.withRotation(angle);

            syncingRotate = true;
            rotateSlider.setValue(angle);
            rotateValueLabel.setText(String.format("%.1f°", angle));
            rotateField.setText(String.format("%.1f", angle));
            syncingRotate = false;

            schedulePreview();
            setStatus(String.format("Rotation applied: %.1f°", angle));
        } catch (NumberFormatException ignored) {}
    }

    // =========================================================
    // TRANSLATE CONTROLS
    // =========================================================

    private void setupTranslateControls() {

        sliderDx.valueProperty().addListener((obs, ov, nv) -> {
            if (syncingTranslate || !imageLoaded()) return;
            state = state.withTranslation(nv.doubleValue(), state.translateY);

            syncingTranslate = true;
            dxValueLabel.setText(String.format("%.0f px", state.translateX));
            fieldDx.setText(String.format("%.0f", state.translateX));
            syncingTranslate = false;

            schedulePreview();
        });

        sliderDy.valueProperty().addListener((obs, ov, nv) -> {
            if (syncingTranslate || !imageLoaded()) return;
            state = state.withTranslation(state.translateX, nv.doubleValue());

            syncingTranslate = true;
            dyValueLabel.setText(String.format("%.0f px", state.translateY));
            fieldDy.setText(String.format("%.0f", state.translateY));
            syncingTranslate = false;

            schedulePreview();
        });

        sliderDx.setOnMouseReleased(e -> {
            if (!imageLoaded()) return;
            setStatus(String.format("Translate X=%.0f Y=%.0f", state.translateX, state.translateY));
        });

        sliderDy.setOnMouseReleased(e -> {
            if (!imageLoaded()) return;
            setStatus(String.format("Translate X=%.0f Y=%.0f", state.translateX, state.translateY));
        });

        fieldDx.setOnKeyPressed(e -> { if (e.getCode() == KeyCode.ENTER) applyTranslate(); });
        fieldDy.setOnKeyPressed(e -> { if (e.getCode() == KeyCode.ENTER) applyTranslate(); });
    }

    @FXML
    private void applyTranslate() {
        if (!imageLoaded()) return;
        try {
            double dx = Double.parseDouble(fieldDx.getText().trim());
            double dy = Double.parseDouble(fieldDy.getText().trim());
            pushUndo();
            state = state.withTranslation(dx, dy);

            syncingTranslate = true;
            sliderDx.setValue(clamp(dx, sliderDx.getMin(), sliderDx.getMax()));
            sliderDy.setValue(clamp(dy, sliderDy.getMin(), sliderDy.getMax()));
            dxValueLabel.setText(String.format("%.0f px", dx));
            dyValueLabel.setText(String.format("%.0f px", dy));
            syncingTranslate = false;

            schedulePreview();
            setStatus(String.format("Translate applied: X=%.0f Y=%.0f", dx, dy));
        } catch (NumberFormatException ignored) {}
    }

    // =========================================================
    // VIEWER ZOOM – ComboBox
    // =========================================================

    @FXML
    private void handleZoomChange() {
        if (viewport == null) return;
        String selected = zoomCombo.getValue();
        viewport.onUserSelectedZoomLabel(selected);
    }

    // =========================================================
    // VIEWER ZOOM – Ctrl + scroll wheel
    // =========================================================

    private void setupScrollZoom() {
        resultScrollPane.addEventFilter(
                javafx.scene.input.ScrollEvent.SCROLL, e -> {
            if (!e.isControlDown() || !imageLoaded()) return;
            e.consume();
            double delta = e.getDeltaY() > 0 ? 1.15 : 1.0 / 1.15;
            viewport.onScrollWheelZoom(delta);
        });
    }

    // =========================================================
    // LOAD IMAGE
    // =========================================================

    @FXML
    private void loadImage() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Open Image");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                "Images", "*.png", "*.jpg", "*.jpeg", "*.bmp",
                "*.tif", "*.tiff", "*.webp"));

        if (lastDirectory != null && lastDirectory.exists())
            chooser.setInitialDirectory(lastDirectory);

        Stage stage = (Stage) resultScrollPane.getScene().getWindow();
        File file = chooser.showOpenDialog(stage);   // must stay on FX thread
        if (file == null) return;

        loadImageFromPath(file.getAbsolutePath());
    }

    /**
     * Load an image from an absolute file path on a background thread.
     * Imgcodecs.imread() can block for large files; keeping it off the
     * FX thread prevents the UI from freezing during load.
     */
    public void loadImageFromPath(String path) {
        setStatus("Loading…");
        setControlsDisabled(true);

        ioExecutor.submit(() -> {
            if (disposed) return;

            Mat loaded = Imgcodecs.imread(path);

            if (loaded.empty()) {
                loaded.release();
                Platform.runLater(() -> {
                    if (disposed) return;
                    showAlertDirect("Failed to load image.\n\n"
                            + "File may be corrupted or in an unsupported format.");
                    setStatus("Load failed.");
                    if (!imageLoaded()) setControlsDisabled(true);
                });
                return;
            }

            final Mat finalLoaded = loaded;
            final File parentDir  = new File(path).getParentFile();
            final String filename = new File(path).getName();

            Platform.runLater(() -> {
                if (disposed) { finalLoaded.release(); return; }
                lastDirectory = parentDir;
                initWithMat(finalLoaded, filename);
            });
        });
    }



    // =========================================================
    // SAVE – to device
    // =========================================================

    @FXML
    private void saveResult() {
        if (!imageLoaded()) { showAlertDirect("No image loaded."); return; }

        // FileChooser must run on FX thread
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save Image");

        FileChooser.ExtensionFilter filterPng =
                new FileChooser.ExtensionFilter("PNG Image (*.png)",  "*.png");
        FileChooser.ExtensionFilter filterJpg =
                new FileChooser.ExtensionFilter("JPEG Image (*.jpg)", "*.jpg");
        FileChooser.ExtensionFilter filterBmp =
                new FileChooser.ExtensionFilter("BMP Image (*.bmp)",  "*.bmp");

        chooser.getExtensionFilters().addAll(filterPng, filterJpg, filterBmp);
        chooser.setSelectedExtensionFilter(filterPng);
        chooser.setInitialFileName("result");

        Stage stage = (Stage) resultScrollPane.getScene().getWindow();
        File file = chooser.showSaveDialog(stage);   // FX thread — correct
        if (file == null) return;

        // Resolve final path with enforced extension before going to background
        FileChooser.ExtensionFilter selected = chooser.getSelectedExtensionFilter();
        String ext = ".png";
        if (selected == filterJpg) ext = ".jpg";
        else if (selected == filterBmp) ext = ".bmp";

        String rawPath  = file.getAbsolutePath();
        String lower    = rawPath.toLowerCase();
        final String savePath;
        if (lower.endsWith(".png") || lower.endsWith(".jpg")
                || lower.endsWith(".jpeg") || lower.endsWith(".bmp")) {
            int dot = rawPath.lastIndexOf('.');
            savePath = rawPath.substring(0, dot) + ext;
        } else {
            savePath = rawPath + ext;
        }

        // Snapshot state for the background thread
        final TransformState saveState = state;
        final Mat srcSnapshot = originalMat.clone();

        setStatus("Saving…");

        ioExecutor.submit(() -> {
            if (disposed) { srcSnapshot.release(); return; }

            // Build full-quality export on background thread
            Mat toSave = null;
            try {
                toSave = GeometricProcessor.applyPipeline(srcSnapshot, saveState, true);
                srcSnapshot.release();

                if (toSave == null || toSave.empty()) {
                    Platform.runLater(() -> {
                        if (!disposed) showAlertDirect(
                                "Export produced an empty result.\nNothing was saved.");
                    });
                    return;
                }

                boolean ok = Imgcodecs.imwrite(savePath, toSave);
                final boolean saved = ok;
                final String name   = new File(savePath).getName();

                Platform.runLater(() -> {
                    if (disposed) return;
                    if (saved) setStatus("Saved: " + name);
                    else       showAlertDirect(
                            "Failed to write file.\n\nCheck permissions and disk space.");
                });

            } finally {
                if (toSave != null) toSave.release();
            }
        });
    }

    // =========================================================
    // SAVE – to library  (stub – pending MetadataStore integration)
    // LOAD – from library  (stub – pending MetadataStore integration)
    // SAVE – both  (stub – pending MetadataStore integration)
    // =========================================================

    @FXML
    private void saveToLibrary() {
        if (!imageLoaded()) {
            showAlertDirect("No image loaded.");
            return;
        }

        Path path = MetadataStore.getInstance()
                .createLibraryImagePath("geometric", ".png");

        final TransformState saveState = state;
        final Mat srcSnapshot = originalMat.clone();

        setStatus("Saving to library...");

        ioExecutor.submit(() -> {
            if (disposed) {
                srcSnapshot.release();
                return;
            }

            Mat toSave = null;
            try {
                toSave = GeometricProcessor.applyPipeline(srcSnapshot, saveState, true);
                srcSnapshot.release();

                if (toSave == null || toSave.empty()) {
                    Platform.runLater(() -> {
                        if (!disposed) showAlertDirect("Export produced an empty result.");
                    });
                    return;
                }

                boolean ok = Imgcodecs.imwrite(path.toString(), toSave);
                String name = path.getFileName().toString();

                Platform.runLater(() -> {
                    if (disposed) return;
                    if (ok) {
                        setStatus("Saved to library: " + name);
                    } else {
                        showAlertDirect("Failed to save image to library.");
                    }
                });
            } finally {
                if (toSave != null) toSave.release();
            }
        });
    }

    @FXML
    private void loadFromLibrary() {
        List<String> paths = MetadataStore.getInstance().getLibraryPaths();

        if (paths.isEmpty()) {
            showAlertDirect("Your Library is empty. Import a folder or save an image to Library first.");
            return;
        }

        ChoiceDialog<String> dialog = new ChoiceDialog<>(paths.get(0), paths);
        dialog.setTitle("Load from Library");
        dialog.setHeaderText("Choose an image from Vi-Flow Library");
        dialog.setContentText("Image:");

        Optional<String> selected = dialog.showAndWait();
        selected.ifPresent(this::loadImageFromPath);
    }

    @FXML
    private void saveBoth() {
        saveResult();
        saveToLibrary();
    }


    // =========================================================
    // RESET
    // =========================================================

    @FXML
    private void handleReset() {
        if (!imageLoaded()) return;
        pushUndo();
        state = TransformState.identity();
        lastRenderedScale = 100.0;
        lastRenderedAngle = 0.0;
        syncAllControlsFromState();
        viewport.resetToFitMode();
        schedulePreview();
        setStatus("All transformations reset.");
    }

    // =========================================================
    // UNDO
    // =========================================================

    /** Called by both toolbar "Undo" and panel "Undo" buttons. */
    @FXML
    private void handleUndo() {
        if (undoStack.isEmpty()) return;
        state = undoStack.pop();
        syncAllControlsFromState();
        schedulePreview();
        updateUndoButton();
        setStatus("Undo: " + state);
    }

    // =========================================================
    // TAB LIFECYCLE – called by DipEditController
    // =========================================================

    /**
     * Call when the user navigates AWAY from this tab.
     * Pauses background rendering without destroying state.
     * The image and transformation state are preserved.
     */
    public void onTabDeselected() {
        if (pendingRender != null && !pendingRender.isDone())
            pendingRender.cancel(false);
    }

    /**
     * Call when the user navigates BACK to this tab.
     * Re-renders the preview in case state changed while away.
     */
    public void onTabSelected() {
        if (imageLoaded()) schedulePreview();
    }

    // =========================================================
    // INITIALIZE WITH A NEW IMAGE
    // =========================================================

    private void initWithMat(Mat loaded, String filename) {
        releaseMat(originalMat);
        releaseMat(latestPreviewMat);
        undoStack.clear();

        originalMat      = loaded;
        latestPreviewMat = null;
        state            = TransformState.identity();
        lastRenderedScale = 100.0;
        lastRenderedAngle = 0.0;

        syncAllControlsFromState();
        setControlsDisabled(false);
        resultLabel.setVisible(false);
        updateUndoButton();
        updateImageInfoLabel();

        // Tell the viewport a new image has arrived so it fits immediately.
        // The actual ImageView content arrives from the async render below,
        // but we prime dimensions now so viewport can pre-calculate.
        viewport.onImageLoaded(loaded.cols(), loaded.rows());

        renderPreviewNow(true);
        setStatus(String.format("Loaded  %s  (%d × %d px)",
                filename, originalMat.cols(), originalMat.rows()));
    }

    // =========================================================
    // ASYNC PREVIEW RENDERING – DEBOUNCED
    // =========================================================

    /**
     * Schedule a preview render after a short debounce delay.
     * Cancels any previously pending render that has not yet started.
     * Uses fast (interactive) interpolation.
     */
    private void schedulePreview() {
        if (!imageLoaded()) return;

        if (pendingRender != null && !pendingRender.isDone())
            pendingRender.cancel(false);

        pendingRender = debouncer.schedule(
                () -> submitRenderTask(false),   // false = interactive quality
                DEBOUNCE_MS,
                TimeUnit.MILLISECONDS);
    }

    /**
     * Submit a render immediately (no debounce).
     * Used for initial load and explicit Apply buttons.
     */
    private void renderPreviewNow(boolean highQuality) {
        if (pendingRender != null && !pendingRender.isDone())
            pendingRender.cancel(false);
        submitRenderTask(highQuality);
    }

    /**
     * Submit a Task to the render thread pool.
     * The generation counter ensures only the latest result
     * is displayed; stale tasks are discarded silently.
     */
    private void submitRenderTask(boolean highQuality) {
        if (!imageLoaded()) return;

        // Snapshot state now so the task captures it immutably
        final TransformState capturedState = state;
        final long generation = renderGeneration.incrementAndGet();

        // We need a snapshot of originalMat on the render thread.
        // Clone here on the calling thread (which may be the debounce thread).
        // The clone is small enough that this is safe.
        final Mat srcClone = originalMat.clone();

        renderExecutor.submit(() -> {
            Mat result = null;
            try {
                result = GeometricProcessor.applyPipeline(srcClone, capturedState, highQuality);
            } finally {
                srcClone.release();
            }

            if (result == null || result.empty()) {
                if (result != null) result.release();
                return;
            }

            // Discard if a newer render has been submitted
            if (renderGeneration.get() != generation) {
                result.release();
                return;
            }

            final Mat finalResult = result;
            Platform.runLater(() -> {
                if (!disposed) displayPreview(finalResult, capturedState);
                else           finalResult.release();
            });
        });
    }

    /**
     * Push rendered Mat into the ImageView and notify SmartViewport.
     * Routes to onTransformResult (scale/rotate) or onTranslationResult
     * based on whether the canvas bounding box could have changed.
     * Must be called on the JavaFX application thread.
     */
    private void displayPreview(Mat rendered, TransformState renderedState) {
        releaseMat(latestPreviewMat);
        latestPreviewMat = rendered;

        resultView.setImage(ImageUtils.matToWritableImage(rendered));

        double rw = rendered.cols();
        double rh = rendered.rows();

        // Translation keeps the same canvas size as the scaled+rotated image,
        // so we don't recalculate zoom for pure translation changes.
        // We detect "only translation changed" by comparing to original dimensions
        // scaled by the current scale — if scale+rotation are identity the canvas
        // is the same regardless of translation, so treat as translation-only.
        boolean onlyTranslation =
                (renderedState.scalePercent  == lastRenderedScale) &&
                (renderedState.rotationAngle == lastRenderedAngle);

        lastRenderedScale = renderedState.scalePercent;
        lastRenderedAngle = renderedState.rotationAngle;

        if (onlyTranslation) {
            viewport.onTranslationResult(rw, rh);
        } else {
            viewport.onTransformResult(rw, rh);
        }

        updateImageInfoLabel();
    }

    // =========================================================
    // SYNC ALL CONTROLS FROM CURRENT STATE
    // =========================================================

    /**
     * Push {@code state} values into all sliders and text fields
     * without triggering their listeners (via sync flags).
     */
    private void syncAllControlsFromState() {
        // Scale
        syncingScale = true;
        scaleSlider.setValue(state.scalePercent);
        scaleValueLabel.setText(String.format("%.0f%%", state.scalePercent));
        if (originalMat != null) {
            resizeW.setText(String.valueOf(
                    Math.max(1, (int) Math.round(originalMat.cols() * state.scalePercent / 100.0))));
            resizeH.setText(String.valueOf(
                    Math.max(1, (int) Math.round(originalMat.rows() * state.scalePercent / 100.0))));
        }
        syncingScale = false;

        // Rotate
        syncingRotate = true;
        rotateSlider.setValue(state.rotationAngle);
        rotateValueLabel.setText(String.format("%.1f°", state.rotationAngle));
        rotateField.setText(String.format("%.1f", state.rotationAngle));
        syncingRotate = false;

        // Translate
        syncingTranslate = true;
        sliderDx.setValue(clamp(state.translateX, sliderDx.getMin(), sliderDx.getMax()));
        sliderDy.setValue(clamp(state.translateY, sliderDy.getMin(), sliderDy.getMax()));
        dxValueLabel.setText(String.format("%.0f px", state.translateX));
        dyValueLabel.setText(String.format("%.0f px", state.translateY));
        fieldDx.setText(String.format("%.0f", state.translateX));
        fieldDy.setText(String.format("%.0f", state.translateY));
        syncingTranslate = false;
    }

    // =========================================================
    // UNDO STACK
    // =========================================================

    private void pushUndo() {
        if (undoStack.size() >= MAX_UNDO)
            undoStack.removeLast();   // drop oldest to keep memory bounded
        undoStack.push(state);        // push current state record (no Mat copy!)
        updateUndoButton();
    }

    private void updateUndoButton() {
        Platform.runLater(() -> undoBtn.setDisable(undoStack.isEmpty()));
    }

    // =========================================================
    // SCALE LABEL HELPER
    // =========================================================

    private void updateScaleLabels(double pct, boolean updateFields) {
        syncingScale = true;
        scaleSlider.setValue(pct);
        scaleValueLabel.setText(String.format("%.0f%%", pct));
        if (updateFields && originalMat != null) {
            resizeW.setText(String.valueOf(
                    Math.max(1, (int) Math.round(originalMat.cols() * pct / 100.0))));
            resizeH.setText(String.valueOf(
                    Math.max(1, (int) Math.round(originalMat.rows() * pct / 100.0))));
        }
        syncingScale = false;
    }

    // =========================================================
    // CHECKERBOARD BACKGROUND
    // =========================================================

    private void drawChecker() {
        double w = checkerCanvas.getWidth();
        double h = checkerCanvas.getHeight();
        if (w <= 0 || h <= 0) return;

        GraphicsContext gc = checkerCanvas.getGraphicsContext2D();
        gc.clearRect(0, 0, w, h);

        int tile = 20;
        // Clearly visible two-tone dark checker — matches professional editors
        Color dark  = Color.web("#1a1a1a");   // near-black
        Color light = Color.web("#2e2e2e");   // visible mid-grey

        for (int r = 0; r * tile < h; r++) {
            for (int c = 0; c * tile < w; c++) {
                gc.setFill((r + c) % 2 == 0 ? dark : light);
                gc.fillRect(c * tile, r * tile, tile, tile);
            }
        }
    }

    // =========================================================
    // IMAGE INFO LABEL
    // =========================================================

    private void updateImageInfoLabel() {
        if (imageInfoLabel == null) return;
        if (!imageLoaded()) {
            imageInfoLabel.setText("");
            return;
        }
        String orig = String.format("Original: %d × %d px",
                originalMat.cols(), originalMat.rows());
        String preview = (latestPreviewMat != null && !latestPreviewMat.empty())
                ? String.format("Preview:  %d × %d px",
                        latestPreviewMat.cols(), latestPreviewMat.rows())
                : "";
        imageInfoLabel.setText(orig + "\n" + preview);
    }

    // =========================================================
    // ENABLE / DISABLE CONTROLS
    // =========================================================

    private void setControlsDisabled(boolean disabled) {
        scaleSlider.setDisable(disabled);
        resizeW.setDisable(disabled);
        resizeH.setDisable(disabled);
        lockAspect.setDisable(disabled);
        rotateSlider.setDisable(disabled);
        rotateField.setDisable(disabled);
        sliderDx.setDisable(disabled);
        sliderDy.setDisable(disabled);
        fieldDx.setDisable(disabled);
        fieldDy.setDisable(disabled);
    }

    // =========================================================
    // HELPERS
    // =========================================================

    private boolean imageLoaded() {
        return originalMat != null && !originalMat.empty();
    }

    private void setStatus(String msg) {
        Platform.runLater(() -> statusBar.setText(msg));
    }

    /**
     * Show an alert dialog.
     * Safe to call from any thread — wraps in Platform.runLater if needed.
     */
    private void showAlert(String msg) {
        if (Platform.isFxApplicationThread()) showAlertDirect(msg);
        else Platform.runLater(() -> showAlertDirect(msg));
    }

    /**
     * Show an alert dialog — must be called on the JavaFX Application Thread.
     */
    private void showAlertDirect(String msg) {
        Alert a = new Alert(Alert.AlertType.INFORMATION);
        a.setHeaderText(null);
        a.setContentText(msg);
        a.showAndWait();
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    // =========================================================
    // MEMORY MANAGEMENT
    // =========================================================

    private void releaseMat(Mat mat) {
        if (mat != null && !mat.empty()) mat.release();
    }

    /**
     * Called when this module is closed or the tab is switched away.
     *
     * Sets the disposed flag first so no in-flight background task
     * can call Platform.runLater() back onto a dead controller.
     * Then cancels pending renders, shuts down all executors, and
     * releases all OpenCV Mat memory.
     */
    public void shutdown() {
        disposed = true;

        // Cancel any pending debounced render
        if (pendingRender != null && !pendingRender.isDone())
            pendingRender.cancel(false);

        debouncer.shutdownNow();
        renderExecutor.shutdownNow();
        ioExecutor.shutdownNow();

        releaseMat(originalMat);
        releaseMat(latestPreviewMat);
        originalMat      = null;
        latestPreviewMat = null;
    }
}