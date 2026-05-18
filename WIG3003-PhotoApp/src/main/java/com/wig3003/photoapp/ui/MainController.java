package com.wig3003.photoapp.ui;

import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.ResourceBundle;
import java.util.Set;

import com.wig3003.photoapp.model.MetadataStore;
import com.wig3003.photoapp.util.ImageUtils;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.util.Duration;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.TilePane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.StrokeType;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import org.kordamp.ikonli.javafx.FontIcon;

// CW: added imports for DipEdit navigation
import com.wig3003.photoapp.dip.DipEditController;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
// CW: change end

import com.wig3003.photoapp.social.ShareController;
import com.wig3003.photoapp.synthesis.MosaicController;
import com.wig3003.photoapp.synthesis.VideoController;


public class MainController implements Initializable {

    // ── FXML bindings ────────────────────────────────────────────────────────

    // Sidebar nav items
    @FXML private HBox navLibrary;
    @FXML private HBox navFavorites;
    @FXML private HBox navAnnotated;
    @FXML private HBox navEdit;
    @FXML private Label countLibrary;
    @FXML private Label countFavorites;
    @FXML private Label countAnnotated;

    // Library view
    @FXML private VBox libraryView;
    @FXML private TextField searchField;
    @FXML private ToggleButton filterAll;
    @FXML private ToggleButton filterAnnotated;
    @FXML private ToggleButton filterFavorites;
    @FXML private ToggleButton filterRecent;
    @FXML private Label selectedCountLabel;
    @FXML private ScrollPane gridScrollPane;
    @FXML private TilePane photoGrid;
    @FXML private VBox emptyState;
    @FXML private VBox emptyStateFiltered;
    @FXML private FontIcon emptyFilteredIcon;
    @FXML private Label emptyFilteredTitle;
    @FXML private Label emptyFilteredBody;

    // Detail view
    @FXML private VBox detailView;
    @FXML private Label detailFilename;
    @FXML private Label detailDimensions;
    @FXML private Button heartButton;
    @FXML private StackPane detailImageArea;
    @FXML private ImageView detailImageView;
    @FXML private javafx.scene.canvas.Canvas annotationCanvas;
    @FXML private javafx.scene.control.TextField annotationTextField;
    @FXML private javafx.scene.control.Slider fontSizeSlider;
    @FXML private Label fontSizeLabel;
    @FXML private javafx.scene.control.ColorPicker fontColorPicker;
    @FXML private Label annotationFeedbackLabel;
    @FXML private Button importBtn;

    private Image originalImage;
    private String userText = "";
    private double textX = -1;
    private double textY = -1;
    private boolean isDraggingText = false;
    private String previousFilter = "ALL";

    // ── State ─────────────────────────────────────────────────────────────────

    private static final String[] IMAGE_EXTS =
            { ".jpg", ".jpeg", ".png", ".bmp", ".gif", ".webp", ".tiff", ".tif", ".heic" };

    private final List<String>     allPaths         = new ArrayList<>();
    private final List<String>     displayPaths     = new ArrayList<>();
    private final Set<String>      favourites       = new HashSet<>();
    private final Set<Integer>     selectedIndices  = new HashSet<>();
    private final List<Rectangle>  selectionOverlays = new ArrayList<>();

    /** Currently selected index into displayPaths (-1 = none). */
    private int selectedIndex = -1;

    /** Absolute path of the image shown in the detail view (null when in library view). */
    private String currentPath;

    /** Which nav filter is active: ALL | FAVOURITES | ANNOTATED */
    private String activeFilter = "ALL";

    /** Pixel size of each thumbnail tile (square). Recalculated on resize. */
    private double thumbSize = 185;

    // CW: cached DipEdit module - load once, reuse on tab switch
    private Parent            dipEditRoot;
    private DipEditController dipEditController;

    // Mosaic view — injected via fx:include in main.fxml
    @FXML private Parent           mosaicView;
    @FXML private MosaicController mosaicViewController;
    @FXML private HBox             navMosaic;

    // Video view — injected via fx:include in main.fxml
    @FXML private Parent          videoView;
    @FXML private VideoController videoViewController;
    @FXML private HBox            navVideo;

    // Share view — injected via fx:include in main.fxml
    @FXML private Parent          shareView;
    @FXML private ShareController shareViewController;
    @FXML private HBox            navShare;

