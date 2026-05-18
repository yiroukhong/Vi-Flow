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
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.io.File;

/**
 * Winnie — Multimedia Synthesis
 * Implementation of the JavaFX Media Player as per Contract §6.
 */
public class MediaPlayerController {

    public void launchPlayer(String videoPath) {

        // 1. Validate input
        if (videoPath == null || videoPath.isBlank()) {
            throw new IllegalArgumentException("videoPath must not be null or empty");
        }

        File f = new File(videoPath);
        if (!f.exists()) {
            throw new IllegalArgumentException("Video file not found: " + videoPath);
        }

        // 2. Create JavaFX Media and MediaPlayer
        Media       media  = new Media(f.toURI().toString());
        MediaPlayer player = new MediaPlayer(media);

        // 3. Create MediaView — fills available space
        MediaView mediaView = new MediaView(player);
        mediaView.setPreserveRatio(true);

        // StackPane holds the MediaView and expands to fill centre
        StackPane mediaPane = new StackPane(mediaView);
        mediaPane.setStyle("-fx-background-color:black;");
        mediaView.fitWidthProperty().bind(mediaPane.widthProperty());
        mediaView.fitHeightProperty().bind(mediaPane.heightProperty());

        // 4. Build playback controls
        Button playBtn   = new Button("▶ Play");
        Button pauseBtn  = new Button("⏸ Pause");
        Slider seekSlider = new Slider(0, 1, 0);
        seekSlider.setPrefWidth(300);
        HBox.setHgrow(seekSlider, Priority.ALWAYS);

        Label timeLabel  = new Label("0:00 / 0:00");
        timeLabel.setTextFill(Color.WHITE);
        timeLabel.setStyle("-fx-font-size:11; -fx-font-family:'Consolas',monospace;");

        // Share via Email button — Contract §7
        Button shareBtn = new Button("✉ Share");

        // Style buttons
        String btnStyle = "-fx-background-color:rgba(255,255,255,0.15);"
                + "-fx-text-fill:white;"
                + "-fx-background-radius:6;"
                + "-fx-cursor:hand;"
                + "-fx-padding:6 12;";
        playBtn.setStyle(btnStyle);
        pauseBtn.setStyle(btnStyle);
        shareBtn.setStyle(btnStyle);

        // 5. Wire play/pause buttons
        playBtn.setOnAction(e  -> player.play());
        pauseBtn.setOnAction(e -> player.pause());

        // 6. Wire seek slider and time label
        player.currentTimeProperty().addListener((obs, old, now) -> {
            double dur = player.getTotalDuration() != null
                    ? player.getTotalDuration().toSeconds() : 0;
            if (dur > 0) {
                seekSlider.setValue(now.toSeconds() / dur);
            }
            timeLabel.setText(formatTime(now) + " / " + formatTime(player.getTotalDuration()));
        });

        seekSlider.setOnMouseReleased(e -> {
            Duration target = player.getTotalDuration()
                    .multiply(seekSlider.getValue());
            player.seek(target);
        });

        // 7. Wire Share button to Sam's EmailSender — Contract §7
        shareBtn.setOnAction(e ->
                EmailSender.launchComposeWindow(videoPath, "VIDEO"));

        // 8. Assemble controls bar — sits at the BOTTOM of the layout
        //    so it never blocks the video content
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox controlBox = new HBox(8,
                playBtn, pauseBtn, seekSlider, timeLabel, spacer, shareBtn);
        controlBox.setAlignment(Pos.CENTER_LEFT);
        controlBox.setPadding(new Insets(10, 16, 10, 16));
        controlBox.setStyle("-fx-background-color:rgba(0,0,0,0.75);");

        // 9. Root layout — video fills centre, controls pinned to bottom
        BorderPane root = new BorderPane();
        root.setCenter(mediaPane);
        root.setBottom(controlBox);
        root.setStyle("-fx-background-color:black;");

        // 10. Auto-play when player is ready
        player.setOnReady(() -> player.play());

        // Contract §6 — launch in a separate non-blocking popup window
        Stage popup = new Stage();
        popup.setScene(new Scene(root, 900, 560));
        popup.setTitle("Video Player — Vi-Flow");
        popup.show();

        // Update play button text on state change
        player.statusProperty().addListener((obs, oldStatus, newStatus) -> {
            if (newStatus == MediaPlayer.Status.PLAYING) {
                playBtn.setText("▶ Play");
            }
        });

        // Stop player when window is closed
        popup.setOnCloseRequest(e -> player.stop());
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