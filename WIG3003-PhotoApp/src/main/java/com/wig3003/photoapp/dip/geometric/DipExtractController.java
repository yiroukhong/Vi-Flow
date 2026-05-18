package com.wig3003.photoapp.dip.geometric;

import com.wig3003.photoapp.util.ImageUtils;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import org.opencv.core.Mat;
import org.opencv.imgcodecs.Imgcodecs;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

// CW: Connection to MetadataStore for library save/load
import com.wig3003.photoapp.model.MetadataStore;
import java.nio.file.Path;
import javafx.scene.control.ChoiceDialog;
import java.util.List;
import java.util.Optional;
// CW: change end

public class DipExtractController {

    @FXML private ImageView imageView;
    @FXML private ImageView resultView;
    @FXML private StackPane sourcePane;
    @FXML private StackPane resultPane;
    @FXML private VBox sourcePlaceholder;
    @FXML private VBox resultPlaceholder;
    @FXML private Slider toleranceSlider;
    @FXML private Label toleranceLabel;

    private static final long DEBOUNCE_MS = 70;
    private static final long RESIZE_DEBOUNCE_MS = 90;

    private Mat originalMat;
    private Mat lastResultMat;
    private int lastClickX = -1;
    private int lastClickY = -1;
    private File lastDirectory;
    private boolean firstVisit = true;
    private volatile boolean disposed = false;

    private final AtomicLong generation = new AtomicLong(0);