    // cached BorderPane root - stored once scene is available
    // Safe to use anytime unlike libraryView.getScene() which returns
    // null when libraryView is swapped out of the BorderPane center
    private BorderPane mainRoot;
    // CW: change end
    private void setupAnnotationDrag() {
        annotationCanvas.setOnMousePressed(e -> {
            if (userText == null || userText.isBlank()) return;
            double dist = Math.hypot(e.getX() - textX, e.getY() - textY);
            if (dist < 80) isDraggingText = true;
        });

        annotationCanvas.setOnMouseDragged(e -> {
            if (!isDraggingText) return;
            textX = e.getX();
            textY = e.getY();
            redrawCanvas();
        });

        annotationCanvas.setOnMouseReleased(e -> isDraggingText = false);
    }
    // ── Initialise ────────────────────────────────────────────────────────────

    @Override
    public void initialize(URL location, ResourceBundle resources) {

        // Bind tile width to scroll pane width so tiles stay 5-per-row
        gridScrollPane.widthProperty().addListener((gridObs, gridOld, gridW) -> {
            double available = gridW.doubleValue() - 32;
            thumbSize = Math.max(100, (available - 4 * 8) / 5);
            photoGrid.setPrefTileWidth(thumbSize);
        });

        // One-time layout listener to size the canvas
        detailImageArea.layoutBoundsProperty().addListener(
            new javafx.beans.value.ChangeListener<javafx.geometry.Bounds>() {
                @Override
                public void changed(
                        javafx.beans.value.ObservableValue<? extends javafx.geometry.Bounds> boundsObs,
                        javafx.geometry.Bounds oldBounds,
                        javafx.geometry.Bounds newBounds) {
                    if (newBounds.getWidth() > 0 && newBounds.getHeight() > 0) {
                        annotationCanvas.setWidth(newBounds.getWidth());
                        annotationCanvas.setHeight(newBounds.getHeight());
                        boundsObs.removeListener(this);
                    }
                }
            });

        // Bind detail ImageView size to its container
        detailImageView.fitWidthProperty().bind(
                detailImageArea.widthProperty().subtract(48));
        detailImageView.fitHeightProperty().bind(
                detailImageArea.heightProperty().subtract(48));

        // Cache the BorderPane root once the scene is attached
        libraryView.sceneProperty().addListener((sceneObs, oldScene, newScene) -> {
            if (newScene != null && mainRoot == null) {
                mainRoot = (BorderPane) newScene.getRoot();
                newScene.setOnKeyPressed(e -> handleKeyPress(e.getCode()));
            }
        });

        // Load saved library images on startup
        loadAppLibrary();

        shareViewController.setMainController(this);
        mosaicViewController.setMainController(this);

        // Set default color picker value
        fontColorPicker.setValue(javafx.scene.paint.Color.WHITE);

        // Live font size label update
        fontSizeSlider.valueProperty().addListener((sliderObs, sliderOld, sliderNew) -> {
            fontSizeLabel.setText((int) sliderNew.doubleValue() + " pt");
            if (userText != null && !userText.isBlank()) redrawCanvas();
        });

        // Live preview — text appears on image as you type
        annotationTextField.textProperty().addListener((txtObs, txtOld, txtNew) -> {
            userText = txtNew;
            redrawCanvas();
        });

        // Live preview — color change updates image immediately
        fontColorPicker.valueProperty().addListener((colorObs, colorOld, colorNew) -> {
            if (userText != null && !userText.isBlank()) redrawCanvas();
        });

        // Setup drag on canvas
        setupAnnotationDrag();

        // Import button context menu
        ContextMenu importMenu = new ContextMenu();
        MenuItem miFiles  = new MenuItem("Import files");
        MenuItem miFolder = new MenuItem("Import folder");
        miFiles.setOnAction(e -> handleImport());
        miFolder.setOnAction(e -> handleImportFolder());
        importMenu.getItems().addAll(miFiles, miFolder);
        importBtn.setOnMouseClicked(e ->
                importMenu.show(importBtn, javafx.geometry.Side.BOTTOM, 0, 0));
    }
    // ── Navigation ────────────────────────────────────────────────────────────

    @FXML
    private void handleNavLibrary() {
        setNavActive(navLibrary);
        activeFilter = "ALL";
        // CW: refresh app library images saved by Geometric / Extraction
        loadAppLibrary();
        // CW: change end
        showLibraryView();
    }

    @FXML
    private void handleNavFavorites() {
        setNavActive(navFavorites);
        activeFilter = "FAVOURITES";
        filterFavorites.setSelected(true);
        applyFilter();
        showLibraryView();
    }

    @FXML
    private void handleNavAnnotated() {
        setNavActive(navAnnotated);
        activeFilter = "ANNOTATED";
        filterAnnotated.setSelected(true);
        applyFilter();
        showLibraryView();
    }

    // Stub handlers for STUDIO items — other modules will wire these
    
    
// =========Chyntia: Edit begin
    // CW: loads images saved by Save to Library into the app Library page
    private void loadAppLibrary() {
        allPaths.clear();
        selectedIndex = -1;
        selectedIndices.clear();
        selectedCountLabel.setText("");

        allPaths.addAll(MetadataStore.getInstance().getLibraryPaths());

        applyFilter();
        updateCounts();
    }
    // CW: change end

