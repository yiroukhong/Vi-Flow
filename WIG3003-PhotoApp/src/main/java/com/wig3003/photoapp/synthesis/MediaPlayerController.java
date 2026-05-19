package com.wig3003.photoapp.synthesis;

import com.wig3003.photoapp.social.EmailSender;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.io.File;

/**
 * Winnie — Multimedia Synthesis
 * Media Player — Contract §6
 * Launches AVI/XVID video in a non-blocking popup Stage (per report §3.2.3).
 * If JavaFX cannot play the file, falls back to the OS default player.
 */
public class MediaPlayerController {

    public void launchPlayer(String videoPath) {

        // 1. Validate input
        if (videoPath == null || videoPath.isBlank())
            throw new IllegalArgumentException("videoPath must not be null or empty");

        File f = new File(videoPath);
        if (!f.exists())
            throw new IllegalArgumentException("Video file not found: " + videoPath);

        // 2. Attempt JavaFX MediaPlayer (per report §3.2.3 — popup Stage)
        try {
            Media       media  = new Media(f.toURI().toString());
            MediaPlayer player = new MediaPlayer(media);

            // Check for immediate error before building UI
            player.setOnError(() -> {
                // JavaFX cannot play this file — fall back to OS default player
                player.dispose();
                openWithSystemPlayer(f);
            });

            // 3. MediaView fills the popup
            MediaView mediaView = new MediaView(player);
            mediaView.setPreserveRatio(true);

            StackPane mediaPane = new StackPane(mediaView);
            mediaPane.setStyle("-fx-background-color:black;");
            mediaView.fitWidthProperty().bind(mediaPane.widthProperty());
            mediaView.fitHeightProperty().bind(mediaPane.heightProperty());

            // 4. Playback controls
            Button playBtn  = new Button("▶ Play");
            Button pauseBtn = new Button("⏸ Pause");

            Slider seekSlider = new Slider(0, 1, 0);
            seekSlider.setPrefWidth(300);
            HBox.setHgrow(seekSlider, Priority.ALWAYS);

            Label timeLabel = new Label("0:00 / 0:00");
            timeLabel.setTextFill(Color.WHITE);
            timeLabel.setStyle("-fx-font-size:11; -fx-font-family:'Consolas',monospace;");

            // Share via Email button — Contract §7
            Button shareBtn = new Button("✉ Share");

            String btnStyle = "-fx-background-color:rgba(255,255,255,0.15);"
                    + "-fx-text-fill:white;"
                    + "-fx-background-radius:6;"
                    + "-fx-cursor:hand;"
                    + "-fx-padding:6 12;";
            playBtn.setStyle(btnStyle);
            pauseBtn.setStyle(btnStyle);
            shareBtn.setStyle(btnStyle);

            // 5. Wire play/pause
            playBtn.setOnAction(e  -> player.play());
            pauseBtn.setOnAction(e -> player.pause());

            // 6. Seek slider + time label (per report §3.2.3 — currentTimeProperty listener)
            player.currentTimeProperty().addListener((obs, old, now) -> {
                double dur = player.getTotalDuration() != null
                        ? player.getTotalDuration().toSeconds() : 0;
                if (dur > 0) seekSlider.setValue(now.toSeconds() / dur);
                timeLabel.setText(
                        formatTime(now) + " / " + formatTime(player.getTotalDuration()));
            });

            seekSlider.setOnMouseReleased(e -> {
                if (player.getTotalDuration() != null)
                    player.seek(player.getTotalDuration().multiply(seekSlider.getValue()));
            });

            // 7. Share button
            shareBtn.setOnAction(e ->
                    EmailSender.launchComposeWindow(videoPath, "VIDEO"));

            // 8. Controls layout — seek row above buttons row (per report §3.2.3)
            HBox seekRow = new HBox(8, seekSlider, timeLabel);
            seekRow.setAlignment(Pos.CENTER_LEFT);

            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            HBox buttonsRow = new HBox(8, playBtn, pauseBtn, spacer, shareBtn);
            buttonsRow.setAlignment(Pos.CENTER_LEFT);

            VBox controlsVBox = new VBox(4, seekRow, buttonsRow);
            controlsVBox.setStyle("-fx-background-color:#161210; -fx-padding:8 16 12 16;");

            // 9. Root — video centre, controls bottom (per report §3.2.3)
            BorderPane root = new BorderPane();
            root.setCenter(mediaPane);
            root.setBottom(controlsVBox);
            root.setStyle("-fx-background-color:black;");

            // 10. Auto-play when ready
            player.setOnReady(() -> player.play());

            // 11. Non-blocking popup Stage (per report §3.2.3)
            Stage popup = new Stage();
            popup.setScene(new Scene(root, 900, 560));
            popup.setTitle("Video Player — Vi-Flow");
            popup.show();

            player.statusProperty().addListener((obs, oldStatus, newStatus) -> {
                if (newStatus == MediaPlayer.Status.PLAYING)
                    playBtn.setText("▶ Play");
            });

            popup.setOnCloseRequest(e -> player.stop());

        } catch (Exception ex) {
            // JavaFX Media failed entirely — fall back to OS default player
            openWithSystemPlayer(f);
        }
    }

    /**
     * Falls back to OS default player (VLC / Windows Media Player).
     * Called when JavaFX MediaPlayer cannot handle the file.
     */
    private void openWithSystemPlayer(File f) {
        try {
            java.awt.Desktop.getDesktop().open(f);
        } catch (Exception e) {
            throw new RuntimeException(
                "Could not open video with system player.\n"
                + "Please open the file manually:\n" + f.getAbsolutePath(), e);
        }
    }

    /**
     * Formats a Duration into m:ss display string.
     */
    private String formatTime(Duration d) {
        if (d == null || d.isUnknown() || d.isIndefinite()) return "0:00";
        int minutes = (int) d.toMinutes();
        int seconds = (int) d.toSeconds() % 60;
        return String.format("%d:%02d", minutes, seconds);
    }
}