    private final ExecutorService renderExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "extract-render");
        t.setDaemon(true);
        return t;
    });

    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "extract-io");
        t.setDaemon(true);
        return t;
    });

    private final ScheduledExecutorService debounce = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "extract-debounce");
        t.setDaemon(true);
        return t;
    });

    private ScheduledFuture<?> pendingRender;
    private ScheduledFuture<?> pendingResize;

    @FXML
    public void initialize() {
        configureImagePane();
        configureToleranceSlider();
        configureSizing();
        toleranceLabel.setText(String.valueOf((int) toleranceSlider.getValue()));
    }

    private void configureSizing() {
        imageView.setPreserveRatio(true);
        resultView.setPreserveRatio(true);
        imageView.setSmooth(true);
        resultView.setSmooth(true);

        ChangeListener<Number> initialLayout = new ChangeListener<>() {
            @Override
            public void changed(javafx.beans.value.ObservableValue<? extends Number> obs,
                                Number oldVal,
                                Number newVal) {
                if (newVal.doubleValue() > 0) {
                    obs.removeListener(this);
                    Platform.runLater(DipExtractController.this::applyImageViewSizes);
                }
            }
        };

        sourcePane.widthProperty().addListener(initialLayout);

        sourcePane.widthProperty().addListener((obs, o, n) -> scheduleResize());
        sourcePane.heightProperty().addListener((obs, o, n) -> scheduleResize());
        resultPane.widthProperty().addListener((obs, o, n) -> scheduleResize());
        resultPane.heightProperty().addListener((obs, o, n) -> scheduleResize());
    }

    private void scheduleResize() {
        if (disposed) return;

        if (pendingResize != null && !pendingResize.isDone()) {
            pendingResize.cancel(false);
        }

        try {
            pendingResize = debounce.schedule(
                    () -> Platform.runLater(this::applyImageViewSizes),
                    RESIZE_DEBOUNCE_MS,
                    TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) {
            // App is shutting down.
        }
    }

    private void applyImageViewSizes() {
        if (disposed) return;

        double sw = sourcePane.getWidth();
        double sh = sourcePane.getHeight();
        double rw = resultPane.getWidth();
        double rh = resultPane.getHeight();

        if (sw > 0 && sh > 0) {
            imageView.setFitWidth(sw);
            imageView.setFitHeight(sh);
        }

        if (rw > 0 && rh > 0) {
            resultView.setFitWidth(rw);
            resultView.setFitHeight(rh);
        }
    }

    private void configureImagePane() {
        sourcePane.setOnMouseClicked(event -> {
            if (!imageLoaded()) return;

            double paneW = sourcePane.getWidth();
            double paneH = sourcePane.getHeight();
            if (paneW <= 0 || paneH <= 0) return;

            DisplayBounds bounds = getRenderedImageBounds(paneW, paneH);
            if (!bounds.contains(event.getX(), event.getY())) {
                return;
            }

            lastClickX = toImageX(event.getX(), bounds);
            lastClickY = toImageY(event.getY(), bounds);
            scheduleExtraction();
        });
    }

    private void configureToleranceSlider() {
        toleranceSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
            toleranceLabel.setText(String.valueOf(newVal.intValue()));
            if (imageLoaded() && lastClickX >= 0) {
                scheduleExtraction();
            }
        });
    }

    public void onTabSelected() {
        if (firstVisit) {
            firstVisit = false;
            try {
                debounce.schedule(
                        () -> Platform.runLater(this::showHelp),
                        300,
                        TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException ignored) {
                // App is shutting down.
            }
        }
    }

    public void onTabDeselected() {
        cancelPendingRender();
    }

    public void shutdown() {
        disposed = true;
        cancelPendingRender();

        if (pendingResize != null && !pendingResize.isDone()) {
            pendingResize.cancel(false);
        }

        renderExecutor.shutdownNow();
        ioExecutor.shutdownNow();
        debounce.shutdownNow();

        releaseMat(originalMat);
        releaseMat(lastResultMat);
        originalMat = null;
        lastResultMat = null;
    }

    @FXML
    private void loadImage() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Open Image");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter(
                        "Images", "*.png", "*.jpg", "*.jpeg", "*.bmp", "*.webp"));

        if (lastDirectory != null && lastDirectory.isDirectory()) {
            chooser.setInitialDirectory(lastDirectory);
        }

        File file = chooser.showOpenDialog(getWindow());
        if (file != null) {
            loadImageFromPath(file.getAbsolutePath());
        }
    }

    @FXML
    private void loadFromLibrary() {
        List<String> paths = MetadataStore.getInstance().getLibraryPaths();

        if (paths.isEmpty()) {
            showAlert("Your Library is empty. Import a folder or save an image to Library first.");
            return;
        }

        ChoiceDialog<String> dialog = new ChoiceDialog<>(paths.get(0), paths);
        dialog.setTitle("Load from Library");
        dialog.setHeaderText("Choose an image from Vi-Flow Library");
        dialog.setContentText("Image:");

        Optional<String> selected = dialog.showAndWait();
        selected.ifPresent(this::loadImageFromPath);
    }


    // CW: shared loader used by Load File and Load Library
    public void loadImageFromPath(String path) {
        if (path == null || path.isBlank() || disposed) return;

        try {
            ioExecutor.submit(() -> {
                Mat loaded = Imgcodecs.imread(path, Imgcodecs.IMREAD_COLOR);

                if (loaded == null || loaded.empty()) {
                    releaseMat(loaded);
                    Platform.runLater(() -> {
                        if (!disposed) showAlert("Failed to load image: " + path);
                    });
                    return;
                }

                Platform.runLater(() -> {
                    if (disposed) {
                        loaded.release();
                        return;
                    }
                    applyLoadedImage(loaded, path);
                });
            });
        } catch (RejectedExecutionException ignored) {
            // App is shutting down.
        }
    }
    // CW: change end

    @FXML
    private void saveToLibrary() {
        if (!hasResult()) {
            showAlert("No result to save yet.");
            return;
        }

        Path path = MetadataStore.getInstance()
                .createLibraryImagePath("extracted", ".png");

        writePng(lastResultMat.clone(), path.toFile());
    }

    @FXML
    private void saveBoth() {
        saveToDevice();
        saveToLibrary();
    }


    private void applyLoadedImage(Mat image, String path) {
        cancelPendingRender();
        generation.incrementAndGet();

        releaseMat(originalMat);
        releaseMat(lastResultMat);

        originalMat = image;
        lastResultMat = null;
        lastClickX = -1;
        lastClickY = -1;

        imageView.setImage(ImageUtils.matToWritableImage(originalMat));
        resultView.setImage(null);

        sourcePlaceholder.setVisible(false);
        sourcePlaceholder.setManaged(false);
        resultPlaceholder.setVisible(true);
        resultPlaceholder.setManaged(true);

        File parent = new File(path).getParentFile();
        if (parent != null && parent.isDirectory()) {
            lastDirectory = parent;
        }

        Platform.runLater(this::applyImageViewSizes);
    }

    private void scheduleExtraction() {
        cancelPendingRender();

        final long gen = generation.incrementAndGet();

        try {
            pendingRender = debounce.schedule(
                    () -> submitExtraction(gen),
                    DEBOUNCE_MS,
                    TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) {
            // App is shutting down.
        }
    }

    private void cancelPendingRender() {
        if (pendingRender != null && !pendingRender.isDone()) {
            pendingRender.cancel(false);
        }
        pendingRender = null;
    }

    private void submitExtraction(long gen) {
        if (!imageLoaded() || lastClickX < 0 || disposed) return;

        final Mat source = originalMat.clone();
        final int px = lastClickX;
        final int py = lastClickY;
        final int tolerance = (int) toleranceSlider.getValue();

        try {
            renderExecutor.submit(() -> {
                Mat mask = null;
                Mat result = null;
                Mat cropped = null;

                try {
                    mask = ObjectExtractor.selectObject(source, px, py, tolerance);
                    result = ObjectExtractor.buildTransparentResult(source, mask);
                    cropped = ObjectExtractor.cropToContent(result, mask);

                    if (generation.get() != gen || disposed) {
                        cropped.release();
                        return;
                    }

                    Mat finalCropped = cropped;
                    cropped = null;

                    Platform.runLater(() -> {
                        if (disposed) {
                            finalCropped.release();
                            return;
                        }

                        releaseMat(lastResultMat);
                        lastResultMat = finalCropped;
                        resultView.setImage(ImageUtils.matToWritableImage(finalCropped));
                        resultPlaceholder.setVisible(false);
                        resultPlaceholder.setManaged(false);
                    });

                } catch (Exception ex) {
                    Platform.runLater(() -> {
                        if (!disposed) showAlert("Extraction failed: " + ex.getMessage());
                    });
                } finally {
                    source.release();
                    releaseMat(mask);
                    releaseMat(result);
                    releaseMat(cropped);
                }
            });
        } catch (RejectedExecutionException ex) {
            source.release();
        }
    }

    private DisplayBounds getRenderedImageBounds(double paneW, double paneH) {
        double imageAspect = (double) originalMat.cols() / originalMat.rows();
        double paneAspect = paneW / paneH;

        double renderedW;
        double renderedH;

        if (imageAspect > paneAspect) {
            renderedW = paneW;
            renderedH = paneW / imageAspect;
        } else {
            renderedH = paneH;
            renderedW = paneH * imageAspect;
        }

        double offsetX = (paneW - renderedW) / 2.0;
        double offsetY = (paneH - renderedH) / 2.0;

        return new DisplayBounds(offsetX, offsetY, renderedW, renderedH);
    }

    private int toImageX(double paneX, DisplayBounds bounds) {
        int x = (int) Math.round((paneX - bounds.x) * originalMat.cols() / bounds.width);
        return clamp(x, 0, originalMat.cols() - 1);
    }

    private int toImageY(double paneY, DisplayBounds bounds) {
        int y = (int) Math.round((paneY - bounds.y) * originalMat.rows() / bounds.height);
        return clamp(y, 0, originalMat.rows() - 1);
    }

    @FXML
    private void saveToDevice() {
        if (!hasResult()) {
            showAlert("No result to save yet.");
            return;
        }

        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save Transparent PNG");
        chooser.setInitialFileName("extracted_" + System.currentTimeMillis() + ".png");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("PNG Image", "*.png"));

        if (lastDirectory != null && lastDirectory.isDirectory()) {
            chooser.setInitialDirectory(lastDirectory);
        }

        File file = chooser.showSaveDialog(getWindow());
        if (file == null) return;

        lastDirectory = file.getParentFile();
        writePng(lastResultMat.clone(), file);
    }

    

    private void writePng(Mat image, File destination) {
        try {
            ioExecutor.submit(() -> {
                boolean ok = Imgcodecs.imwrite(destination.getAbsolutePath(), image);
                image.release();

                Platform.runLater(() -> {
                    if (disposed) return;
                    if (ok) {
                        showAlert("Saved: " + destination.getName());
                    } else {
                        showAlert("Failed to save image.");
                    }
                });
            });
        } catch (RejectedExecutionException ex) {
            image.release();
        }
    }

    @FXML
    public void showHelp() {
        if (disposed) return;

        Alert dialog = new Alert(Alert.AlertType.NONE);
        dialog.setTitle("How to Extract an Object");
        dialog.setHeaderText(null);

        DialogPane pane = dialog.getDialogPane();
        pane.setStyle(
                "-fx-background-color:#1e1a16;" +
                "-fx-border-color:#3a3028;" +
                "-fx-border-width:1;" +
                "-fx-font-family:'Georgia',serif;");

        VBox content = new VBox(14);
        content.setStyle("-fx-padding:8 4 4 4;");

        Label title = new Label("Object Extraction");
        title.setStyle(
                "-fx-font-size:16;" +
                "-fx-font-weight:bold;" +
                "-fx-text-fill:#f0e6d8;" +
                "-fx-font-family:'Georgia',serif;");

        Label[] steps = {
                styledStep("1", "Load File",
                        "Open any JPG, PNG, BMP, or WebP image using Load File."),
                styledStep("2", "Click the object",
                        "Click anywhere on the object you want to extract.\n" +
                        "The app samples nearby pixels and selects connected\n" +
                        "pixels with visually similar color."),
                styledStep("3", "Adjust Tolerance",
                        "Drag the Tolerance slider left for stricter selection\n" +
                        "or right for looser selection. The result updates live."),
                styledStep("4", "Save",
                        "Save to Device exports the extracted object as a\n" +
                        "transparent PNG.")
        };

        Label tip = new Label(
                "Tip: Click a representative color inside the object,\n" +
                "not on a highlight, shadow, or edge.");
        tip.setStyle(
                "-fx-font-size:11;" +
                "-fx-text-fill:rgba(240,230,216,0.45);" +
                "-fx-font-family:'Georgia',serif;" +
                "-fx-font-style:italic;");

        content.getChildren().add(title);
        for (Label step : steps) {
            content.getChildren().add(step);
        }
        content.getChildren().add(tip);

        pane.setContent(content);

        ButtonType got = new ButtonType("Got it");
        pane.getButtonTypes().setAll(got);

        pane.lookupButton(got).setStyle(
                "-fx-background-color:#c86442;" +
                "-fx-text-fill:white;" +
                "-fx-font-size:12;" +
                "-fx-font-weight:bold;" +
                "-fx-padding:8 24;" +
                "-fx-background-radius:6;" +
                "-fx-cursor:hand;");

        dialog.showAndWait();
    }

    private Label styledStep(String num, String heading, String body) {
        Label label = new Label(num + ".  " + heading + "\n    " + body);
        label.setWrapText(true);
        label.setStyle(
                "-fx-font-size:12;" +
                "-fx-text-fill:rgba(240,230,216,0.85);" +
                "-fx-font-family:'Georgia',serif;" +
                "-fx-line-spacing:2;");
        return label;
    }

    private boolean imageLoaded() {
        return originalMat != null && !originalMat.empty();
    }

    private boolean hasResult() {
        return lastResultMat != null && !lastResultMat.empty();
    }

    private void releaseMat(Mat mat) {
        if (mat != null && !mat.empty()) {
            mat.release();
        }
    }

    private Window getWindow() {
        if (sourcePane == null || sourcePane.getScene() == null) return null;
        return sourcePane.getScene().getWindow();
    }

    private void showAlert(String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Vi-Flow");
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class DisplayBounds {
        final double x;
        final double y;
        final double width;
        final double height;

        DisplayBounds(double x, double y, double width, double height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }

        boolean contains(double px, double py) {
            return px >= x && px <= x + width && py >= y && py <= y + height;
        }
    }
}