    @FXML
    private void handleNavEdit() {
        navigateToDipEdit("Geometric");
    }

    @FXML
    private void handleNavExtract() {
        navigateToDipEdit("Extraction");
    }

    // CW: new method - load DipEdit once, swap BorderPane center
    private void navigateToDipEdit(String tabName) {
        // CW: use cached mainRoot - never call libraryView.getScene()
        // here because libraryView may already be detached from the scene
        if (mainRoot == null) return;
 
        try {
            if (dipEditRoot == null) {
                FXMLLoader loader = new FXMLLoader(
                        getClass().getResource(
                                "/com/wig3003/photoapp/fxml/DipEdit.fxml"));
                dipEditRoot       = loader.load();
                dipEditController = (DipEditController) loader.getController();
                dipEditController.setMainController(this);
            }
 
            String pathToPass = currentPath != null ? currentPath
                    : (selectedIndex >= 0 && selectedIndex < displayPaths.size()
                            ? displayPaths.get(selectedIndex) : null);
 
            if (pathToPass != null)
                dipEditController.setInitialImage(pathToPass);

            dipEditController.setLibraryPaths(new ArrayList<>(allPaths));
            dipEditController.selectTab(tabName);
            mainRoot.setCenter(dipEditRoot);
            setNavActive(navEdit);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    // CW: change end

    // CW: new method - restore library StackPane back into BorderPane center
    private void restoreLibraryCenter() {
        if (mainRoot == null) return;
        javafx.scene.Parent parent = libraryView.getParent();
        if (parent instanceof StackPane) {
            mainRoot.setCenter((StackPane) parent);
        } else {
            mainRoot.setCenter(parent != null ? parent : libraryView);
        }
    }
    // CW: change end

    
// =========Chyntia: Edit end

    @FXML
    private void handleNavMosaic() {
        // If DipEdit replaced the center, restore the StackPane first
        if (mainRoot != null && dipEditRoot != null
                && mainRoot.getCenter() == dipEditRoot) {
            restoreLibraryCenter();
        }
        libraryView.setVisible(false);
        libraryView.setManaged(false);
        detailView.setVisible(false);
        detailView.setManaged(false);
        videoView.setVisible(false);
        videoView.setManaged(false);
        shareView.setVisible(false);
        shareView.setManaged(false);
        mosaicView.setVisible(true);
        mosaicView.setManaged(true);
        mosaicViewController.setLibraryPaths(new ArrayList<>(allPaths));
        setNavActive(navMosaic);
    }

    @FXML
    private void handleNavVideo() {
        // If DipEdit replaced the center, restore the StackPane first
        if (mainRoot != null && dipEditRoot != null
                && mainRoot.getCenter() == dipEditRoot) {
            restoreLibraryCenter();
        }
        libraryView.setVisible(false);
        libraryView.setManaged(false);
        detailView.setVisible(false);
        detailView.setManaged(false);
        mosaicView.setVisible(false);
        mosaicView.setManaged(false);
        shareView.setVisible(false);
        shareView.setManaged(false);
        videoView.setVisible(true);
        videoView.setManaged(true);
        videoViewController.setLibraryPaths(new ArrayList<>(allPaths));
        setNavActive(navVideo);
    }
    @FXML
    public void handleNavShare() {
        if (mainRoot != null && dipEditRoot != null
                && mainRoot.getCenter() == dipEditRoot) {
            restoreLibraryCenter();
        }
        libraryView.setVisible(false);
        libraryView.setManaged(false);
        detailView.setVisible(false);
        detailView.setManaged(false);
        mosaicView.setVisible(false);
        mosaicView.setManaged(false);
        videoView.setVisible(false);
        videoView.setManaged(false);
        shareView.setVisible(true);
        shareView.setManaged(true);
        setNavActive(navShare);
    }

    private void setNavActive(HBox active) {
        for (HBox item : List.of(navLibrary, navFavorites, navAnnotated, navEdit, navMosaic, navVideo, navShare)) {
            item.getStyleClass().remove("nav-active");
        }
        active.getStyleClass().add("nav-active");
    }

    // ── Import / Directory chooser ────────────────────────────────────────────

    @FXML
    private void handleImport() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Select Images");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                "Image files", "*.png", "*.jpg", "*.jpeg", "*.bmp", "*.gif"));
        List<File> files = chooser.showOpenMultipleDialog(
                gridScrollPane.getScene().getWindow());
        if (files == null || files.isEmpty()) return;
        List<String> paths = new ArrayList<>();
        for (File f : files) paths.add(f.getAbsolutePath());
        addFilesToLibrary(paths);
    }

    @FXML
    private void handleImportFolder() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Select Photo Folder");
        File dir = chooser.showDialog(gridScrollPane.getScene().getWindow());
        if (dir == null) return;
        File[] files = dir.listFiles(f -> f.isFile() && isImageFile(f.getName()));
        if (files == null) return;
        Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        List<String> paths = new ArrayList<>();
        for (File f : files) paths.add(f.getAbsolutePath());
        addFilesToLibrary(paths);
    }

