package com.wig3003.photoapp.social;

import jakarta.mail.*;
import jakarta.mail.internet.*;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;

import java.io.File;
import java.io.FileInputStream;
import java.util.Properties;

public class EmailSender {

    public static void launchComposeWindow(String attachmentPath, String attachmentType) {
        if (attachmentPath == null || attachmentPath.isBlank())
            throw new IllegalArgumentException("attachmentPath must not be null or blank");
        if (!attachmentType.equals("IMAGE") && !attachmentType.equals("VIDEO"))
            throw new IllegalArgumentException("attachmentType must be IMAGE or VIDEO");

        // Build UI
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20));

        TextField recipientField = new TextField();
        TextField subjectField   = new TextField();
        TextArea  bodyArea       = new TextArea();
        bodyArea.setPrefRowCount(5);
        Label attachmentLabel = new Label(attachmentPath);

        grid.add(new Label("To:"),         0, 0);
        grid.add(recipientField,           1, 0);
        grid.add(new Label("Subject:"),    0, 1);
        grid.add(subjectField,             1, 1);
        grid.add(new Label("Body:"),       0, 2);
        grid.add(bodyArea,                 1, 2);
        grid.add(new Label("Attachment:"), 0, 3);
        grid.add(attachmentLabel,          1, 3);

        Button sendBtn   = new Button("Send");
        Button cancelBtn = new Button("Cancel");

        // Disable Send until recipient + subject are filled
        sendBtn.disableProperty().bind(
            recipientField.textProperty().isEmpty()
                .or(subjectField.textProperty().isEmpty())
        );

        HBox buttons = new HBox(10, sendBtn, cancelBtn);
        grid.add(buttons, 1, 4);

        Stage stage = new Stage();
        stage.setTitle("Share via Email");
        stage.setScene(new Scene(grid, 500, 350));
        stage.show();

        // Inline validation on recipient field
        recipientField.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
            if (!isFocused && !recipientField.getText().contains("@")) {
                recipientField.setStyle("-fx-border-color: red;");
            } else {
                recipientField.setStyle("");
            }
        });

        cancelBtn.setOnAction(e -> stage.close());

        sendBtn.setOnAction(e -> {
            String recipient = recipientField.getText();
            String subject   = subjectField.getText();
            String body      = bodyArea.getText();

            // Run SMTP on background thread
            new Thread(() -> {
                try {
                    sendEmail(recipient, subject, body, attachmentPath);
                    Platform.runLater(() -> {
                        showInfo("Email sent successfully!");
                        stage.close();
                    });
                } catch (Exception ex) {
                    Platform.runLater(() ->
                        showError("Failed to send email: " + ex.getMessage()));
                }
            }).start();
        });
    }

    private static void sendEmail(String recipient, String subject,
                                   String body, String attachmentPath) throws Exception {
        // Load credentials from data/email.properties
        Properties credentials = new Properties();
        try (FileInputStream fis = new FileInputStream("data/email.properties")) {
            credentials.load(fis);
        } catch (Exception e) {
            throw new Exception(
                "Could not load data/email.properties. " +
                "Please create it with mail.user and mail.password entries.");
        }

        String user = credentials.getProperty("mail.user");
        String pass = credentials.getProperty("mail.password");

        if (user == null || pass == null)
            throw new Exception("mail.user or mail.password missing in email.properties");

        // SMTP config
        Properties smtpProps = new Properties();
        smtpProps.put("mail.smtp.host",            "smtp.gmail.com");
        smtpProps.put("mail.smtp.port",            "587");
        smtpProps.put("mail.smtp.auth",            "true");
        smtpProps.put("mail.smtp.starttls.enable", "true");

        Session session = Session.getInstance(smtpProps, new Authenticator() {
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(user, pass);
            }
        });

        // Build message
        MimeMessage msg = new MimeMessage(session);
        msg.setFrom(new InternetAddress(user));
        msg.setRecipient(Message.RecipientType.TO, new InternetAddress(recipient));
        msg.setSubject(subject);

        MimeBodyPart textPart = new MimeBodyPart();
        textPart.setText(body);

        MimeBodyPart filePart = new MimeBodyPart();
        filePart.attachFile(new File(attachmentPath));

        Multipart multipart = new MimeMultipart();
        multipart.addBodyPart(textPart);
        multipart.addBodyPart(filePart);
        msg.setContent(multipart);

        Transport.send(msg);
    }

    private static void showInfo(String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION, message, ButtonType.OK);
        alert.setHeaderText(null);
        alert.showAndWait();
    }

    private static void showError(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR, message, ButtonType.OK);
        alert.setHeaderText(null);
        alert.showAndWait();
    }
}