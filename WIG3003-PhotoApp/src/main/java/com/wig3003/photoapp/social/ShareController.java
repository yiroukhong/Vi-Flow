package com.wig3003.photoapp.social;

import jakarta.mail.*;
import jakarta.mail.internet.*;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.util.Duration;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;

import com.wig3003.photoapp.ui.MainController;

public class ShareController {

    // ── FXML bindings ────────────────────────────────────────────────────────

    @FXML private Button    sendBtn;
    @FXML private Button    cancelBtn;

    @FXML private VBox      errorBanner;
    @FXML private Label     errorTitle;
    @FXML private Label     errorBody;
    @FXML private Button    retrySendBtn;
    @FXML private Button    sendWithoutLargeBtn;

    @FXML private TextField fromField;

    @FXML private VBox      recipientTagBox;
    @FXML private TextField recipientInput;

    @FXML private TextField subjectField;
    @FXML private TextArea  bodyArea;

    @FXML private VBox      attachmentsSection;
    @FXML private Label     attachmentsHeaderLabel;
    @FXML private Label     attachmentsSummaryLabel;
    @FXML private VBox      attachmentListBox;

    @FXML private HBox      successToast;
    @FXML private Label     toastLabel;
    @FXML private Label     uploadStatusLabel;

    // ── State ─────────────────────────────────────────────────────────────────

    private final List<String> recipientEmails = new ArrayList<>();
    private final List<File>   attachedFiles   = new ArrayList<>();
    private static final long  MAX_SIZE_BYTES  = 25L * 1024 * 1024;

    private MainController mainController;

    // ── Public API ────────────────────────────────────────────────────────────

    public void setMainController(MainController mc) {
        this.mainController = mc;
    }

    public void prefillAttachment(String filePath) {
        if (filePath == null || filePath.isBlank()) return;
        File file = new File(filePath);
        if (!file.exists()) return;
        if (attachedFiles.stream().anyMatch(
                f -> f.getAbsolutePath().equals(file.getAbsolutePath()))) return;
        attachedFiles.add(file);
        attachmentListBox.getChildren().add(buildAttachmentRow(file));
        updateAttachmentHeader();
        if (mainController != null) {
            mainController.handleNavShare();
        }
    }

    // ── Initialise ────────────────────────────────────────────────────────────

