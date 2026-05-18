package com.wig3003.photoapp.synthesis;

import com.wig3003.photoapp.util.FavouritesManager;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.TilePane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class VideoController {

    // =========================================================
    // FXML — TOOLBAR
    // =========================================================

    @FXML private Label  videoTitle;
    @FXML private Label  timecodeLabel;
    @FXML private Button saveDraftBtn;
    @FXML private Button exportBtn;

    // =========================================================
    // FXML — CANVAS AREA
    // =========================================================

    @FXML private StackPane canvasArea;
    @FXML private ImageView previewView;
    @FXML private Label     placeholderLabel;
    @FXML private HBox      playbackBar;

    // =========================================================
    // FXML — PLAYBACK CONTROLS
    // =========================================================

    @FXML private Button prevBtn;
    @FXML private Button playPauseBtn;
    @FXML private Button nextBtn;
    @FXML private Label  playbackTimeLabel;
    @FXML private Slider seekSlider;

    // =========================================================
    // FXML — FILMSTRIP
    // =========================================================

    @FXML private Label stripInfoLabel;
    @FXML private HBox  filmstripBox;

    // =========================================================
    // FXML — RIGHT PANEL
    // =========================================================

    @FXML private Label        clipIndexLabel;
    @FXML private Label        durationValueLabel;
    @FXML private Slider       durationSlider;
    @FXML private ToggleButton transNone;
    @FXML private ToggleButton transFade;
    @FXML private ToggleButton transCross;
    @FXML private TextArea     overlayTextArea;
    @FXML private ToggleButton posTop;
    @FXML private ToggleButton posCenter;
    @FXML private ToggleButton posBottom;
    @FXML private Slider       textSizeSlider;
    @FXML private Label        textSizeLabel;
    @FXML private ToggleButton fps24;
    @FXML private ToggleButton fps30;
    @FXML private ToggleButton fps60;
    @FXML private Button       compileBtn;

    // =========================================================
    // STATE
    // =========================================================

    private List<String>             clipPaths         = new ArrayList<>();
    private List<String>             libraryPaths      = new ArrayList<>();
    private int                      selectedClipIndex = -1;
    private String                   lastVideoPath     = null;
    private Thread                   compileThread     = null;
    private Timeline                 previewTimeline   = null;
    private int                      previewIndex      = 0;
    private final Map<Integer,String> clipOverlays     = new HashMap<>();

    // =========================================================
    // INITIALIZE
    // =========================================================

    @FXML
    public void initialize() {
        // Keep previewView constrained to the content area (canvas minus 24 px padding each side).
        // Unconditional — ensures fit dimensions are always correct regardless of image state.
        canvasArea.widthProperty().addListener((obs, ov, nv) -> {
            double w = nv.doubleValue() - 48;
            if (w > 0) previewView.setFitWidth(w);
        });
        canvasArea.heightProperty().addListener((obs, ov, nv) -> {
            double h = nv.doubleValue() - 48;
            if (h > 0) previewView.setFitHeight(h);
        });

        durationSlider.valueProperty().addListener((obs, ov, nv) -> {
            durationValueLabel.setText(String.format("%.1f s", nv.doubleValue()));
            updateStripInfo();
        });

        textSizeSlider.valueProperty().addListener((obs, ov, nv) ->
                textSizeLabel.setText(nv.intValue() + " px"));

        loadClipsFromFavourites();
    }

    // =========================================================
    // LOAD CLIPS
    // =========================================================

    private void loadClipsFromFavourites() {
        List<String> paths;
        try {
            paths = FavouritesManager.getFavourites();
        } catch (IOException e) {
            paths = new ArrayList<>();
        }
        clipPaths = new ArrayList<>(paths);
        rebuildFilmstrip();
        updateStripInfo();
        if (!clipPaths.isEmpty()) {
            handleClipSelected(0);
        }
    }

    private void rebuildFilmstrip() {
        filmstripBox.getChildren().clear();
        for (int i = 0; i < clipPaths.size(); i++) {
            filmstripBox.getChildren().add(buildClipCard(i, clipPaths.get(i)));
        }
        filmstripBox.getChildren().add(buildAddCard());
    }

    private StackPane buildClipCard(int index, String path) {
        StackPane card = new StackPane();
        card.setMinSize(120, 80);
        card.setMaxSize(120, 80);
        card.setPrefSize(120, 80);

        boolean selected = (index == selectedClipIndex);
        card.setStyle(
                "-fx-background-color:#ECE4D3;"
                + "-fx-background-radius:6;"
                + "-fx-cursor:hand;"
                + (selected ? "-fx-border-color:#B0432B;-fx-border-width:2;-fx-border-radius:6;" : "")
        );

        // Thumbnail loaded in background
        Thread t = new Thread(() -> {
            String uri = new File(path).toURI().toString();
            Image img = new Image(uri, 120, 80, false, true);
            Platform.runLater(() -> {
                ImageView iv = new ImageView(img);
                iv.setFitWidth(120);
                iv.setFitHeight(80);
                iv.setPreserveRatio(false);
                iv.setSmooth(true);
                card.getChildren().add(0, iv);
            });
        });
        t.setDaemon(true);
        t.start();

        // Orange dot — visible when this clip has overlay text
        StackPane dot = new StackPane();
        dot.setMinSize(8, 8);
        dot.setMaxSize(8, 8);
        dot.setPrefSize(8, 8);
        dot.setStyle("-fx-background-color:#C8A93F; -fx-background-radius:999;");
        StackPane.setAlignment(dot, Pos.TOP_RIGHT);
        StackPane.setMargin(dot, new Insets(4, 4, 0, 0));
        boolean hasOverlay = !clipOverlays.getOrDefault(index, "").isEmpty();
        dot.setVisible(hasOverlay);
        dot.setManaged(hasOverlay);
        card.getChildren().add(dot);

        card.setOnMouseClicked(e -> handleClipSelected(index));
        return card;
    }

    private StackPane buildAddCard() {
        StackPane card = new StackPane();
        card.setMinSize(120, 80);
        card.setMaxSize(120, 80);
        card.setPrefSize(120, 80);
        card.setStyle(
                "-fx-background-color:transparent;"
                + "-fx-border-color:#DDD2BC;"
                + "-fx-border-width:1.5;"
                + "-fx-border-style:dashed;"
                + "-fx-border-radius:6;"
                + "-fx-cursor:hand;"
        );

        Label plus = new Label("+");
        plus.setStyle("-fx-font-size:24; -fx-text-fill:#9C907D;");
        card.getChildren().add(plus);
        card.setOnMouseClicked(e -> handleAddFromFavorites());
        return card;
    }

    private void updateStripInfo() {
        int n         = clipPaths.size();
        int totalSecs = (int) (n * durationSlider.getValue());
        int m         = totalSecs / 60;
        int s         = totalSecs % 60;
        stripInfoLabel.setText(String.format(
                "Strip · %d Clip%s · %d:%02d", n, n == 1 ? "" : "s", m, s));
    }

    // =========================================================
    // CLIP SELECTION
    // =========================================================

    private void handleClipSelected(int index) {
        // Persist overlay text for the previously selected clip
        if (selectedClipIndex >= 0 && overlayTextArea != null) {
            clipOverlays.put(selectedClipIndex, overlayTextArea.getText());
        }

        selectedClipIndex = index;
        clipIndexLabel.setText(String.format("CLIP · #%02d", index + 1));
        overlayTextArea.setText(clipOverlays.getOrDefault(index, ""));

        // Load preview in background
        if (index >= 0 && index < clipPaths.size()) {
            final String path = clipPaths.get(index);
            Thread t = new Thread(() -> {
                String uri = new File(path).toURI().toString();
                Image img  = new Image(uri, 0, 0, true, true);
                Platform.runLater(() -> {
                    previewView.setImage(img);
                    double fw = canvasArea.getWidth() - 48;
                    double fh = canvasArea.getHeight() - 48;
                    if (fw > 0) previewView.setFitWidth(fw);
                    if (fh > 0) previewView.setFitHeight(fh);
                    placeholderLabel.setVisible(false);
                    placeholderLabel.setManaged(false);
                });
            });
            t.setDaemon(true);
            t.start();
        }

        rebuildFilmstrip();
    }

    // =========================================================
    // ADD FROM FAVORITES
    // =========================================================

    private void handleAddFromFavorites() {
        Stage owner = getStage();
        if (owner == null) return;

        // Use full library if available, otherwise fall back to Favorites
        List<String> source;
        if (!libraryPaths.isEmpty()) {
            source = libraryPaths;
        } else {
            try {
                source = FavouritesManager.getFavourites();
            } catch (IOException e) {
                showError("Could not load library.", e.getMessage());
                return;
            }
        }

        List<String> available = source.stream()
                .filter(p -> !clipPaths.contains(p))
                .collect(Collectors.toList());

        if (available.isEmpty()) {
            showInfo("No photos available.", "All library photos are already in the strip.");
            return;
        }

        Set<String> selectedPaths = new HashSet<>();

        // ── Thumbnail grid ────────────────────────────────────────────
        TilePane tilePane = new TilePane();
        tilePane.setHgap(10);
        tilePane.setVgap(10);
        tilePane.setPrefColumns(5);
        tilePane.setPadding(new Insets(16));
        tilePane.setStyle("-fx-background-color:#FBF8F3;");

        for (String path : available) {
            final String BASE = "-fx-background-color:#ECE4D3;-fx-background-radius:8;-fx-cursor:hand;";
            final String SEL  = "-fx-background-color:#ECE4D3;-fx-background-radius:8;-fx-cursor:hand;"
                    + "-fx-border-color:#B0432B;-fx-border-width:2.5;-fx-border-radius:8;";

            StackPane card = new StackPane();
            card.setMinSize(140, 100);
            card.setMaxSize(140, 100);
            card.setPrefSize(140, 100);
            card.setStyle(BASE);

            // Thumbnail loaded in background
            Thread t = new Thread(() -> {
                String uri = new File(path).toURI().toString();
                Image img = new Image(uri, 140, 100, false, true);
                Platform.runLater(() -> {
                    ImageView iv = new ImageView(img);
                    iv.setFitWidth(140);
                    iv.setFitHeight(100);
                    iv.setPreserveRatio(false);
                    iv.setSmooth(true);
                    card.getChildren().add(0, iv);
                });
            });
            t.setDaemon(true);
            t.start();

            // Checkmark badge (hidden until selected)
            Label check = new Label("✓");
            check.setStyle("-fx-background-color:#B0432B;-fx-text-fill:white;"
                    + "-fx-background-radius:999;-fx-font-size:11;-fx-font-weight:bold;"
                    + "-fx-min-width:20;-fx-min-height:20;-fx-max-width:20;-fx-max-height:20;"
                    + "-fx-alignment:center;");
            StackPane.setAlignment(check, Pos.TOP_RIGHT);
            StackPane.setMargin(check, new Insets(6, 6, 0, 0));
            check.setVisible(false);
            card.getChildren().add(check);

            card.setOnMouseClicked(e -> {
                if (selectedPaths.contains(path)) {
                    selectedPaths.remove(path);
                    card.setStyle(BASE);
                    check.setVisible(false);
                } else {
                    selectedPaths.add(path);
                    card.setStyle(SEL);
                    check.setVisible(true);
                }
            });

            tilePane.getChildren().add(card);
        }

        ScrollPane scrollPane = new ScrollPane(tilePane);
        scrollPane.setFitToWidth(true);
        scrollPane.setStyle("-fx-background-color:#FBF8F3;-fx-background:#FBF8F3;"
                + "-fx-border-color:transparent;-fx-focus-color:transparent;"
                + "-fx-faint-focus-color:transparent;");

        // ── Header ───────────────────────────────────────────────────
        Label titleLbl = new Label("Choose Photos");
        titleLbl.setStyle("-fx-font-size:20;-fx-font-family:'Georgia',serif;-fx-text-fill:#1F1B16;");
        Label subtitleLbl = new Label("Click to select · " + available.size() + " available");
        subtitleLbl.setStyle("-fx-font-size:12;-fx-text-fill:#6B6051;");
        VBox header = new VBox(4, titleLbl, subtitleLbl);
        header.setPadding(new Insets(16, 20, 14, 20));
        header.setStyle("-fx-background-color:#FBF8F3;"
                + "-fx-border-color:#ECE4D3;-fx-border-width:0 0 1 0;");

        // ── Footer ───────────────────────────────────────────────────
        Button cancelBtn = new Button("Cancel");
        cancelBtn.setStyle("-fx-background-color:white;-fx-text-fill:#1F1B16;"
                + "-fx-border-color:#DDD2BC;-fx-border-radius:8;-fx-background-radius:8;"
                + "-fx-padding:8 16;-fx-cursor:hand;-fx-font-size:13;");

        Button addBtn = new Button("Add Selected");
        addBtn.setStyle("-fx-background-color:#1F1B16;-fx-text-fill:white;"
                + "-fx-background-radius:8;-fx-padding:8 16;-fx-cursor:hand;-fx-font-size:13;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox footer = new HBox(8, spacer, cancelBtn, addBtn);
        footer.setPadding(new Insets(12, 20, 14, 20));
        footer.setAlignment(Pos.CENTER_RIGHT);
        footer.setStyle("-fx-background-color:#FBF8F3;"
                + "-fx-border-color:#ECE4D3;-fx-border-width:1 0 0 0;");

        // ── Root layout ───────────────────────────────────────────────
        BorderPane root = new BorderPane();
        root.setTop(header);
        root.setCenter(scrollPane);
        root.setBottom(footer);
        root.setStyle("-fx-background-color:#FBF8F3;");

        // ── Dialog stage — slightly smaller than main window ──────────
        Stage dialog = new Stage();
        dialog.setTitle("Add Images — Vi-Flow");
        dialog.initModality(Modality.WINDOW_MODAL);
        dialog.initOwner(owner);
        dialog.setWidth(owner.getWidth() - 120);
        dialog.setHeight(owner.getHeight() - 80);
        dialog.setX(owner.getX() + 60);
        dialog.setY(owner.getY() + 40);

        cancelBtn.setOnAction(e -> dialog.close());

        addBtn.setOnAction(e -> {
            if (!selectedPaths.isEmpty()) {
                // Preserve the order from the available list
                for (String p : available) {
                    if (selectedPaths.contains(p)) {
                        clipPaths.add(p);
                    }
                }
                rebuildFilmstrip();
                updateStripInfo();
                handleClipSelected(clipPaths.size() - 1);
            }
            dialog.close();
        });

        dialog.setScene(new Scene(root));
        dialog.show();
    }

    // =========================================================
    // TOOLBAR / STRIP ACTIONS
    // =========================================================

    @FXML
    private void handleAddText() {
        overlayTextArea.requestFocus();
    }

    // =========================================================
    // PLAYBACK
    // =========================================================

    @FXML
    private void handlePrev() {
        if (clipPaths.isEmpty()) return;
        handleClipSelected(Math.max(0, selectedClipIndex - 1));
    }

    @FXML
    private void handleNext() {
        if (clipPaths.isEmpty()) return;
        handleClipSelected(Math.min(clipPaths.size() - 1, selectedClipIndex + 1));
    }

    @FXML
    private void handlePlayPause() {
        if (clipPaths.isEmpty()) return;

        if (previewTimeline != null
                && previewTimeline.getStatus() == Timeline.Status.RUNNING) {
            previewTimeline.pause();
            playPauseBtn.setText("▶");
            return;
        }

        previewIndex = selectedClipIndex < 0 ? 0 : selectedClipIndex;
        double secPerClip = durationSlider.getValue();

        previewTimeline = new Timeline(new KeyFrame(Duration.seconds(secPerClip), e -> {
            previewIndex = (previewIndex + 1) % clipPaths.size();
            String uri = new File(clipPaths.get(previewIndex)).toURI().toString();
            previewView.setImage(new Image(uri, true));
            double fw = canvasArea.getWidth() - 48;
            double fh = canvasArea.getHeight() - 48;
            if (fw > 0) previewView.setFitWidth(fw);
            if (fh > 0) previewView.setFitHeight(fh);
            placeholderLabel.setVisible(false);
            placeholderLabel.setManaged(false);
            clipIndexLabel.setText(String.format("CLIP · #%02d", previewIndex + 1));

            int totalSecs  = (int) (clipPaths.size() * secPerClip);
            int elapsedSec = (int) (previewIndex * secPerClip);
            playbackTimeLabel.setText(formatTime(elapsedSec) + " / " + formatTime(totalSecs));
            seekSlider.setValue((double) elapsedSec / Math.max(1, totalSecs));
        }));
        previewTimeline.setCycleCount(Timeline.INDEFINITE);
        previewTimeline.play();
        playPauseBtn.setText("⏸");
    }

    // =========================================================
    // COMPILE
    // =========================================================

    @FXML
    private void handleCompile() {
        if (clipPaths.isEmpty()) {
            showError("No clips.", "Add photos from Favorites to build your video.");
            return;
        }

        int    durationPerPhoto = (int) durationSlider.getValue();
        String overlayText      = overlayTextArea.getText();
        String outputDir        = "data/output";

        new File(outputDir).mkdirs();

        compileBtn.setDisable(true);
        saveDraftBtn.setText("Compiling…");
        saveDraftBtn.setVisible(true);
        saveDraftBtn.setManaged(true);

        String transitionType = "NONE";
        if (transFade.isSelected())       transitionType = "FADE";
        else if (transCross.isSelected()) transitionType = "CROSS";

        List<String> paths = new ArrayList<>(clipPaths);
        final String transition = transitionType;

        compileThread = new Thread(() -> {
            try {
                String resultPath = new VideoCompiler()
                        .compileVideo(paths, durationPerPhoto, overlayText, outputDir, transition);

                Platform.runLater(() -> {
                    lastVideoPath = resultPath;
                    timecodeLabel.setText(formatTime(paths.size() * durationPerPhoto));
                    resetCompileUI();
                    showInfo("Video compiled.", "Click Export to save the file.");
                });

            } catch (IllegalArgumentException | IOException e) {
                Platform.runLater(() -> {
                    showError("Compile failed.",
                            e.getMessage() != null ? e.getMessage()
                                                   : e.getClass().getSimpleName());
                    resetCompileUI();
                });
            }
        });
        compileThread.setDaemon(true);
        compileThread.setName("video-compile");
        compileThread.start();
    }

    private void resetCompileUI() {
        compileBtn.setDisable(false);
        saveDraftBtn.setText("Save draft");
        saveDraftBtn.setVisible(false);
        saveDraftBtn.setManaged(false);
    }

    // =========================================================
    // SAVE DRAFT / EXPORT
    // =========================================================

    @FXML
    private void handleSaveDraft() {
        showInfo("Draft saved.", "Your clip list has been noted.");
    }

    @FXML
    private void handleExport() {
        if (lastVideoPath == null) {
            showWarning("Nothing to export.", "Compile the video first.");
            return;
        }

        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export video");
        chooser.setInitialFileName("video_export.avi");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("AVI Video", "*.avi"));

        Stage stage = getStage();
        if (stage == null) return;

        File dest = chooser.showSaveDialog(stage);
        if (dest == null) return;

        try {
            Files.copy(Paths.get(lastVideoPath), dest.toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
            showInfo("Exported.", "Saved to: " + dest.getAbsolutePath());
            new MediaPlayerController().launchPlayer(dest.getAbsolutePath());
        } catch (IOException e) {
            showError("Export failed.", e.getMessage());
        }
    }

    // =========================================================
    // PUBLIC API
    // =========================================================

    public void setLibraryPaths(List<String> paths) {
        libraryPaths = paths != null ? new ArrayList<>(paths) : new ArrayList<>();
    }

    public void setClipPaths(List<String> paths) {
        clipPaths = paths != null ? new ArrayList<>(paths) : new ArrayList<>();
        clipOverlays.clear();
        selectedClipIndex = -1;
        rebuildFilmstrip();
        updateStripInfo();
        if (!clipPaths.isEmpty()) {
            handleClipSelected(0);
        }
    }

    // =========================================================
    // HELPERS
    // =========================================================

    private String formatTime(int totalSeconds) {
        return String.format("%d:%02d", totalSeconds / 60, totalSeconds % 60);
    }

    private Stage getStage() {
        if (compileBtn == null || compileBtn.getScene() == null) return null;
        return (Stage) compileBtn.getScene().getWindow();
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