    @FXML
    private void handleBrowse() {
        handleImport();
    }

    private void addFilesToLibrary(List<String> newPaths) {
        for (String p : newPaths) {
            if (!allPaths.contains(p)) allPaths.add(p);
        }
        MetadataStore.getInstance().saveLibraryImagePaths(allPaths);
        applyFilter();
        updateCounts();
    }

    public void addToLibrary(String absolutePath) {
        if (absolutePath == null || absolutePath.isBlank()) return;
        if (!allPaths.contains(absolutePath)) {
            allPaths.add(absolutePath);
            MetadataStore.getInstance().saveLibraryImagePath(absolutePath);
            applyFilter();
            updateCounts();
        }
    }

    



    private boolean isImageFile(String name) {
        String lower = name.toLowerCase();
        for (String ext : IMAGE_EXTS) {
            if (lower.endsWith(ext)) return true;
        }
        return false;
    }

    // ── Filtering ─────────────────────────────────────────────────────────────

    @FXML
    private void handleFilter() {
        if (filterAll.isSelected())        activeFilter = "ALL";
        else if (filterAnnotated.isSelected()) activeFilter = "ANNOTATED";
        else if (filterFavorites.isSelected()) activeFilter = "FAVOURITES";
        else if (filterRecent.isSelected())    activeFilter = "RECENT";
        applyFilter();
    }

    private void applyFilter() {
        displayPaths.clear();
        selectedIndex = -1;
        selectedIndices.clear();
        selectedCountLabel.setText("");

        for (String p : allPaths) {
            switch (activeFilter) {
                case "FAVOURITES": if (favourites.contains(p))  displayPaths.add(p); break;
                case "ANNOTATED":  if (MetadataStore.getInstance().hasAnnotation(p)) displayPaths.add(p); break;
                case "RECENT":     displayPaths.add(p); break; // TODO: sort by mtime
                default:           displayPaths.add(p); break;
            }
        }

        refreshGrid();
        updateFilterLabels();
    }

    private void updateFilterLabels() {
        int annotated = MetadataStore.getInstance().annotationCount(allPaths);
        filterAll.setText("All · " + allPaths.size());
        filterFavorites.setText("Favorites · " + favourites.size());
        filterAnnotated.setText("Annotated · " + annotated);
    }

    private void updateCounts() {
        int annotated = MetadataStore.getInstance().annotationCount(allPaths);
        countLibrary.setText(String.valueOf(allPaths.size()));
        countFavorites.setText(String.valueOf(favourites.size()));
        countAnnotated.setText(String.valueOf(annotated));
        updateFilterLabels();
    }

    // ── Grid ──────────────────────────────────────────────────────────────────

    private void refreshGrid() {
        photoGrid.getChildren().clear();
        selectionOverlays.clear();

        if (displayPaths.isEmpty()) {
            showAppropriateEmptyState();
            return;
        }

        showEmptyState(false);
        for (int i = 0; i < displayPaths.size(); i++) {
            photoGrid.getChildren().add(createThumbnailCell(displayPaths.get(i), i));
        }
    }

    private void showAppropriateEmptyState() {
        if ("FAVOURITES".equals(activeFilter)) {
            showFilteredEmptyState("bi-heart", "No favourites yet",
                    "Nothing here yet. Mark images as favourites in the library to see them here.");
        } else if ("ANNOTATED".equals(activeFilter)) {
            showFilteredEmptyState("bi-pencil-square", "No annotated images yet",
                    "Nothing here yet. Open an image in the library and add an annotation to see it here.");
        } else {
            showEmptyState(true);
        }
    }

    private void showFilteredEmptyState(String icon, String title, String body) {
        emptyFilteredIcon.setIconLiteral(icon);
        emptyFilteredTitle.setText(title);
        emptyFilteredBody.setText(body);
        emptyState.setVisible(false);
        emptyState.setManaged(false);
        gridScrollPane.setVisible(false);
        gridScrollPane.setManaged(false);
        emptyStateFiltered.setVisible(true);
        emptyStateFiltered.setManaged(true);
    }

