package com.wig3003.photoapp.dip.radiometric;

import com.wig3003.photoapp.dip.geometric.SmartViewport;
import com.wig3003.photoapp.model.MetadataStore;
import com.wig3003.photoapp.util.ImageUtils;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
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

public class DipRadiometricController {

    // =========================================================
    // INNER STATE RECORD
    // =========================================================

    private static final class RadioState {
        final double brightness;
        final double contrast;
        final boolean grayscale;

        RadioState(double brightness, double contrast, boolean grayscale) {
            this.brightness = brightness;
            this.contrast   = contrast;
            this.grayscale  = grayscale;
        }
    }

    // =========================================================
    // CONSTANTS
    // =========================================================

    private static final int  MAX_UNDO    = 20;
    private static final long DEBOUNCE_MS = 40;

    // =========================================================
    // FXML — VIEWER
    // =========================================================

    @FXML private ImageView  resultView;
    @FXML private StackPane  resultCheckerPane;
    @FXML private ScrollPane resultScrollPane;
    @FXML private Canvas     checkerCanvas;
    @FXML private Label      resultLabel;
    @FXML private Label      statusBar;
    @FXML private Label      imageInfoLabel;
    @FXML private Button     undoBtn;
    @FXML private ComboBox<String> zoomCombo;

    // =========================================================
    // FXML — CONTROLS
    // =========================================================

    @FXML private Slider        brightnessSlider;
    @FXML private Label         brightnessValueLabel;
    @FXML private Slider        contrastSlider;
    @FXML private Label         contrastValueLabel;
    @FXML private ToggleButton  grayscaleToggle;

    // =========================================================
    // IMAGE STATE
    // =========================================================

    private Mat originalMat;
    private Mat latestPreviewMat;
    private File lastDirectory;

    // =========================================================
    // TRANSFORM STATE
    // =========================================================

    private double  brightness = 0.0;
    private double  contrast   = 1.0;
    private boolean grayscale  = false;

    private final ArrayDeque<RadioState> undoStack = new ArrayDeque<>();

    // =========================================================
    // SMART VIEWPORT
    // =========================================================

    private SmartViewport viewport;

    // =========================================================
    // SYNC FLAGS
    // =========================================================

    private boolean syncingSliders = false;

    // =========================================================
    // ASYNC RENDERING
    // =========================================================

    private final AtomicLong renderGeneration = new AtomicLong(0);
    private volatile boolean disposed = false;

