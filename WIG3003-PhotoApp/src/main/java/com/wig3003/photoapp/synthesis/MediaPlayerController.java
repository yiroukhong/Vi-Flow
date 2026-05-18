package com.wig3003.photoapp.synthesis;


import com.wig3003.photoapp.social.EmailSender;

import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
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

        // 3. Create MediaView and bind to player
        MediaView mediaView = new MediaView(player);
        mediaView.setFitWidth(800);
        mediaView.setPreserveRatio(true);

        // 4. Build playback controls
        Button playBtn   = new Button("Play");
        Button pauseBtn  = new Button("Pause");
        Slider seekSlider = new Slider(0, 1, 0);
        seekSlider.setPrefWidth(300); // ensure slider is usable
        Label  timeLabel = new Label("0:00");

        // Share via Email button — Contract §7
        Button shareBtn = new Button("Share via Email");

        // 5. Wire play/pause buttons
        playBtn.setOnAction(e  -> player.play());
        pauseBtn.setOnAction(e -> player.pause());

        // 6. Wire seek slider and time label
        player.currentTimeProperty().addListener((obs, old, now) -> {
            double dur = player.getTotalDuration().toSeconds();
            if (dur > 0) {
                seekSlider.setValue(now.toSeconds() / dur);
            }
            timeLabel.setText(formatTime(now));
        });

        seekSlider.setOnMouseReleased(e -> {
            Duration target = player.getTotalDuration()
                    .multiply(seekSlider.getValue());
            player.seek(target);
        });

        // 7. Wire Share button to Sam's EmailSender
        shareBtn.setOnAction(e -> {
            EmailSender.launchComposeWindow(videoPath, "VIDEO");
        });

        // 8. Assemble layout and open popup Stage
        HBox controlBox = new HBox(8, playBtn, pauseBtn, seekSlider, timeLabel, shareBtn);
        VBox layout     = new VBox(8, mediaView, controlBox);

        // Contract §6 — launch in a separate non-blocking popup window
        Stage popup = new Stage();
        popup.setScene(new Scene(layout));
        popup.setTitle("Video Player");
        popup.show(); // non-blocking
    }

    /**
     * Formats a Duration into m:ss display string.
     */
    private String formatTime(Duration elapsed) {
        int minutes = (int) elapsed.toMinutes();
        int seconds = (int) elapsed.toSeconds() % 60;
        return String.format("%d:%02d", minutes, seconds);
    }
}