package com.wig3003.photoapp.synthesis;

import com.wig3003.photoapp.ui.MainController;
import com.wig3003.photoapp.util.FavouritesManager;
import com.wig3003.photoapp.util.ImageUtils;
import com.wig3003.photoapp.util.SaveHelper;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.TilePane;
import javafx.scene.paint.Color;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import org.opencv.core.Mat;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MosaicController {

    // =========================================================
    // FXML — TOOLBAR
    // =========================================================

    @FXML private Label  mosaicTitle;
    @FXML private Label  progressLabel;
    @FXML private Button cancelBtn;
    @FXML private Button exportBtn;

    // =========================================================
    // FXML — CANVAS AREA
    // =========================================================

    @FXML private StackPane  mosaicCheckerPane;
    @FXML private ScrollPane mosaicScrollPane;
    @FXML private Canvas     checkerCanvas;
    @FXML private ImageView  mosaicView;
    @FXML private Label      placeholderLabel;
    @FXML private Label      gridInfoLabel;

    // =========================================================
    // FXML — TARGET IMAGE
    // =========================================================

    @FXML private Label     targetImageLabel;
    @FXML private ImageView targetPreview;

    // =========================================================
    // FXML — RIGHT PANEL: TILE POOL
    // =========================================================

    @FXML private Label    tileCountLabel;
    @FXML private Label    tileSourceLabel;
    @FXML private TilePane tilePreviewPane;

    // =========================================================
    // FXML — RIGHT PANEL: GRID
    // =========================================================

    @FXML private Slider tileSizeSlider;
    @FXML private Label  tileSizeLabel;

    // =========================================================
    // FXML — RIGHT PANEL: GENERATE
    // =========================================================

    @FXML private Button generateBtn;

    // =========================================================
    // STATE
    // =========================================================

    private MainController mainController = null;

    private List<String> tilePaths        = new ArrayList<>();
    private List<String> libraryPaths     = new ArrayList<>();
    private String       targetPath       = null;
    private String       lastMosaicPath   = null;
    private Thread       generationThread = null;

    private final ExecutorService thumbnailExecutor =
            Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "mosaic-thumb");
                t.setDaemon(true);
                return t;
            });

    // =========================================================
    // INITIALIZE
    // =========================================================

    @FXML
    public void initialize() {
        tileSizeSlider.valueProperty().addListener((obs, ov, nv) ->
                tileSizeLabel.setText(nv.intValue() + " px"));

        checkerCanvas.widthProperty().bind(mosaicCheckerPane.widthProperty());
        checkerCanvas.heightProperty().bind(mosaicCheckerPane.heightProperty());
        checkerCanvas.widthProperty().addListener( (o, ov, nv) -> drawChecker());
        checkerCanvas.heightProperty().addListener((o, ov, nv) -> drawChecker());

        loadTilePoolFromFavourites();
    }

    // =========================================================
    // PUBLIC API
    // =========================================================

    public void setLibraryPaths(List<String> paths) {
        this.libraryPaths = paths != null ? new ArrayList<>(paths) : new ArrayList<>();
    }

    public void setMainController(MainController mc) {
        this.mainController = mc;
    }

    // =========================================================
    // TARGET IMAGE PICKER
    // =========================================================

    @FXML
    private void handleSelectTarget() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Select target image");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Image Files",
                        "*.jpg", "*.jpeg", "*.png", "*.bmp", "*.webp", "*.tiff", "*.tif"));

        Stage stage = getStage();
        if (stage == null) return;

        File file = chooser.showOpenDialog(stage);
        if (file == null) return;

        targetPath = file.getAbsolutePath();
        targetImageLabel.setText(file.getName());

        Image preview = new Image(file.toURI().toString(), 200, 200, true, true);
        targetPreview.setImage(preview);
        targetPreview.setVisible(true);
        targetPreview.setManaged(true);
    }

    // =========================================================
    // TILE POOL LOADING
    // =========================================================

    private void loadTilePoolFromFavourites() {
        List<String> paths;
        try {
            paths = FavouritesManager.getFavourites();
        } catch (IOException e) {
            paths = new ArrayList<>();
        }
        applyTilePool(paths, "Favorites");
    }

    private void loadTilePoolFromLibrary() {
        applyTilePool(new ArrayList<>(libraryPaths), "All Library");
    }

    private void loadTilePoolFromDirectory(File dir) {
        List<String> paths = new ArrayList<>();
        File[] files = dir.listFiles(f -> f.isFile() && isImageFile(f.getName()));
        if (files != null) {
            for (File f : files) paths.add(f.getAbsolutePath());
        }
        applyTilePool(paths, dir.getName());
    }

    private void applyTilePool(List<String> paths, String sourceName) {
        tilePaths = paths;
        tileCountLabel.setText(paths.size() + " photo" + (paths.size() == 1 ? "" : "s"));
        tileSourceLabel.setText(sourceName);
        populateTilePreview(paths);
    }

    private void populateTilePreview(List<String> paths) {
        tilePreviewPane.getChildren().clear();
        int max = Math.min(21, paths.size());
        for (int i = 0; i < max; i++) {
            final String path = paths.get(i);
            StackPane cell = makeThumbnailCell();
            tilePreviewPane.getChildren().add(cell);

            thumbnailExecutor.submit(() -> {
                String uri = new File(path).toURI().toString();
                Image img = new Image(uri, 28, 28, false, true);
                Platform.runLater(() -> {
                    ImageView iv = new ImageView(img);
                    iv.setFitWidth(28);
                    iv.setFitHeight(28);
                    iv.setPreserveRatio(false);
                    iv.setSmooth(true);
                    cell.getChildren().add(iv);
                });
            });
        }
    }

    private StackPane makeThumbnailCell() {
        StackPane cell = new StackPane();
        cell.setMinSize(28, 28);
        cell.setMaxSize(28, 28);
        cell.setPrefSize(28, 28);
        cell.setStyle("-fx-background-color:#ECE4D3; -fx-background-radius:4;");
        return cell;
    }

    // =========================================================
    // CHANGE TILE SOURCE
    // =========================================================

    @FXML
    private void handleChangeSource() {
        ButtonType favBtn    = new ButtonType("Favorites");
        ButtonType libBtn    = new ButtonType("All Library");
        ButtonType folderBtn = new ButtonType("Browse folder…");
        ButtonType cancelBt  = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);

        Alert dialog = new Alert(Alert.AlertType.NONE,
                "Choose the source for tile images:",
                favBtn, libBtn, folderBtn, cancelBt);
        dialog.setTitle("Change tile source");
        dialog.setHeaderText(null);

        dialog.showAndWait().ifPresent(result -> {
            if (result == favBtn) {
                loadTilePoolFromFavourites();
            } else if (result == libBtn) {
                loadTilePoolFromLibrary();
            } else if (result == folderBtn) {
                DirectoryChooser dc = new DirectoryChooser();
                dc.setTitle("Select tile folder");
                Stage stage = getStage();
                if (stage == null) return;
                File dir = dc.showDialog(stage);
                if (dir != null) loadTilePoolFromDirectory(dir);
            }
        });
    }

    // =========================================================
    // GENERATE
    // =========================================================

    @FXML
    private void handleGenerate() {
        if (targetPath == null || targetPath.isBlank()) {
            showError("No target image.", "Please select a target image first.");
            return;
        }

        if (tilePaths.isEmpty()) {
            showError("No tile images.", "Add images to your Favorites or choose a tile source.");
            return;
        }

        final int          tileSize       = Math.max(10, (int) tileSizeSlider.getValue());
        final List<String> capturedTiles  = new ArrayList<>(tilePaths);
        final String       capturedTarget = targetPath;

        progressLabel.setText("Generating…");
        progressLabel.setVisible(true);
        progressLabel.setManaged(true);
        cancelBtn.setVisible(true);
        cancelBtn.setManaged(true);
        generateBtn.setDisable(true);

        generationThread = new Thread(() -> {
            try {
                // Contract §6 — correct 3-argument signature
                String resultPath = new MosaicGenerator()
                        .generateMosaic(capturedTiles, capturedTarget, tileSize);

                if (Thread.interrupted()) return;

                final Mat    resultMat = ImageUtils.loadMatFromPath(resultPath);
                final String finalPath = resultPath;

                Platform.runLater(() -> {
                    WritableImage wi = ImageUtils.matToWritableImage(resultMat);
                    resultMat.release();
                    lastMosaicPath = finalPath;
                    mosaicView.setImage(wi);
                    placeholderLabel.setVisible(false);
                    gridInfoLabel.setText(capturedTiles.size() + " tiles · " + tileSize + "px");
                    resetGenerationUI();
                });

            } catch (Exception e) {
                if (Thread.currentThread().isInterrupted()
                        || e instanceof java.io.InterruptedIOException) {
                    Platform.runLater(this::resetGenerationUI);
                } else {
                    Platform.runLater(() -> {
                        showError("Generation failed.", e.getMessage() != null
                                ? e.getMessage() : e.getClass().getSimpleName());
                        resetGenerationUI();
                    });
                }
            }
        });
        generationThread.setDaemon(true);
        generationThread.setName("mosaic-generate");
        generationThread.start();
    }

    private void resetGenerationUI() {
        progressLabel.setVisible(false);
        progressLabel.setManaged(false);
        cancelBtn.setVisible(false);
        cancelBtn.setManaged(false);
        generateBtn.setDisable(false);
    }

    // =========================================================
    // CANCEL
    // =========================================================

    @FXML
    private void handleCancel() {
        if (generationThread != null && generationThread.isAlive()) {
            generationThread.interrupt();
        }
        resetGenerationUI();
        progressLabel.setText("");
    }

    // =========================================================
    // EXPORT
    // =========================================================

    @FXML
    private void handleExport() {
        if (lastMosaicPath == null) {
            showWarning("No mosaic to export.", "Generate a mosaic first.");
            return;
        }
        Stage stage = getStage();
        if (stage == null) return;
        String saved = SaveHelper.promptSaveDestination(
                lastMosaicPath, "Export Mosaic", "PNG Image", "*.png",
                "mosaic_export.png", mainController, stage);
        if (saved != null) {
            showInfo("Exported successfully.", "Saved to: " + saved);
        }
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

        int   tile  = 20;
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
    // HELPERS
    // =========================================================

    private boolean isImageFile(String name) {
        String lower = name.toLowerCase();
        return lower.endsWith(".jpg")  || lower.endsWith(".jpeg")
            || lower.endsWith(".png")  || lower.endsWith(".bmp")
            || lower.endsWith(".webp") || lower.endsWith(".tiff")
            || lower.endsWith(".tif");
    }

    private Stage getStage() {
        if (generateBtn == null || generateBtn.getScene() == null) return null;
        return (Stage) generateBtn.getScene().getWindow();
    }

    private void showError(String header, String body) {
        Alert a = new Alert(Alert.AlertType.ERROR);
        a.setHeaderText(header);
        a.setContentText(body);
        a.showAndWait();
    }

    private void showWarning(String header, String body) {
        Alert a = new Alert(Alert.AlertType.WARNING);
        a.setHeaderText(header);
        a.setContentText(body);
        a.showAndWait();
    }

    private void showInfo(String header, String body) {
        Alert a = new Alert(Alert.AlertType.INFORMATION);
        a.setHeaderText(header);
        a.setContentText(body);
        a.showAndWait();
    }
}