    private void showEmptyState(boolean empty) {
        emptyState.setVisible(empty);
        emptyState.setManaged(empty);
        gridScrollPane.setVisible(!empty);
        gridScrollPane.setManaged(!empty);
        emptyStateFiltered.setVisible(false);
        emptyStateFiltered.setManaged(false);
    }

    private Node createThumbnailCell(String path, int index) {
        StackPane cell = new StackPane();
        cell.getStyleClass().add("thumbnail-cell");
        cell.setPrefSize(thumbSize, thumbSize);
        cell.setMinSize(thumbSize, thumbSize);
        cell.setMaxSize(thumbSize, thumbSize);

        // Rounded clip
        Rectangle clip = new Rectangle(thumbSize, thumbSize);
        clip.setArcWidth(16);
        clip.setArcHeight(16);
        cell.setClip(clip);

        // Thumbnail image (background-loading, true = async)
        String uri = new File(path).toURI().toString();
        Image thumb = new Image(uri, thumbSize, thumbSize, false, true, true);
        ImageView iv = new ImageView(thumb);
        iv.setFitWidth(thumbSize);
        iv.setFitHeight(thumbSize);
        iv.setPreserveRatio(false);
        iv.setSmooth(true);
        cell.getChildren().add(iv);
        

        // Heart badge (shown when favourited)
        if (favourites.contains(path)) {
            cell.getChildren().add(buildHeartBadge());
        }

        // Annotation badge (shown when annotated)
        if (MetadataStore.getInstance().hasAnnotation(path)) {
            cell.getChildren().add(buildAnnotationBadge());
        }

        // Selection overlay — brown stroke drawn on top of the image
        Rectangle selectionOverlay = new Rectangle(thumbSize, thumbSize);
        selectionOverlay.setArcWidth(16);
        selectionOverlay.setArcHeight(16);
        selectionOverlay.setFill(Color.TRANSPARENT);
        selectionOverlay.setStroke(Color.TRANSPARENT);
        selectionOverlay.setStrokeWidth(3.5);
        selectionOverlay.setStrokeType(StrokeType.INSIDE);
        selectionOverlay.setMouseTransparent(true);
        cell.getChildren().add(selectionOverlay);
        selectionOverlays.add(selectionOverlay);

        // Click handlers
        cell.setOnMouseClicked(e -> {
            if (e.getButton() != MouseButton.PRIMARY) return;
            if (e.getClickCount() == 1) {
                selectImage(index, e.isShiftDown());
            } else if (e.getClickCount() == 2) {
                openDetail(index);
            }
        });

        // Right-click context menu
        String filename = Paths.get(path).getFileName().toString();
        ContextMenu ctxMenu = new ContextMenu();

        MenuItem exportItem = new MenuItem("Export to device");
        exportItem.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.setTitle("Export image");
            fc.setInitialFileName(filename);
            fc.getExtensionFilters().add(
                    new FileChooser.ExtensionFilter("Image files",
                            "*.png", "*.jpg", "*.jpeg", "*.bmp"));
            File dest = fc.showSaveDialog(cell.getScene().getWindow());
            if (dest != null) {
                try {
                    Files.copy(Paths.get(path), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    Alert info = new Alert(Alert.AlertType.INFORMATION);
                    info.setTitle("Export");
                    info.setHeaderText(null);
                    info.setContentText("Exported successfully.");
                    info.showAndWait();
                } catch (java.io.IOException ex) {
                    Alert err = new Alert(Alert.AlertType.ERROR);
                    err.setTitle("Export failed");
                    err.setHeaderText(null);
                    err.setContentText("Could not export: " + ex.getMessage());
                    err.showAndWait();
                }
            }
        });

        MenuItem deleteItem = new MenuItem("Remove from library");
        deleteItem.setStyle("-fx-text-fill: #B0432B;");
        deleteItem.setOnAction(e -> {
            selectImage(index);
            deleteSelectedImages();
        });

        ctxMenu.getItems().addAll(exportItem, new SeparatorMenuItem(), deleteItem);
        cell.setOnContextMenuRequested(e ->
                ctxMenu.show(cell, e.getScreenX(), e.getScreenY()));

        // Filename label below thumbnail
        Label nameLabel = new Label(filename);
        nameLabel.getStyleClass().add("thumbnail-name-label");
        nameLabel.setWrapText(true);
        nameLabel.setMaxWidth(thumbSize);
        nameLabel.setMaxHeight(34);
        nameLabel.setAlignment(Pos.CENTER);

