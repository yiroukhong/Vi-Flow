package com.wig3003.photoapp.synthesis;

import com.wig3003.photoapp.util.ImageUtils;

import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.Rect;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.opencv.videoio.VideoWriter;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

import javax.imageio.ImageIO;

/**
 * Winnie — Multimedia Synthesis
 * Video Compiler — Contract §6
 * AVI + MJPG, 1280x720, 25fps.
 * Features: text overlay (plain or with background box), graphic image overlay.
 */
public class VideoCompiler {

    private static final int    FRAME_WIDTH  = 1280;
    private static final int    FRAME_HEIGHT = 720;
    private static final double FPS          = 25.0;

    // =========================================================
    // PUBLIC API — backward-compatible overload (no graphic, no textStyle)
    // =========================================================

    public String compileVideo(
            List<String> imagePaths,
            int durationPerPhoto,
            String overlayText,
            String outputDir,
            String transitionType,
            String textPosition,
            int fps) throws IOException {

        return compileVideo(imagePaths, durationPerPhoto, overlayText,
                outputDir, transitionType, textPosition, fps,
                null, null, "PLAIN");
    }

    // =========================================================
    // PUBLIC API — full overload with graphic overlay + text style
    // =========================================================

    /**
     * @param overlayImagePath Path to PNG/JPG to stamp on every frame (null = none)
     * @param overlayImagePos  "TOP_LEFT" | "TOP_RIGHT" | "BOTTOM_LEFT" |
     *                         "BOTTOM_RIGHT" | "CENTER"  (null = TOP_LEFT)
     * @param textStyle        "PLAIN"    → white text directly on frame
     *                         "WITH_BOX" → white text on semi-transparent black box
     */
    public String compileVideo(
            List<String> imagePaths,
            int durationPerPhoto,
            String overlayText,
            String outputDir,
            String transitionType,
            String textPosition,
            int fps,
            String overlayImagePath,
            String overlayImagePos,
            String textStyle) throws IOException {

        // 1. Validate
        if (imagePaths == null || imagePaths.isEmpty())
            throw new IllegalArgumentException("imagePaths must not be empty");
        if (durationPerPhoto <= 0)
            throw new IllegalArgumentException("durationPerPhoto must be > 0");
        if (outputDir == null || outputDir.isBlank())
            throw new IllegalArgumentException("outputDir must not be null or blank");

        String transition = (transitionType == null) ? "NONE" : transitionType.toUpperCase();
        if (!transition.equals("FADE") && !transition.equals("CROSS"))
            transition = "NONE";

        String position = (textPosition == null) ? "BOTTOM" : textPosition.toUpperCase();
        if (!position.equals("TOP") && !position.equals("CENTER"))
            position = "BOTTOM";

        String graphicPos = (overlayImagePos == null) ? "TOP_LEFT" : overlayImagePos.toUpperCase();

        // Normalise textStyle — default PLAIN
        boolean useBox = "WITH_BOX".equalsIgnoreCase(textStyle);

        // 2. Pre-load graphic overlay once
        BufferedImage graphicOverlay = null;
        if (overlayImagePath != null && !overlayImagePath.isBlank()) {
            File imgFile = new File(overlayImagePath);
            if (imgFile.exists()) {
                graphicOverlay = ImageIO.read(imgFile);
                System.out.println("VideoCompiler: graphic overlay loaded → " + overlayImagePath);
            } else {
                System.err.println("VideoCompiler: overlay image not found → " + overlayImagePath);
            }
        }

        // 3. Ensure output directory exists
        Files.createDirectories(Paths.get(outputDir));

        // 4. VideoWriter — MJPG, AVI, 1280x720
        String filename = "viflow_output_"
                + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss")
                        .format(new java.util.Date())
                + ".avi";
        String      outPath   = outputDir + File.separator + filename;
        int         fourcc    = VideoWriter.fourcc('M', 'J', 'P', 'G');
        Size        frameSize = new Size(FRAME_WIDTH, FRAME_HEIGHT);
        VideoWriter writer    = new VideoWriter();

        writer.open(outPath, fourcc, FPS, frameSize, true);
        if (!writer.isOpened())
            throw new IOException("VideoWriter failed to open: " + outPath
                    + "\nMJPG codec unavailable.");

        // 5. Process each image
        int perPhotoFrames = (int) (durationPerPhoto * FPS);

        for (int i = 0; i < imagePaths.size(); i++) {
            String imgPath = imagePaths.get(i);
            if (imgPath == null || imgPath.isBlank()) continue;

            Mat frame = ImageUtils.loadMatFromPath(imgPath);
            if (frame == null || frame.empty()) continue;

            Mat letterboxed = letterboxResize(frame);

            com.wig3003.photoapp.util.BufferedImage customBi =
                    ImageUtils.matToBufferedImage(letterboxed);

            BufferedImage javaBi = new BufferedImage(
                    customBi.getWidth(), customBi.getHeight(),
                    BufferedImage.TYPE_3BYTE_BGR);
            byte[] srcData  = customBi.getData();
            byte[] destData = ((DataBufferByte) javaBi.getRaster().getDataBuffer()).getData();
            System.arraycopy(srcData, 0, destData, 0, srcData.length);

            Graphics2D g2d = javaBi.createGraphics();
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g2d.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);

            // Draw graphic image overlay
            if (graphicOverlay != null) {
                drawGraphicOverlay(g2d, graphicOverlay, graphicPos);
            }

            // Draw text — plain OR with background box depending on user selection
            if (overlayText != null && !overlayText.isBlank()) {
                if (useBox) {
                    drawTextWithBackground(g2d, overlayText, position);  // WITH BOX
                } else {
                    drawTextPlain(g2d, overlayText, position);           // PLAIN
                }
            }

            g2d.dispose();

            byte[] modifiedData =
                    ((DataBufferByte) javaBi.getRaster().getDataBuffer()).getData();
            com.wig3003.photoapp.util.BufferedImage modifiedCustomBi =
                    new com.wig3003.photoapp.util.BufferedImage(
                            javaBi.getWidth(), javaBi.getHeight(),
                            customBi.getChannels(), modifiedData);

            Mat overlaidFrame = ImageUtils.bufferedImageToMat(modifiedCustomBi);

            for (int f = 0; f < perPhotoFrames; f++) {
                writer.write(overlaidFrame);
            }

            if (!transition.equals("NONE") && i < imagePaths.size() - 1) {
                String nextPath  = imagePaths.get(i + 1);
                Mat    nextFrame = ImageUtils.loadMatFromPath(nextPath);
                if (nextFrame != null && !nextFrame.empty()) {
                    Mat nextLetterboxed = letterboxResize(nextFrame);
                    writeTransitionFrames(writer, overlaidFrame, nextLetterboxed, transition);
                    nextLetterboxed.release();
                    nextFrame.release();
                }
            }

            overlaidFrame.release();
            letterboxed.release();
            frame.release();
        }