    @FXML
    private void initialize() {
        loadSenderInfo();

        recipientInput.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
            if (!isFocused && recipientInput.getText().contains("@")) {
                handleAddRecipient();
            }
        });

        subjectField.textProperty().addListener((obs, o, n) -> validateSendBtn());
    }

    private void loadSenderInfo() {
        try (FileInputStream fis = new FileInputStream("data/email.properties")) {
            Properties props = new Properties();
            props.load(fis);
            fromField.setText(props.getProperty("mail.user", ""));
        } catch (Exception ignored) {
            // leave field empty for the user to fill in
        }
        fromField.textProperty().addListener((obs, o, n) -> validateSendBtn());
        fromField.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
            String text = fromField.getText();
            if (!isFocused && !text.isBlank() && !text.contains("@")) {
                fromField.setStyle(
                    "-fx-background-color:transparent; -fx-border-color:red; -fx-border-radius:4; " +
                    "-fx-font-size:13; -fx-text-fill:#1F1B16; " +
                    "-fx-focus-color:transparent; -fx-faint-focus-color:transparent;");
            } else {
                fromField.setStyle(
                    "-fx-background-color:transparent; -fx-border-color:transparent; " +
                    "-fx-font-size:13; -fx-text-fill:#1F1B16; -fx-prompt-text-fill:#9C907D; " +
                    "-fx-focus-color:transparent; -fx-faint-focus-color:transparent;");
            }
        });
    }

    // ── Recipients ────────────────────────────────────────────────────────────

    @FXML
    private void handleAddRecipient() {
        String email = recipientInput.getText().trim();
        if (email.isBlank() || !email.contains("@")) {
            recipientInput.setStyle(
                "-fx-background-color:transparent; -fx-border-color:red; -fx-border-radius:4; " +
                "-fx-font-size:13; -fx-text-fill:#1F1B16; " +
                "-fx-focus-color:transparent; -fx-faint-focus-color:transparent;");
            return;
        }
        if (recipientEmails.contains(email)) {
            recipientInput.clear();
            return;
        }
        recipientEmails.add(email);
        HBox chip = buildRecipientChip(email);
        chip.setUserData(email);
        int inputIndex = recipientTagBox.getChildren().indexOf(recipientInput);
        recipientTagBox.getChildren().add(inputIndex, chip);
        recipientInput.clear();
        recipientInput.setStyle(
            "-fx-background-color:transparent; -fx-border-color:transparent; " +
            "-fx-font-size:13; -fx-text-fill:#1F1B16; -fx-prompt-text-fill:#9C907D; " +
            "-fx-focus-color:transparent; -fx-faint-focus-color:transparent;");
        validateSendBtn();
    }

    private HBox buildRecipientChip(String email) {
        Label initialsLabel = new Label(email.substring(0, 1).toUpperCase());
        initialsLabel.setStyle(
            "-fx-font-size:11; -fx-font-weight:bold; -fx-text-fill:#6B6051; " +
            "-fx-background-color:#DDD2BC; -fx-background-radius:999; " +
            "-fx-min-width:20; -fx-max-width:20; -fx-min-height:20; -fx-max-height:20; " +
            "-fx-alignment:CENTER;");

        Label emailLabel = new Label(email);
        emailLabel.setStyle("-fx-font-size:12; -fx-text-fill:#1F1B16;");

        Button removeBtn = new Button("×");
        removeBtn.setStyle(
            "-fx-background-color:transparent; -fx-border-color:transparent; " +
            "-fx-text-fill:#9C907D; -fx-font-size:11; -fx-cursor:hand; -fx-padding:0;");
        removeBtn.setOnAction(e -> handleRemoveRecipient(email));

        HBox chip = new HBox(6, initialsLabel, emailLabel, removeBtn);
        chip.setAlignment(Pos.CENTER);
        chip.setStyle(
            "-fx-background-color:#ECE4D3; -fx-background-radius:999; -fx-padding:3 8 3 8;");
        return chip;
    }

    private void handleRemoveRecipient(String email) {
        recipientEmails.remove(email);
        recipientTagBox.getChildren().removeIf(node ->
            !(node instanceof TextField) && email.equals(node.getUserData()));
        validateSendBtn();
    }

    // ── Attachments ───────────────────────────────────────────────────────────

    @FXML
    private void handleAttachFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Attach file");
        chooser.getExtensionFilters().addAll(
            new FileChooser.ExtensionFilter("Images", "*.png", "*.jpg", "*.jpeg", "*.bmp"),
            new FileChooser.ExtensionFilter("Videos", "*.avi", "*.mp4"),
            new FileChooser.ExtensionFilter("All files", "*.*")
        );
        List<File> selected = chooser.showOpenMultipleDialog(
            attachmentListBox.getScene().getWindow());
        if (selected == null) return;
        for (File file : selected) {
            if (attachedFiles.stream().anyMatch(
                    f -> f.getAbsolutePath().equals(file.getAbsolutePath()))) continue;
            attachedFiles.add(file);
            attachmentListBox.getChildren().add(buildAttachmentRow(file));
        }
        updateAttachmentHeader();
    }

    private void handleRemoveAttachment(File file) {
        int idx = attachedFiles.indexOf(file);
        if (idx >= 0) {
            attachedFiles.remove(idx);
            attachmentListBox.getChildren().remove(idx);
        }
        updateAttachmentHeader();
    }

    private HBox buildAttachmentRow(File file) {
        boolean isVideo   = file.getName().toLowerCase().matches(".*\\.(avi|mp4|mov|mkv)");
        boolean oversized = file.length() > MAX_SIZE_BYTES;

        Label typeIcon = new Label(isVideo ? "🎬" : "🖼");
        typeIcon.setStyle("-fx-font-size:11;");
        StackPane iconBox = new StackPane(typeIcon);
        iconBox.setStyle(
            "-fx-background-color:#F5DAC9; -fx-background-radius:6; " +
            "-fx-min-width:32; -fx-max-width:32; -fx-min-height:32; -fx-max-height:32; " +
            "-fx-alignment:CENTER;");

        Label filenameLabel = new Label(file.getName());
        filenameLabel.setStyle("-fx-font-size:12; -fx-font-weight:bold; -fx-text-fill:#1F1B16;");

        double mb = file.length() / (1024.0 * 1024.0);
        String metaText = oversized
            ? String.format("%.1f MB - exceeds 25 MB limit", mb)
            : String.format("%.1f MB", mb);
        Label metaLabel = new Label(metaText);
        metaLabel.setStyle("-fx-font-size:11; -fx-text-fill:" +
            (oversized ? "#B0432B" : "#9C907D") + ";");

        VBox info = new VBox(3, filenameLabel, metaLabel);
        HBox.setHgrow(info, Priority.ALWAYS);

        ProgressBar progress = new ProgressBar(0);
        progress.setPrefWidth(120);
        progress.setPrefHeight(4);
        progress.setVisible(false);
        progress.setStyle("-fx-accent:#C8623F;");

        Button removeBtn = new Button("×");
        removeBtn.setStyle(
            "-fx-background-color:transparent; -fx-border-color:transparent; " +
            "-fx-text-fill:#9C907D; -fx-font-size:14; -fx-cursor:hand;");
        removeBtn.setOnAction(e -> handleRemoveAttachment(file));

        HBox row = new HBox(12, iconBox, info, progress, removeBtn);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setStyle(oversized
            ? "-fx-background-color:#FDE8E8; -fx-border-color:#F5C0C0; " +
              "-fx-border-radius:8; -fx-background-radius:8; -fx-padding:10 14 10 14;"
            : "-fx-background-color:#FBF8F3; -fx-border-color:#ECE4D3; " +
              "-fx-border-radius:8; -fx-background-radius:8; -fx-padding:10 14 10 14;");
        return row;
    }

    private void updateAttachmentHeader() {
        int count = attachedFiles.size();
        if (count == 0) {
            attachmentsSummaryLabel.setText("");
        } else {
            double totalMb = attachedFiles.stream().mapToLong(File::length).sum()
                / (1024.0 * 1024.0);
            attachmentsSummaryLabel.setText(
                "· " + count + " FILE" + (count > 1 ? "S" : "") + " · " +
                String.format("%.1f", totalMb) + " MB");
        }
    }

    // ── Validation ────────────────────────────────────────────────────────────

    private void validateSendBtn() {
        String from = fromField.getText();
        sendBtn.setDisable(
            from.isBlank() || !from.contains("@") ||
            recipientEmails.isEmpty() || subjectField.getText().isBlank());
    }

    // ── Send ──────────────────────────────────────────────────────────────────

    @FXML
    private void handleSend() {
        handleSendWithFiles(new ArrayList<>(attachedFiles));
    }

    @FXML
    private void handleSendWithoutLarge() {
        List<File> filtered = attachedFiles.stream()
            .filter(f -> f.length() <= MAX_SIZE_BYTES)
            .collect(Collectors.toList());
        handleSendWithFiles(filtered);
    }

    private void handleSendWithFiles(List<File> files) {
        errorBanner.setVisible(false);
        errorBanner.setManaged(false);

        List<File> oversized = files.stream()
            .filter(f -> f.length() > MAX_SIZE_BYTES)
            .collect(Collectors.toList());
        if (!oversized.isEmpty()) {
            errorTitle.setText("Couldn't send - attachment too large");
            errorBody.setText("Your mail provider rejected files over 25 MB.");
            sendWithoutLargeBtn.setVisible(true);
            sendWithoutLargeBtn.setManaged(true);
            errorBanner.setVisible(true);
            errorBanner.setManaged(true);
            return;
        }

        String senderEmail = fromField.getText().trim();
        if (senderEmail.isBlank() || !senderEmail.contains("@") ||
                recipientEmails.isEmpty() || subjectField.getText().isBlank()) return;

        uploadStatusLabel.setText("Uploading attachments…");
        sendBtn.setDisable(true);
        showProgressBars(true);

        String       subject    = subjectField.getText();
        String       body       = bodyArea.getText();
        List<String> recipients = new ArrayList<>(recipientEmails);

        new Thread(() -> {
            try {
                sendSmtp(senderEmail, recipients, subject, body, files);
                Platform.runLater(() -> {
                    uploadStatusLabel.setText("");
                    showProgressBars(false);
                    validateSendBtn();
                    toastLabel.setText("✓ Message sent to " + String.join(" and ", recipients));
                    successToast.setVisible(true);
                    successToast.setManaged(true);
                    PauseTransition pause = new PauseTransition(Duration.seconds(3));
                    pause.setOnFinished(ev -> {
                        successToast.setVisible(false);
                        successToast.setManaged(false);
                    });
                    pause.play();
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    uploadStatusLabel.setText("");
                    showProgressBars(false);
                    validateSendBtn();
                    String msg = ex.getMessage() != null ? ex.getMessage() : "";
                    if (msg.contains("too large") || msg.contains("552")
                            || msg.contains("5.3.4")) {
                        errorTitle.setText("Couldn't send - attachment too large");
                        errorBody.setText("Your mail provider rejected files over 25 MB.");
                        sendWithoutLargeBtn.setVisible(true);
                        sendWithoutLargeBtn.setManaged(true);
                        errorBanner.setVisible(true);
                        errorBanner.setManaged(true);
                    } else {
                        Alert alert = new Alert(Alert.AlertType.ERROR,
                            "Failed to send: " + msg, ButtonType.OK);
                        alert.setHeaderText(null);
                        alert.showAndWait();
                    }
                });
            }
        }).start();
    }

    private void showProgressBars(boolean visible) {
        for (var child : attachmentListBox.getChildren()) {
            if (child instanceof HBox) {
                HBox row = (HBox) child;
                for (var c : row.getChildren()) {
                    if (c instanceof ProgressBar) {
                        ((ProgressBar) c).setVisible(visible);
                    }
                }
            }
        }
    }

    private void sendSmtp(String senderEmail, List<String> recipients,
                           String subject, String body, List<File> files) throws Exception {
        Properties credentials = new Properties();
        try (FileInputStream fis = new FileInputStream("data/email.properties")) {
            credentials.load(fis);
        } catch (Exception e) {
            throw new Exception(
                "Could not load data/email.properties. " +
                "Please create it with a mail.password entry.");
        }

        String pass = credentials.getProperty("mail.password");
        if (pass == null)
            throw new Exception("mail.password missing in email.properties");

        Properties smtpProps = new Properties();
        smtpProps.put("mail.smtp.host",            "smtp.gmail.com");
        smtpProps.put("mail.smtp.port",            "587");
        smtpProps.put("mail.smtp.auth",            "true");
        smtpProps.put("mail.smtp.starttls.enable", "true");

        Session session = Session.getInstance(smtpProps, new Authenticator() {
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(senderEmail, pass);
            }
        });

        MimeMessage msg = new MimeMessage(session);
        msg.setFrom(new InternetAddress(senderEmail));

        InternetAddress[] toAddresses = new InternetAddress[recipients.size()];
        for (int i = 0; i < recipients.size(); i++) {
            toAddresses[i] = new InternetAddress(recipients.get(i));
        }
        msg.setRecipients(Message.RecipientType.TO, toAddresses);
        msg.setSubject(subject);

        MimeBodyPart textPart = new MimeBodyPart();
        textPart.setText(body);

        Multipart multipart = new MimeMultipart();
        multipart.addBodyPart(textPart);
        for (File file : files) {
            MimeBodyPart filePart = new MimeBodyPart();
            filePart.attachFile(file);
            multipart.addBodyPart(filePart);
        }
        msg.setContent(multipart);
        Transport.send(msg);
    }

    // ── Cancel ────────────────────────────────────────────────────────────────

    @FXML
    private void handleCancel() {
        recipientEmails.clear();
        attachedFiles.clear();
        recipientTagBox.getChildren().removeIf(node -> !(node instanceof TextField));
        attachmentListBox.getChildren().clear();
        subjectField.clear();
        bodyArea.clear();
        recipientInput.clear();
        recipientInput.setStyle(
            "-fx-background-color:transparent; -fx-border-color:transparent; " +
            "-fx-font-size:13; -fx-text-fill:#1F1B16; -fx-prompt-text-fill:#9C907D; " +
            "-fx-focus-color:transparent; -fx-faint-focus-color:transparent;");
        errorBanner.setVisible(false);
        errorBanner.setManaged(false);
        successToast.setVisible(false);
        successToast.setManaged(false);
        attachmentsSummaryLabel.setText("");
        validateSendBtn();
    }
}
