package com.wig3003.photoapp.util;

import com.wig3003.photoapp.ui.MainController;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

public class SaveHelper {

    /**
     * Shows a "Save to Library / Save to Device / Cancel" dialog and copies
     * srcPath to the chosen destination.
     *
     * @return absolute path of the saved file, or null if cancelled / failed
     */
    public static String promptSaveDestination(
            String srcPath,
            String dialogTitle,
            String extensionDescription,
            String extensionGlob,
            String defaultFilename,
            MainController mainController,
            Window ownerWindow) {

        ButtonType btnLibrary = new ButtonType("Save to Library");
        ButtonType btnDevice  = new ButtonType("Save to Device");
        ButtonType btnCancel  = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);

        Alert alert = new Alert(Alert.AlertType.NONE, null, btnLibrary, btnDevice, btnCancel);
        alert.setTitle(dialogTitle);
        alert.setHeaderText("Where would you like to save?");
        if (ownerWindow != null) alert.initOwner(ownerWindow);

        Optional<ButtonType> result = alert.showAndWait();
        if (result.isEmpty() || result.get() == btnCancel) return null;

        if (result.get() == btnLibrary) {
            try {
                Files.createDirectories(Paths.get("data/output"));
                String base = defaultFilename;
                String ext  = extensionGlob.replace("*", "");
                if (base.contains(".")) base = base.substring(0, base.lastIndexOf('.'));
                String destPath = Paths.get("data/output",
                        base + "_" + System.currentTimeMillis() + ext)
                        .toAbsolutePath().toString();
                Files.copy(Paths.get(srcPath), Paths.get(destPath),
                        StandardCopyOption.REPLACE_EXISTING);
                if (mainController != null) mainController.addToLibrary(destPath);
                return destPath;
            } catch (IOException e) {
                showError("Failed to save to library", e.getMessage());
                return null;
            }
        } else {
            FileChooser chooser = new FileChooser();
            chooser.setTitle(dialogTitle);
            chooser.setInitialFileName(defaultFilename);
            chooser.getExtensionFilters().add(
                    new FileChooser.ExtensionFilter(extensionDescription, extensionGlob));
            File dest = chooser.showSaveDialog(ownerWindow);
            if (dest == null) return null;
            try {
                Files.copy(Paths.get(srcPath), dest.toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
                return dest.getAbsolutePath();
            } catch (IOException e) {
                showError("Failed to save", e.getMessage());
                return null;
            }
        }
    }

    private static void showError(String header, String body) {
        Alert err = new Alert(Alert.AlertType.ERROR);
        err.setHeaderText(header);
        err.setContentText(body);
        err.showAndWait();
    }
}