        VBox wrapper = new VBox(cell, nameLabel);
        wrapper.setAlignment(Pos.TOP_CENTER);
        wrapper.setSpacing(0);
        wrapper.setPrefWidth(thumbSize);
        return wrapper;
    }

    private StackPane buildHeartBadge() {
        Label heart = new Label("♥");
        heart.getStyleClass().add("heart-label");

        StackPane badge = new StackPane(heart);
        badge.getStyleClass().add("heart-badge");
        StackPane.setAlignment(badge, Pos.TOP_RIGHT);
        StackPane.setMargin(badge, new Insets(8, 8, 0, 0));
        return badge;
    }

    private StackPane buildAnnotationBadge() {
        Label icon = new Label("✏");
        icon.getStyleClass().add("annotation-badge-label");

        StackPane badge = new StackPane(icon);
        badge.getStyleClass().add("annotation-badge");
        StackPane.setAlignment(badge, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(badge, new Insets(0, 8, 8, 0));
        return badge;
    }
   @FXML
    private void handleSaveAndBack() {
        if (currentPath == null) return;

        userText = annotationTextField.getText();

        try {
            if (userText == null || userText.isBlank()) {
                MetadataStore.getInstance().deleteAnnotation(currentPath);
            } else {
                String toSave = userText + "||" + textX + "||" + textY;
                MetadataStore.getInstance().saveAnnotation(currentPath, toSave);
            }
            updateCounts();
        } catch (Exception e) {
            e.printStackTrace();
        }

        // restore the filter we came from
        activeFilter = previousFilter;
        switch (previousFilter) {
            case "ANNOTATED":
                filterAnnotated.setSelected(true);
                setNavActive(navAnnotated);
                break;
            case "FAVOURITES":
                filterFavorites.setSelected(true);
                setNavActive(navFavorites);
                break;
            default:
                filterAll.setSelected(true);
                setNavActive(navLibrary);
                break;
        }

        applyFilter();
        Platform.runLater(() -> showLibraryView());
    }
    // ── Selection ─────────────────────────────────────────────────────────────

    private void selectImage(int index) {
        selectImage(index, false);
    }

    private void selectImage(int index, boolean shiftDown) {
        if (index < 0 || index >= displayPaths.size()) return;

        if (!shiftDown || selectedIndex == -1) {
            clearSelectionStyle();
            selectedIndices.add(index);
            selectedIndex = index;
            setOverlaySelected(index, true);
        } else {
            clearSelectionStyle();
            int start = Math.min(selectedIndex, index);
            int end = Math.max(selectedIndex, index);
            for (int i = start; i <= end; i++) {
                selectedIndices.add(i);
                setOverlaySelected(i, true);
            }
        }

        selectedCountLabel.setText(selectedIndices.size() + " SELECTED");
        scrollToCell(index);
    }

    private void clearSelectionStyle() {
        for (int i : selectedIndices) {
            setOverlaySelected(i, false);
        }
        selectedIndices.clear();
        selectedCountLabel.setText("");
    }

    private void setOverlaySelected(int index, boolean selected) {
        if (index >= 0 && index < selectionOverlays.size()) {
            selectionOverlays.get(index).setStroke(
                    selected ? Color.web("#8B5A2B") : Color.TRANSPARENT);
        }
    }

    private void scrollToCell(int index) {
        // Approximate scroll position based on row
        int cols = Math.max(1, (int) photoGrid.getPrefColumns());
        int row  = index / cols;
        int totalRows = (int) Math.ceil((double) displayPaths.size() / cols);
        if (totalRows > 1) {
            double vValue = (double) row / (totalRows - 1);
            gridScrollPane.setVvalue(vValue);
        }
        
    }

    // ── Detail view ───────────────────────────────────────────────────────────

    private void openDetail(int index) {
        if (index < 0 || index >= displayPaths.size()) return;
        previousFilter = activeFilter; // remember where we came from
        selectedIndex = index;
        currentPath = displayPaths.get(index);
        loadDetailImage(currentPath);
        loadAnnotationForImage(currentPath);
        showDetailView();
    }

    private void loadDetailImage(String path) {
        File file = new File(path);
        detailFilename.setText(file.getName());
        detailDimensions.setText(""); // updated once image loads

        String uri = file.toURI().toString();
        Image img = new Image(uri, 0, 0, true, true, true);

        img.progressProperty().addListener((obs, old, prog) -> {
            if (prog.doubleValue() >= 1.0 && !img.isError()) {
                Platform.runLater(() -> {
                    detailImageView.setImage(img);
                    originalImage = img;
                    int w = (int) img.getWidth();
                    int h = (int) img.getHeight();
                    detailDimensions.setText(String.format("%,d × %,d", w, h));
                    annotationCanvas.setWidth(detailImageArea.getWidth());
                    annotationCanvas.setHeight(detailImageArea.getHeight());
                    redrawCanvas();
                });
            }
        });

        // Update heart button state
        boolean fav = favourites.contains(path);
        heartButton.setText(fav ? "♥" : "♡");
        if (fav) {
            heartButton.getStyleClass().add("active");
        } else {
            heartButton.getStyleClass().remove("active");
        }
    }

    private void showDetailView() {
        libraryView.setVisible(false);
        libraryView.setManaged(false);
        mosaicView.setVisible(false);
        mosaicView.setManaged(false);
        videoView.setVisible(false);
        videoView.setManaged(false);
        shareView.setVisible(false);
        shareView.setManaged(false);
        detailView.setVisible(true);
        detailView.setManaged(true);
    }

    // CW: extended to restore BorderPane center when returning from DipEdit
    private void showLibraryView() {
        detailView.setVisible(false);
        detailView.setManaged(false);
        mosaicView.setVisible(false);
        mosaicView.setManaged(false);
        videoView.setVisible(false);
        videoView.setManaged(false);
        shareView.setVisible(false);
        shareView.setManaged(false);
        libraryView.setVisible(true);
        libraryView.setManaged(true);
 
        // CW: use mainRoot - safe even when libraryView is detached from scene
        if (mainRoot != null && dipEditRoot != null
                && mainRoot.getCenter() == dipEditRoot) {
            restoreLibraryCenter();
        }
        setNavActive(navLibrary);
    }
    // CW: change end
    


    // ── Favourites toggle ─────────────────────────────────────────────────────

    @FXML
    private void handleToggleFavourite() {
        if (selectedIndex < 0 || selectedIndex >= displayPaths.size()) return;
        String path = displayPaths.get(selectedIndex);

        if (favourites.contains(path)) {
            favourites.remove(path);
            heartButton.setText("♡");
            heartButton.getStyleClass().remove("active");
        } else {
            favourites.add(path);
            heartButton.setText("♥");
            if (!heartButton.getStyleClass().contains("active")) {
                heartButton.getStyleClass().add("active");
            }
        }
        updateCounts();
        // Refresh the thumbnail cell to show/hide the badge
        refreshGrid();
        if (selectedIndex >= 0 && selectedIndex < displayPaths.size()) {
            selectImage(selectedIndex);
        }
    }

    // ── Annotation ────────────────────────────────────────────────────────────

    private void loadAnnotationForImage(String path) {
        String saved = MetadataStore.getInstance().getAnnotation(path);
        if (saved != null && saved.contains("||")) {
            // parse "text||x||y"
            String[] parts = saved.split("\\|\\|");
            userText = parts[0];
            textX = parts.length > 1 ? Double.parseDouble(parts[1]) : -1;
            textY = parts.length > 2 ? Double.parseDouble(parts[2]) : -1;
        } else {
            userText = saved != null ? saved : "";
            textX = -1;
            textY = -1;
        }
        annotationTextField.setText(userText);
    }
    @FXML
    private void handleApplyAnnotation() {
        if (currentPath == null) return;
        userText = annotationTextField.getText();
        if (userText == null || userText.isBlank()) return;

        redrawCanvas();

        try {
            String toSave = userText + "||" + textX + "||" + textY;
            MetadataStore.getInstance().saveAnnotation(currentPath, toSave);
            annotationFeedbackLabel.setText("✓ Applied!");
            annotationFeedbackLabel.setStyle(
                "-fx-font-size: 12px; -fx-text-fill: #4A6741; -fx-font-style: italic;");
            PauseTransition pause = new PauseTransition(Duration.seconds(2.5));
            pause.setOnFinished(e -> annotationFeedbackLabel.setText(""));
            pause.play();
            updateCounts();
            refreshGrid();
        } catch (Exception e) {
            annotationFeedbackLabel.setText("✗ Failed to save");
        }
    }

    @FXML
    private void handleClearText() {
        if (currentPath == null) return;
        userText = "";
        textX = -1;
        textY = -1;
        annotationTextField.clear();
        redrawCanvas();

        try {
            MetadataStore.getInstance().deleteAnnotation(currentPath);
            annotationFeedbackLabel.setText("Annotation cleared.");
            annotationFeedbackLabel.setStyle(
                "-fx-font-size: 12px; -fx-text-fill: #9C907D; -fx-font-style: italic;");
            PauseTransition pause = new PauseTransition(Duration.seconds(2.5));
            pause.setOnFinished(e -> annotationFeedbackLabel.setText(""));
            pause.play();
            updateCounts();
            refreshGrid();
        } catch (Exception e) {
            annotationFeedbackLabel.setText("✗ Failed to clear");
        }
    }
    private void redrawCanvas() {
        if (annotationCanvas == null) return;
        if (annotationCanvas.getWidth() == 0 || annotationCanvas.getHeight() == 0) return;

        javafx.scene.canvas.GraphicsContext gc = annotationCanvas.getGraphicsContext2D();
        gc.clearRect(0, 0, annotationCanvas.getWidth(), annotationCanvas.getHeight());

        if (originalImage != null) {
            gc.drawImage(originalImage, 0, 0,
                    annotationCanvas.getWidth(), annotationCanvas.getHeight());
        }

        if (userText != null && !userText.isBlank()) {
            int fontSize = (int) fontSizeSlider.getValue();
            javafx.scene.paint.Color color = fontColorPicker.getValue();

            gc.setFont(javafx.scene.text.Font.font("Serif", fontSize));
            gc.setFill(color);

            // use saved drag position, or default bottom-center
            if (textX < 0 || textY < 0) {
                textX = (annotationCanvas.getWidth() / 2)
                        - (userText.length() * fontSize * 0.3);
                textY = annotationCanvas.getHeight() - 20;
            }

            gc.fillText(userText, textX, textY);
        }
    }
    // ── Stub handlers (wired by other modules) ────────────────────────────────

    @FXML private void handleNewMosaic() { /* Multimedia module */ }
    @FXML private void handleAnnotate()  { annotationTextField.requestFocus(); }
    @FXML
    private void handleShare() {
        handleNavShare();
        if (currentPath != null) {
            shareViewController.prefillAttachment(currentPath);
        }
    }
    @FXML private void handleSearch()    { /* search logic */ }

    // ── Delete selected images ────────────────────────────────────────────────

    private void deleteSelectedImages() {
        if (selectedIndices.isEmpty()) return;

        String contentText;
        if (selectedIndices.size() == 1) {
            int idx = selectedIndices.iterator().next();
            String filename = Paths.get(displayPaths.get(idx)).getFileName().toString();
            contentText = "Remove \"" + filename + "\" from the library?";
        } else {
            contentText = "Remove " + selectedIndices.size() + " images from the library?";
        }

        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Remove from library");
        alert.setHeaderText(null);
        alert.setContentText(contentText);
        ButtonType removeBtn = new ButtonType("Remove");
        alert.getButtonTypes().setAll(removeBtn, ButtonType.CANCEL);

        alert.showAndWait().ifPresent(result -> {
            if (result == removeBtn) {
                List<Integer> sorted = new ArrayList<>(selectedIndices);
                sorted.sort((a, b) -> b - a);
                for (int i : sorted) {
                    String path = displayPaths.get(i);
                    allPaths.remove(path);
                    MetadataStore.getInstance().deleteAnnotation(path);
                    favourites.remove(path);
                }
                MetadataStore.getInstance().saveLibraryImagePaths(allPaths);
                clearSelectionStyle();
                selectedIndex = -1;
                applyFilter();
                updateCounts();
            }
        });
    }

    // ── Keyboard navigation ───────────────────────────────────────────────────

    private void handleKeyPress(KeyCode code) {
        boolean inDetail = detailView.isVisible();

        if (inDetail) {
            switch (code) {
                case LEFT:  navigateDetail(-1); break;
                case RIGHT: navigateDetail(+1); break;
                case ESCAPE: showLibraryView(); break;
                default: break;
            }
        } else {
            int cols = Math.max(1, (int) photoGrid.getPrefColumns());
            switch (code) {
                case LEFT:  navigateGrid(-1);    break;
                case RIGHT: navigateGrid(+1);    break;
                case UP:    navigateGrid(-cols);  break;
                case DOWN:  navigateGrid(+cols);  break;
                case ENTER: if (selectedIndex >= 0) openDetail(selectedIndex); break;
                case ESCAPE: clearSelectionStyle(); selectedIndex = -1; break;
                case DELETE:
                case BACK_SPACE:
                    if (libraryView.isVisible() && !selectedIndices.isEmpty())
                        deleteSelectedImages();
                    break;
                default: break;
            }
        }
    }

    private void navigateGrid(int delta) {
        int next = selectedIndex + delta;
        if (next < 0) next = 0;
        if (next >= displayPaths.size()) next = displayPaths.size() - 1;
        selectImage(next);
    }

    private void navigateDetail(int delta) {
        int next = selectedIndex + delta;
        if (next < 0 || next >= displayPaths.size()) return;
        openDetail(next);
    }

    // CW: 
    // ── Application shutdown ──────────────────────────────────────────────────
 
    /**
     * Call this from the primary stage's setOnCloseRequest handler.
     * Shuts down the DipEdit module's background threads and releases
     * OpenCV Mat memory cleanly before the JVM exits.
     *
     * Example in MainApp.java:
     *   primaryStage.setOnCloseRequest(e -> mainController.onAppClose());
     */
    public void onAppClose() {
        if (dipEditController != null) {
            try {
                dipEditController.shutdown();
            } catch (Exception ignored) {}
        }
    }
    // CW: change end
}
