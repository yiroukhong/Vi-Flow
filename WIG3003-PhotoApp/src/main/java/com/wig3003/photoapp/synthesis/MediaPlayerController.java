package com.wig3003.photoapp.synthesis;

//import com.wig3003.photoapp.social.EmailSender; 
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
        // 1. Requirement check: Validate input [cite: 76]
        if (videoPath == null || videoPath.isBlank()) {
            throw new IllegalArgumentException("videoPath must not be null or empty");
        }

        // 2. Create JavaFX Media and MediaPlayer [cite: 76]
        File f = new File(videoPath);
        Media media = new Media(f.toURI().toString());
        MediaPlayer player = new MediaPlayer(media);

        // 3. Create MediaView and bind to player [cite: 76]
        MediaView mediaView = new MediaView(player);
        mediaView.setFitWidth(800); 
        mediaView.setPreserveRatio(true);

        // 4. Build playback controls [cite: 76]
        Button playBtn = new Button("Play");
        Button pauseBtn = new Button("Pause");
        Slider seekSlider = new Slider(0, 1, 0);
        Label timeLabel = new Label("0:00");
        
        // Share via Email button requirement [cite: 77]
        Button shareBtn = new Button("Share via Email");

        // 5. Wire play/pause buttons [cite: 76]
        playBtn.setOnAction(e -> player.play());
        pauseBtn.setOnAction(e -> player.pause());

        // 6. Wire seek slider and time label [cite: 76]
        player.currentTimeProperty().addListener((obs, old, now) -> {
            double dur = player.getTotalDuration().toSeconds();
            if (dur > 0) {
                seekSlider.setValue(now.toSeconds() / dur);
            }
            timeLabel.setText(formatTime(now));
        });

        seekSlider.setOnMouseReleased(e -> {
            Duration target = player.getTotalDuration().multiply(seekSlider.getValue());
            player.seek(target);
        });

        // 7. Wire Share button to Sam's EmailSender [cite: 77]
        shareBtn.setOnAction(e -> {
            // EmailSender.launchComposeWindow(videoPath, "VIDEO");
        });

        // 8. Assemble layout and open popup Stage [cite: 76, 77]
        HBox controlBox = new HBox(8, playBtn, pauseBtn, seekSlider, timeLabel, shareBtn);
        VBox layout = new VBox(8, mediaView, controlBox);

        // Requirement: Launch in separate window [cite: 77]
        Stage popup = new Stage(); 
        popup.setScene(new Scene(layout));
        popup.setTitle("Video Player");
        popup.show();
    }

    /**
     * Helper to format Duration into m:ss
     */
    private String formatTime(Duration elapsed) {
        int minutes = (int) elapsed.toMinutes();
        int seconds = (int) elapsed.toSeconds() % 60;
        return String.format("%d:%02d", minutes, seconds);
    }
}