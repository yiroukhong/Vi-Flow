package com.wig3003.photoapp.app;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import nu.pattern.OpenCV;

public class MainApp extends Application {

    @Override
    public void init()throws Exception {
        OpenCV.loadLocally();
         // Auto-create data/ subdirectories on first run
        java.nio.file.Files.createDirectories(java.nio.file.Paths.get("data/annotations"));
        java.nio.file.Files.createDirectories(java.nio.file.Paths.get("data/output"));
    }

    @Override
    public void start(Stage primaryStage) throws Exception {
        Parent root = FXMLLoader.load(
                getClass().getResource("/com/wig3003/photoapp/fxml/main.fxml"));

        Scene scene = new Scene(root, 1280, 800);
        scene.getStylesheets().add(
                getClass().getResource("/com/wig3003/photoapp/css/app.css")
                          .toExternalForm());

        primaryStage.setTitle("Vi-Flow");
        primaryStage.setMinWidth(900);
        primaryStage.setMinHeight(600);
        primaryStage.setScene(scene);
        primaryStage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}