        writer.release();
        return outPath;
    }

    // =========================================================
    // TEXT — PLAIN (no background box)
    // =========================================================

    /**
     * Draws white text directly on the frame without any background.
     * Original style per report §3.2.2.
     */
    private void drawTextPlain(Graphics2D g2d, String text, String position) {
        g2d.setFont(new Font("Arial", Font.BOLD, 32));
        FontMetrics fm = g2d.getFontMetrics();
        int textX = (FRAME_WIDTH - fm.stringWidth(text)) / 2;
        int textY = computeTextY(position, fm);

        // Thin dark shadow for readability on bright backgrounds
        g2d.setColor(new Color(0, 0, 0, 160));
        g2d.drawString(text, textX + 2, textY + 2);

        // White text on top
        g2d.setColor(Color.WHITE);
        g2d.drawString(text, textX, textY);
    }

    // =========================================================
    // TEXT — WITH BACKGROUND BOX
    // =========================================================

    /**
     * Draws text with a semi-transparent rounded black box behind it.
     * Improves readability against any background image.
     */
    private void drawTextWithBackground(Graphics2D g2d, String text, String position) {
        g2d.setFont(new Font("Arial", Font.BOLD, 32));
        FontMetrics fm = g2d.getFontMetrics();

        int textW  = fm.stringWidth(text);
        int textH  = fm.getAscent();
        int padX   = 20;
        int padY   = 12;
        int boxW   = textW + padX * 2;
        int boxH   = textH + padY * 2;
        int textX  = (FRAME_WIDTH - textW) / 2;
        int textY  = computeTextY(position, fm);
        int boxX   = textX - padX;
        int boxY   = textY - textH - padY;

        // Semi-transparent black rounded box
        g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.55f));
        g2d.setColor(Color.BLACK);
        g2d.fillRoundRect(boxX, boxY, boxW, boxH, 16, 16);

        // White text fully opaque on top of box
        g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1.0f));
        g2d.setColor(Color.WHITE);
        g2d.drawString(text, textX, textY);
    }

    // =========================================================
    // GRAPHIC IMAGE OVERLAY
    // =========================================================

    private void drawGraphicOverlay(Graphics2D g2d, BufferedImage graphic, String position) {
        int maxW  = 200;
        int maxH  = 100;
        double scaleW = (double) maxW / graphic.getWidth();
        double scaleH = (double) maxH / graphic.getHeight();
        double scale  = Math.min(scaleW, scaleH);
        int drawW = (int) (graphic.getWidth()  * scale);
        int drawH = (int) (graphic.getHeight() * scale);
        int margin = 16;
        int drawX, drawY;

        switch (position) {
            case "TOP_RIGHT":
                drawX = FRAME_WIDTH  - drawW - margin;
                drawY = margin;
                break;
            case "BOTTOM_LEFT":
                drawX = margin;
                drawY = FRAME_HEIGHT - drawH - margin;
                break;
            case "BOTTOM_RIGHT":
                drawX = FRAME_WIDTH  - drawW - margin;
                drawY = FRAME_HEIGHT - drawH - margin;
                break;
            case "CENTER":
                drawX = (FRAME_WIDTH  - drawW) / 2;
                drawY = (FRAME_HEIGHT - drawH) / 2;
                break;
            case "TOP_LEFT":
            default:
                drawX = margin;
                drawY = margin;
                break;
        }

        g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.80f));
        g2d.drawImage(graphic, drawX, drawY, drawW, drawH, null);
        g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1.0f));
    }

    // =========================================================
    // PRIVATE HELPERS
    // =========================================================

    private int computeTextY(String position, FontMetrics fm) {
        switch (position) {
            case "TOP":    return fm.getAscent() + 40;
            case "CENTER": return (FRAME_HEIGHT + fm.getAscent() - fm.getDescent()) / 2;
            case "BOTTOM":
            default:       return FRAME_HEIGHT - 40;
        }
    }

    private Mat letterboxResize(Mat src) {
        Mat    canvas  = Mat.zeros(FRAME_HEIGHT, FRAME_WIDTH, src.type());
        double scaleW  = (double) FRAME_WIDTH  / src.cols();
        double scaleH  = (double) FRAME_HEIGHT / src.rows();
        double scale   = Math.min(scaleW, scaleH);
        int    scaledW = (int) (src.cols() * scale);
        int    scaledH = (int) (src.rows() * scale);
        int    offsetX = (FRAME_WIDTH  - scaledW) / 2;
        int    offsetY = (FRAME_HEIGHT - scaledH) / 2;
        Mat    resized = new Mat();
        Imgproc.resize(src, resized, new Size(scaledW, scaledH));
        resized.copyTo(canvas.submat(new Rect(offsetX, offsetY, scaledW, scaledH)));
        resized.release();
        return canvas;
    }

    private void writeTransitionFrames(VideoWriter writer, Mat frameA, Mat frameB,
                                       String transition) {
        int transFrames = 12;
        if ("FADE".equals(transition)) {
            int half  = transFrames / 2;
            Mat black = Mat.zeros(frameA.size(), frameA.type());
            for (int t = 0; t < half; t++) {
                double ratio = 1.0 - (double)(t + 1) / half;
                Mat blended  = new Mat();
                Core.addWeighted(frameA, ratio, black, 0, 0, blended);
                writer.write(blended);
                blended.release();
            }
            for (int t = 0; t < half; t++) {
                double ratio = (double)(t + 1) / half;
                Mat blended  = new Mat();
                Core.addWeighted(frameB, ratio, black, 0, 0, blended);
                writer.write(blended);
                blended.release();
            }
            black.release();
        } else {
            for (int t = 0; t < transFrames; t++) {
                double alpha = (double) t / (transFrames - 1);
                Mat blended  = new Mat();
                Core.addWeighted(frameA, 1.0 - alpha, frameB, alpha, 0, blended);
                writer.write(blended);
                blended.release();
            }
        }
    }
}