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
 *
 * Codec strategy:
 *   1st try: avc1 (H264) .mp4  — JavaFX MediaPlayer can play this natively
 *   2nd try: MJPG .avi          — always available in OpenCV, opens via Desktop
 */
public class VideoCompiler {

    private static final int    FRAME_WIDTH  = 1280;
    private static final int    FRAME_HEIGHT = 720;
    private static final double FPS          = 25.0;

    // =========================================================
    // PUBLIC API — backward-compatible (no graphic, no textStyle)
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
    // PUBLIC API — full overload
    // =========================================================

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
        boolean useBox    = "WITH_BOX".equalsIgnoreCase(textStyle);

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

        // 4. Try codecs: avc1 MP4 first (JavaFX plays it), MJPG AVI fallback
        String      timestamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss")
                                        .format(new java.util.Date());
        VideoWriter writer    = new VideoWriter();
        Size        frameSize = new Size(FRAME_WIDTH, FRAME_HEIGHT);
        String      outPath   = null;

        int[][]  codecs     = {
            { 'a', 'v', 'c', '1' },  // H264 MP4 — JavaFX plays natively on Windows
            { 'M', 'J', 'P', 'G' },  // Motion JPEG AVI — always works with OpenCV
        };
        String[] extensions = { ".mp4", ".avi" };

        for (int ci = 0; ci < codecs.length; ci++) {
            int[] c    = codecs[ci];
            int fourcc = VideoWriter.fourcc(
                    (char)c[0], (char)c[1], (char)c[2], (char)c[3]);
            String candidate = outputDir + File.separator
                    + "viflow_output_" + timestamp + extensions[ci];

            writer.open(candidate, fourcc, FPS, frameSize, true);
            if (writer.isOpened()) {
                outPath = candidate;
                System.out.println("VideoCompiler: codec ["
                        + (char)c[0]+(char)c[1]+(char)c[2]+(char)c[3]
                        + "] → " + candidate);
                break;
            }
            System.err.println("VideoCompiler: codec ["
                    + (char)c[0]+(char)c[1]+(char)c[2]+(char)c[3]
                    + "] not available, trying next…");
            writer.release();
            writer = new VideoWriter();
        }

        if (!writer.isOpened() || outPath == null)
            throw new IOException("VideoWriter failed with all codecs. Output dir: " + outputDir);

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

            if (graphicOverlay != null)
                drawGraphicOverlay(g2d, graphicOverlay, graphicPos);

            if (overlayText != null && !overlayText.isBlank()) {
                if (useBox) drawTextWithBackground(g2d, overlayText, position);
                else        drawTextPlain(g2d, overlayText, position);
            }

            g2d.dispose();

            byte[] modifiedData =
                    ((DataBufferByte) javaBi.getRaster().getDataBuffer()).getData();
            com.wig3003.photoapp.util.BufferedImage modifiedCustomBi =
                    new com.wig3003.photoapp.util.BufferedImage(
                            javaBi.getWidth(), javaBi.getHeight(),
                            customBi.getChannels(), modifiedData);

            Mat overlaidFrame = ImageUtils.bufferedImageToMat(modifiedCustomBi);

            for (int f = 0; f < perPhotoFrames; f++)
                writer.write(overlaidFrame);

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
    // TEXT — PLAIN
    // =========================================================

    private void drawTextPlain(Graphics2D g2d, String text, String position) {
        g2d.setFont(new Font("Arial", Font.BOLD, 32));
        FontMetrics fm = g2d.getFontMetrics();
        int textX = (FRAME_WIDTH - fm.stringWidth(text)) / 2;
        int textY = computeTextY(position, fm);
        g2d.setColor(new Color(0, 0, 0, 160));
        g2d.drawString(text, textX + 2, textY + 2);
        g2d.setColor(Color.WHITE);
        g2d.drawString(text, textX, textY);
    }

    // =========================================================
    // TEXT — WITH BACKGROUND BOX
    // =========================================================

    private void drawTextWithBackground(Graphics2D g2d, String text, String position) {
        g2d.setFont(new Font("Arial", Font.BOLD, 32));
        FontMetrics fm = g2d.getFontMetrics();
        int textW = fm.stringWidth(text);
        int textH = fm.getAscent();
        int padX  = 20;
        int padY  = 12;
        int textX = (FRAME_WIDTH - textW) / 2;
        int textY = computeTextY(position, fm);
        int boxX  = textX - padX;
        int boxY  = textY - textH - padY;
        int boxW  = textW + padX * 2;
        int boxH  = textH + padY * 2;

        g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.55f));
        g2d.setColor(Color.BLACK);
        g2d.fillRoundRect(boxX, boxY, boxW, boxH, 16, 16);

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
        double scale = Math.min((double) maxW / graphic.getWidth(),
                                (double) maxH / graphic.getHeight());
        int drawW  = (int) (graphic.getWidth()  * scale);
        int drawH  = (int) (graphic.getHeight() * scale);
        int margin = 16;
        int drawX, drawY;

        switch (position) {
            case "TOP_RIGHT":
                drawX = FRAME_WIDTH  - drawW - margin; drawY = margin; break;
            case "BOTTOM_LEFT":
                drawX = margin; drawY = FRAME_HEIGHT - drawH - margin; break;
            case "BOTTOM_RIGHT":
                drawX = FRAME_WIDTH  - drawW - margin;
                drawY = FRAME_HEIGHT - drawH - margin; break;
            case "CENTER":
                drawX = (FRAME_WIDTH  - drawW) / 2;
                drawY = (FRAME_HEIGHT - drawH) / 2; break;
            default: // TOP_LEFT
                drawX = margin; drawY = margin; break;
        }

        g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.80f));
        g2d.drawImage(graphic, drawX, drawY, drawW, drawH, null);
        g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1.0f));
    }

    // =========================================================
    // HELPERS
    // =========================================================

    private int computeTextY(String position, FontMetrics fm) {
        switch (position) {
            case "TOP":    return fm.getAscent() + 40;
            case "CENTER": return (FRAME_HEIGHT + fm.getAscent() - fm.getDescent()) / 2;
            default:       return FRAME_HEIGHT - 40;
        }
    }

    private Mat letterboxResize(Mat src) {
        Mat    canvas  = Mat.zeros(FRAME_HEIGHT, FRAME_WIDTH, src.type());
        double scale   = Math.min((double) FRAME_WIDTH / src.cols(),
                                  (double) FRAME_HEIGHT / src.rows());
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
                writer.write(blended); blended.release();
            }
            for (int t = 0; t < half; t++) {
                double ratio = (double)(t + 1) / half;
                Mat blended  = new Mat();
                Core.addWeighted(frameB, ratio, black, 0, 0, blended);
                writer.write(blended); blended.release();
            }
            black.release();
        } else {
            for (int t = 0; t < transFrames; t++) {
                double alpha = (double) t / (transFrames - 1);
                Mat blended  = new Mat();
                Core.addWeighted(frameA, 1.0 - alpha, frameB, alpha, 0, blended);
                writer.write(blended); blended.release();
            }
        }
    }
}