    private final ExecutorService renderExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "radiometric-render");
                t.setDaemon(true);
                return t;
            });

    private final ExecutorService ioExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "radiometric-io");
                t.setDaemon(true);
                return t;
            });

    private final ScheduledExecutorService debouncer =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "radiometric-debounce");
                t.setDaemon(true);
                return t;
            });

    private ScheduledFuture<?> pendingRender;

    // =========================================================
    // INITIALIZE
    // =========================================================

    @FXML
    public void initialize() {
        // Zoom combo
        zoomCombo.setItems(
                javafx.collections.FXCollections.observableArrayList(
                        SmartViewport.PRESET_LABELS));
        zoomCombo.getSelectionModel().select(SmartViewport.LABEL_FIT);

        viewport = new SmartViewport(
                resultScrollPane,
                resultCheckerPane,
                resultView,
                zoomCombo);

        // Checkerboard
        checkerCanvas.widthProperty().bind(resultCheckerPane.widthProperty());
        checkerCanvas.heightProperty().bind(resultCheckerPane.heightProperty());
        checkerCanvas.widthProperty().addListener( (o, ov, nv) -> drawChecker());
        checkerCanvas.heightProperty().addListener((o, ov, nv) -> drawChecker());

        // Brightness slider — live preview
        brightnessSlider.valueProperty().addListener((obs, ov, nv) -> {
            if (syncingSliders || !imageLoaded()) return;
            brightness = nv.doubleValue();
            brightnessValueLabel.setText(String.format("%.0f", brightness));
            schedulePreview();
        });

        // Contrast slider — live preview
        contrastSlider.valueProperty().addListener((obs, ov, nv) -> {
            if (syncingSliders || !imageLoaded()) return;
            contrast = nv.doubleValue();
            contrastValueLabel.setText(String.format("%.2f×", contrast));
            schedulePreview();
        });

        setControlsDisabled(true);
        updateUndoButton();
        setStatus("No image loaded.");
    }

    // =========================================================
    // TAB LIFECYCLE
    // =========================================================

    public void onTabSelected() {
        if (imageLoaded()) schedulePreview();
    }

    public void onTabDeselected() {
        if (pendingRender != null && !pendingRender.isDone())
            pendingRender.cancel(false);
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
        File file = chooser.showOpenDialog(stage);
        if (file == null) return;

        loadImageFromPath(file.getAbsolutePath());
    }

    public void loadImageFromPath(String path) {
        if (path == null || path.isBlank() || disposed) return;
        setStatus("Loading…");
        setControlsDisabled(true);

        ioExecutor.submit(() -> {
            if (disposed) return;

            Mat loaded;
            try {
                loaded = ImageUtils.loadMatFromPath(path);
            } catch (Exception e) {
                Platform.runLater(() -> {
                    if (disposed) return;
                    showAlertDirect("Failed to load image.\n\n" + e.getMessage());
                    setStatus("Load failed.");
                    if (!imageLoaded()) setControlsDisabled(true);
                });
                return;
            }

            final Mat finalLoaded  = loaded;
            final File parentDir   = new File(path).getParentFile();
            final String filename  = new File(path).getName();

            Platform.runLater(() -> {
                if (disposed) { finalLoaded.release(); return; }
                lastDirectory = parentDir;
                initWithMat(finalLoaded, filename);
            });
        });
    }

    private void initWithMat(Mat loaded, String filename) {
        releaseMat(originalMat);
        releaseMat(latestPreviewMat);
        undoStack.clear();

        originalMat      = loaded;
        latestPreviewMat = null;

        // Reset state
        brightness = 0.0;
        contrast   = 1.0;
        grayscale  = false;

        syncingSliders = true;
        brightnessSlider.setValue(0.0);
        brightnessValueLabel.setText("0");
        contrastSlider.setValue(1.0);
        contrastValueLabel.setText("1.00×");
        grayscaleToggle.setSelected(false);
        grayscaleToggle.setText("Off");
        syncingSliders = false;

        setControlsDisabled(false);
        resultLabel.setVisible(false);
        updateUndoButton();
        updateImageInfoLabel();

        viewport.onImageLoaded(loaded.cols(), loaded.rows());
        submitRenderTask();
        setStatus(String.format("Loaded  %s  (%d × %d px)",
                filename, originalMat.cols(), originalMat.rows()));
    }

    // =========================================================
    // SAVE
    // =========================================================

    @FXML
    private void saveResult() {
        if (!imageLoaded()) { showAlertDirect("No image loaded."); return; }

        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save Image");
        FileChooser.ExtensionFilter filterPng =
                new FileChooser.ExtensionFilter("PNG Image (*.png)", "*.png");
        FileChooser.ExtensionFilter filterJpg =
                new FileChooser.ExtensionFilter("JPEG Image (*.jpg)", "*.jpg");
        chooser.getExtensionFilters().addAll(filterPng, filterJpg);
        chooser.setSelectedExtensionFilter(filterPng);
        chooser.setInitialFileName("result");

        Stage stage = (Stage) resultScrollPane.getScene().getWindow();
        File file = chooser.showSaveDialog(stage);
        if (file == null) return;

        FileChooser.ExtensionFilter sel = chooser.getSelectedExtensionFilter();
        String ext = (sel == filterJpg) ? ".jpg" : ".png";
        String rawPath = file.getAbsolutePath();
        String lower   = rawPath.toLowerCase();
        final String savePath;
        if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            int dot = rawPath.lastIndexOf('.');
            savePath = rawPath.substring(0, dot) + ext;
        } else {
            savePath = rawPath + ext;
        }

        final Mat srcSnapshot = originalMat.clone();
        final double capBrightness = brightness;
        final double capContrast   = contrast;
        final boolean capGrayscale = grayscale;
        setStatus("Saving…");

        ioExecutor.submit(() -> {
            if (disposed) { srcSnapshot.release(); return; }
            Mat result = buildResult(srcSnapshot, capContrast, capBrightness, capGrayscale);
            srcSnapshot.release();
            if (result == null || result.empty()) {
                Platform.runLater(() -> { if (!disposed) showAlertDirect("Export produced an empty result."); });
                return;
            }
            boolean ok = Imgcodecs.imwrite(savePath, result);
            result.release();
            final String name = new File(savePath).getName();
            Platform.runLater(() -> {
                if (disposed) return;
                if (ok) setStatus("Saved: " + name);
                else    showAlertDirect("Failed to write file.\n\nCheck permissions and disk space.");
            });
        });
    }

    @FXML
    private void saveToLibrary() {
        if (!imageLoaded()) { showAlertDirect("No image loaded."); return; }

        Path path = MetadataStore.getInstance()
                .createLibraryImagePath("radiometric", ".png");

        final Mat srcSnapshot  = originalMat.clone();
        final double capB      = brightness;
        final double capC      = contrast;
        final boolean capG     = grayscale;
        setStatus("Saving to library…");

        ioExecutor.submit(() -> {
            if (disposed) { srcSnapshot.release(); return; }
            Mat result = buildResult(srcSnapshot, capC, capB, capG);
            srcSnapshot.release();
            if (result == null || result.empty()) {
                Platform.runLater(() -> { if (!disposed) showAlertDirect("Export produced an empty result."); });
                return;
            }
            boolean ok = Imgcodecs.imwrite(path.toString(), result);
            result.release();
            String name = path.getFileName().toString();
            Platform.runLater(() -> {
                if (disposed) return;
                if (ok) setStatus("Saved to library: " + name);
                else    showAlertDirect("Failed to save to library.");
            });
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
    // GRAYSCALE TOGGLE
    // =========================================================

    @FXML
    private void handleGrayscaleToggle() {
        if (!imageLoaded()) return;
        pushUndo();
        grayscale = grayscaleToggle.isSelected();
        grayscaleToggle.setText(grayscale ? "On" : "Off");
        schedulePreview();
    }

    // =========================================================
    // UNDO / RESET
    // =========================================================

    @FXML
    private void handleUndo() {
        if (undoStack.isEmpty()) return;
        RadioState prev = undoStack.pop();
        brightness = prev.brightness;
        contrast   = prev.contrast;
        grayscale  = prev.grayscale;
        syncControlsFromState();
        schedulePreview();
        updateUndoButton();
        setStatus("Undo.");
    }

    @FXML
    private void handleReset() {
        if (!imageLoaded()) return;
        pushUndo();
        brightness = 0.0;
        contrast   = 1.0;
        grayscale  = false;
        syncControlsFromState();
        viewport.resetToFitMode();
        schedulePreview();
        setStatus("All adjustments reset.");
    }

    // =========================================================
    // ZOOM
    // =========================================================

    @FXML
    private void handleZoomChange() {
        if (viewport == null) return;
        String selected = zoomCombo.getValue();
        viewport.onUserSelectedZoomLabel(selected);
    }

    // =========================================================
    // ASYNC PREVIEW RENDERING
    // =========================================================

    private void schedulePreview() {
        if (!imageLoaded()) return;
        if (pendingRender != null && !pendingRender.isDone())
            pendingRender.cancel(false);

        pendingRender = debouncer.schedule(
                this::submitRenderTask,
                DEBOUNCE_MS,
                TimeUnit.MILLISECONDS);
    }

    private void submitRenderTask() {
        if (!imageLoaded()) return;

        final double   capB        = brightness;
        final double   capC        = contrast;
        final boolean  capG        = grayscale;
        final long     generation  = renderGeneration.incrementAndGet();
        final Mat      srcClone    = originalMat.clone();

        renderExecutor.submit(() -> {
            Mat result = buildResult(srcClone, capC, capB, capG);
            srcClone.release();

            if (result == null || result.empty()) {
                if (result != null) result.release();
                return;
            }
            if (renderGeneration.get() != generation) {
                result.release();
                return;
            }

            final Mat finalResult = result;
            Platform.runLater(() -> {
                if (!disposed) displayPreview(finalResult);
                else           finalResult.release();
            });
        });
    }

    /**
     * Applies brightness/contrast and optional grayscale to src.
     * Delegates to RadiometricProcessor when available.
     * src is consumed (cloned internally if needed); caller releases it.
     */
    private Mat buildResult(Mat src, double capContrast, double capBrightness, boolean capGrayscale) {
        Mat result = src.clone();
        try {
            // TODO: replace stub with RadiometricProcessor once implemented
            // result = RadiometricProcessor.adjustBrightnessContrast(result, capContrast, capBrightness);
            result.convertTo(result, -1, capContrast, capBrightness);

            if (capGrayscale) {
                // TODO: replace stub with RadiometricProcessor.toGrayscale(result)
                Mat gray = new Mat();
                org.opencv.imgproc.Imgproc.cvtColor(result, gray, org.opencv.imgproc.Imgproc.COLOR_BGR2GRAY);
                org.opencv.imgproc.Imgproc.cvtColor(gray, result, org.opencv.imgproc.Imgproc.COLOR_GRAY2BGR);
                gray.release();
            }
        } catch (Exception e) {
            // If processing fails, return the unmodified clone
        }
        return result;
    }

    private void displayPreview(Mat rendered) {
        releaseMat(latestPreviewMat);
        latestPreviewMat = rendered;
        resultView.setImage(ImageUtils.matToWritableImage(rendered));
        viewport.onTransformResult(rendered.cols(), rendered.rows());
        updateImageInfoLabel();
    }

    // =========================================================
    // SYNC CONTROLS FROM STATE
    // =========================================================

    private void syncControlsFromState() {
        syncingSliders = true;
        brightnessSlider.setValue(brightness);
        brightnessValueLabel.setText(String.format("%.0f", brightness));
        contrastSlider.setValue(contrast);
        contrastValueLabel.setText(String.format("%.2f×", contrast));
        grayscaleToggle.setSelected(grayscale);
        grayscaleToggle.setText(grayscale ? "On" : "Off");
        syncingSliders = false;
    }

    // =========================================================
    // UNDO STACK
    // =========================================================

    private void pushUndo() {
        if (undoStack.size() >= MAX_UNDO)
            undoStack.removeLast();
        undoStack.push(new RadioState(brightness, contrast, grayscale));
        updateUndoButton();
    }

    private void updateUndoButton() {
        Platform.runLater(() -> undoBtn.setDisable(undoStack.isEmpty()));
    }

    // =========================================================
    // CHECKERBOARD
    // =========================================================

    private void drawChecker() {
        double w = checkerCanvas.getWidth();
        double h = checkerCanvas.getHeight();
        if (w <= 0 || h <= 0) return;

        GraphicsContext gc = checkerCanvas.getGraphicsContext2D();
        gc.clearRect(0, 0, w, h);

        int tile  = 20;
        Color dark  = Color.web("#1a1a1a");
        Color light = Color.web("#2e2e2e");

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
        if (!imageLoaded()) { imageInfoLabel.setText(""); return; }
        String orig    = String.format("Original: %d × %d px",
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
        brightnessSlider.setDisable(disabled);
        contrastSlider.setDisable(disabled);
        grayscaleToggle.setDisable(disabled);
    }

    // =========================================================
    // SHUTDOWN
    // =========================================================

    public void shutdown() {
        disposed = true;

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

    // =========================================================
    // HELPERS
    // =========================================================

    private boolean imageLoaded() {
        return originalMat != null && !originalMat.empty();
    }

    private void setStatus(String msg) {
        Platform.runLater(() -> statusBar.setText(msg));
    }

    private void showAlertDirect(String msg) {
        Alert a = new Alert(Alert.AlertType.INFORMATION);
        a.setHeaderText(null);
        a.setContentText(msg);
        a.showAndWait();
    }

    private void releaseMat(Mat mat) {
        if (mat != null && !mat.empty()) mat.release();
    }
}
