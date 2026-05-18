package com.wig3003.photoapp.dip.aesthetic;

import com.wig3003.photoapp.dip.geometric.SmartViewport;
import com.wig3003.photoapp.model.MetadataStore;
import com.wig3003.photoapp.util.ImageUtils;

import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import org.opencv.core.Mat;
import org.opencv.core.Scalar;
import org.opencv.imgcodecs.Imgcodecs;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

public class DipAestheticController {

    // =========================================================
    // INNER STATE RECORD
    // =========================================================

    private static final class AestheticState {
        final int    borderWidth;
        final String borderType;
        final String borderColor;

        AestheticState(int borderWidth, String borderType, String borderColor) {
            this.borderWidth = borderWidth;
            this.borderType  = borderType;
            this.borderColor = borderColor;
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

    @FXML private ToggleGroup  borderGroup;
    @FXML private ToggleButton borderNone;
    @FXML private ToggleButton borderConstant;
    @FXML private ToggleButton borderReflect;
    @FXML private ToggleButton borderReplicate;

    @FXML private Slider borderWidthSlider;
    @FXML private Label  borderWidthLabel;

    @FXML private VBox colorPickerSection;

    // =========================================================
    // IMAGE STATE
    // =========================================================

    private Mat  originalMat;
    private Mat  latestPreviewMat;
    private File lastDirectory;

    // =========================================================
    // TRANSFORM STATE
    // =========================================================

    private int    borderWidth = 14;
    private String borderType  = "NONE";
    private String borderColor = "#FFFFFF";

    private Button selectedSwatchBtn;

    private final ArrayDeque<AestheticState> undoStack = new ArrayDeque<>();

    // =========================================================
    // SMART VIEWPORT
    // =========================================================

    private SmartViewport viewport;

    // =========================================================
    // SYNC FLAGS
    // =========================================================

    private boolean syncingSlider = false;

    // =========================================================
    // ASYNC RENDERING
    // =========================================================

    private final AtomicLong renderGeneration = new AtomicLong(0);
    private volatile boolean disposed = false;

    private final ExecutorService renderExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "aesthetic-render");
                t.setDaemon(true);
                return t;
            });

    private final ExecutorService ioExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "aesthetic-io");
                t.setDaemon(true);
                return t;
            });

    private final ScheduledExecutorService debouncer =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "aesthetic-debounce");
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

        // Border width slider — live preview
        borderWidthSlider.valueProperty().addListener((obs, ov, nv) -> {
            if (syncingSlider || !imageLoaded()) return;
            borderWidth = nv.intValue();
            borderWidthLabel.setText(borderWidth + " px");
            schedulePreview();
        });

        // Color picker starts hidden (borderType = NONE)
        colorPickerSection.setVisible(false);
        colorPickerSection.setManaged(false);

        // Mark the first swatch (white) as selected by default
        Platform.runLater(() -> {
            Button first = getSwatchButtons().isEmpty() ? null : getSwatchButtons().get(0);
            if (first != null) markSwatchSelected(first);
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

            final Mat    finalLoaded = loaded;
            final File   parentDir   = new File(path).getParentFile();
            final String filename    = new File(path).getName();

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
        borderWidth = 14;
        borderType  = "NONE";
        borderColor = "#FFFFFF";

        syncingSlider = true;
        borderWidthSlider.setValue(14);
        borderWidthLabel.setText("14 px");
        syncingSlider = false;

        borderNone.setSelected(true);
        applyToggleStyles();
        colorPickerSection.setVisible(false);
        colorPickerSection.setManaged(false);

        // Reset swatch selection to white
        List<Button> swatches = getSwatchButtons();
        if (!swatches.isEmpty()) markSwatchSelected(swatches.get(0));

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
        String ext     = (sel == filterJpg) ? ".jpg" : ".png";
        String rawPath = file.getAbsolutePath();
        String lower   = rawPath.toLowerCase();
        final String savePath;
        if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            int dot = rawPath.lastIndexOf('.');
            savePath = rawPath.substring(0, dot) + ext;
        } else {
            savePath = rawPath + ext;
        }

        final Mat    srcSnapshot = originalMat.clone();
        final int    capW        = borderWidth;
        final String capT        = borderType;
        final String capC        = borderColor;
        setStatus("Saving…");

        ioExecutor.submit(() -> {
            if (disposed) { srcSnapshot.release(); return; }
            Mat result = buildResult(srcSnapshot, capW, capT, capC);
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
                .createLibraryImagePath("aesthetic", ".png");

        final Mat    srcSnapshot = originalMat.clone();
        final int    capW        = borderWidth;
        final String capT        = borderType;
        final String capC        = borderColor;
        setStatus("Saving to library…");

        ioExecutor.submit(() -> {
            if (disposed) { srcSnapshot.release(); return; }
            Mat result = buildResult(srcSnapshot, capW, capT, capC);
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
    // BORDER TYPE TOGGLE
    // =========================================================

    @FXML
    private void handleBorderTypeChange() {
        if (!imageLoaded()) {
            applyToggleStyles();
            return;
        }
        pushUndo();

        Toggle sel = borderGroup.getSelectedToggle();
        if (sel == borderConstant)  borderType = "CONSTANT";
        else if (sel == borderReflect)   borderType = "REFLECT";
        else if (sel == borderReplicate) borderType = "REPLICATE";
        else                             borderType = "NONE";

        boolean showColor = "CONSTANT".equals(borderType);
        colorPickerSection.setVisible(showColor);
        colorPickerSection.setManaged(showColor);

        applyToggleStyles();
        schedulePreview();
    }

    // =========================================================
    // COLOR SWATCH
    // =========================================================

    @FXML
    private void handleColorSwatch(ActionEvent e) {
        if (!(e.getSource() instanceof Button)) return;
        Button btn = (Button) e.getSource();
        Object userData = btn.getUserData();
        if (userData == null) return;

        borderColor = userData.toString();
        markSwatchSelected(btn);

        if (imageLoaded()) schedulePreview();
    }

    // =========================================================
    // UNDO / RESET
    // =========================================================

    @FXML
    private void handleUndo() {
        if (undoStack.isEmpty()) return;
        AestheticState prev = undoStack.pop();
        borderWidth = prev.borderWidth;
        borderType  = prev.borderType;
        borderColor = prev.borderColor;
        syncControlsFromState();
        schedulePreview();
        updateUndoButton();
        setStatus("Undo.");
    }

    @FXML
    private void handleReset() {
        if (!imageLoaded()) return;
        pushUndo();
        borderWidth = 14;
        borderType  = "NONE";
        borderColor = "#FFFFFF";
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
        viewport.onUserSelectedZoomLabel(zoomCombo.getValue());
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

        final int    capW       = borderWidth;
        final String capT       = borderType;
        final String capC       = borderColor;
        final long   generation = renderGeneration.incrementAndGet();
        final Mat    srcClone   = originalMat.clone();

        renderExecutor.submit(() -> {
            Mat result = buildResult(srcClone, capW, capT, capC);
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
     * Applies border to src according to the current state.
     * Delegates to AestheticProcessor when available.
     */
    private Mat buildResult(Mat src, int capWidth, String capType, String capColor) {
        if ("NONE".equals(capType) || capWidth <= 0) {
            return src.clone();
        }

        // TODO: replace stub with AestheticProcessor.applyBorder(src, capWidth, capType, capColor)
        Mat result = new Mat();
        try {
            int cvBorderType;
            switch (capType) {
                case "REFLECT":    cvBorderType = org.opencv.core.Core.BORDER_REFLECT;    break;
                case "REPLICATE":  cvBorderType = org.opencv.core.Core.BORDER_REPLICATE;  break;
                case "CONSTANT":   cvBorderType = org.opencv.core.Core.BORDER_CONSTANT;   break;
                default:           return src.clone();
            }

            Scalar colorScalar = hexToScalar(capColor);
            org.opencv.core.Core.copyMakeBorder(
                    src, result,
                    capWidth, capWidth, capWidth, capWidth,
                    cvBorderType, colorScalar);
        } catch (Exception e) {
            result.release();
            return src.clone();
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
        syncingSlider = true;
        borderWidthSlider.setValue(borderWidth);
        borderWidthLabel.setText(borderWidth + " px");
        syncingSlider = false;

        // Restore toggle selection
        if ("CONSTANT".equals(borderType))       borderConstant.setSelected(true);
        else if ("REFLECT".equals(borderType))   borderReflect.setSelected(true);
        else if ("REPLICATE".equals(borderType)) borderReplicate.setSelected(true);
        else                                     borderNone.setSelected(true);

        boolean showColor = "CONSTANT".equals(borderType);
        colorPickerSection.setVisible(showColor);
        colorPickerSection.setManaged(showColor);

        applyToggleStyles();

        // Restore swatch selection
        for (Button btn : getSwatchButtons()) {
            if (borderColor.equalsIgnoreCase(String.valueOf(btn.getUserData()))) {
                markSwatchSelected(btn);
                break;
            }
        }
    }

    // =========================================================
    // TOGGLE BUTTON STYLES
    // =========================================================

    private void applyToggleStyles() {
        String selected   = "-fx-font-size:10;-fx-background-color:#1f1b16;-fx-text-fill:white;"
                          + "-fx-border-radius:999;-fx-background-radius:999;"
                          + "-fx-padding:4 10;-fx-cursor:hand;";
        String unselected = "-fx-font-size:10;-fx-background-color:transparent;-fx-text-fill:#3d362c;"
                          + "-fx-border-color:#ddd2bc;-fx-border-radius:999;-fx-background-radius:999;"
                          + "-fx-padding:4 10;-fx-cursor:hand;";

        borderNone.setStyle(borderNone.isSelected()           ? selected : unselected);
        borderConstant.setStyle(borderConstant.isSelected()   ? selected : unselected);
        borderReflect.setStyle(borderReflect.isSelected()     ? selected : unselected);
        borderReplicate.setStyle(borderReplicate.isSelected() ? selected : unselected);
    }

    // =========================================================
    // SWATCH SELECTION STYLING
    // =========================================================

    private void markSwatchSelected(Button btn) {
        // Deselect all swatches
        for (Button b : getSwatchButtons()) {
            String bg = String.valueOf(b.getUserData());
            b.setStyle("-fx-background-color:" + bg + ";"
                     + "-fx-background-radius:999;"
                     + "-fx-border-color:#ddd2bc;"
                     + "-fx-border-width:1;"
                     + "-fx-border-radius:999;"
                     + "-fx-cursor:hand;"
                     + "-fx-min-width:28;-fx-min-height:28;"
                     + "-fx-max-width:28;-fx-max-height:28;");
        }
        // Mark the selected one
        String bg = String.valueOf(btn.getUserData());
        btn.setStyle("-fx-background-color:" + bg + ";"
                   + "-fx-background-radius:999;"
                   + "-fx-border-color:#1f1b16;"
                   + "-fx-border-width:2;"
                   + "-fx-border-radius:999;"
                   + "-fx-cursor:hand;"
                   + "-fx-min-width:28;-fx-min-height:28;"
                   + "-fx-max-width:28;-fx-max-height:28;");
        selectedSwatchBtn = btn;
    }

    /** Returns the list of swatch Button nodes inside the colorPickerSection HBox. */
    private List<Button> getSwatchButtons() {
        java.util.List<Button> result = new java.util.ArrayList<>();
        // colorPickerSection children: Label, HBox(swatches)
        if (colorPickerSection.getChildren().size() < 2) return result;
        javafx.scene.Node hboxNode = colorPickerSection.getChildren().get(1);
        if (!(hboxNode instanceof HBox)) return result;
        for (javafx.scene.Node n : ((HBox) hboxNode).getChildren()) {
            if (n instanceof Button) result.add((Button) n);
        }
        return result;
    }

    // =========================================================
    // UNDO STACK
    // =========================================================

    private void pushUndo() {
        if (undoStack.size() >= MAX_UNDO)
            undoStack.removeLast();
        undoStack.push(new AestheticState(borderWidth, borderType, borderColor));
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
        borderNone.setDisable(disabled);
        borderConstant.setDisable(disabled);
        borderReflect.setDisable(disabled);
        borderReplicate.setDisable(disabled);
        borderWidthSlider.setDisable(disabled);
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

    /**
     * Converts a "#RRGGBB" hex string to an OpenCV BGR Scalar.
     * Falls back to white on parse failure.
     */
    private static Scalar hexToScalar(String hex) {
        try {
            Color c = Color.web(hex);
            return new Scalar(c.getBlue() * 255, c.getGreen() * 255, c.getRed() * 255);
        } catch (Exception e) {
            return new Scalar(255, 255, 255);
        }
    }
}
