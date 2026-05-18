package com.wig3003.photoapp.synthesis;

import com.wig3003.photoapp.util.ImageUtils;

import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.opencv.videoio.VideoWriter;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.DataBufferByte;

import java.io.File;
import java.io.IOException;
import java.util.List;

public class VideoCompiler {

    private static final int FPS               = 25;
    private static final int TRANSITION_FRAMES = 12; // ~0.5 s at 25 fps

    // ── Primary entry point (with transition) ─────────────────────
    public String compileVideo(
            List<String> imagePaths,
            int durationPerPhoto,
            String overlayText,
            String outputDir,
            String transitionType) throws IOException {

        if (imagePaths == null || imagePaths.isEmpty())
            throw new IllegalArgumentException("imagePaths must not be empty");
        if (durationPerPhoto <= 0)
            throw new IllegalArgumentException("durationPerPhoto must be > 0");
        if (transitionType == null) transitionType = "NONE";

        String filename = "video_" + System.currentTimeMillis() + ".avi";
        String outPath  = outputDir + File.separator + filename;

        // MJPG is universally available; XVID requires a separately installed codec
        int  fourcc    = VideoWriter.fourcc('M', 'J', 'P', 'G');
        Size frameSize = new Size(1280, 720);

        VideoWriter writer = new VideoWriter();
        writer.open(outPath, fourcc, FPS, frameSize, true);

        if (!writer.isOpened())
            throw new IOException("VideoWriter could not open: " + outPath);

        int clipFrames = durationPerPhoto * FPS;
        // Cap transition at ⅓ of clip so it never swamps a short clip
        int tranFrames = Math.min(TRANSITION_FRAMES, clipFrames / 3);

        Mat current = prepareFrame(imagePaths.get(0), overlayText, frameSize);

        for (int i = 0; i < imagePaths.size(); i++) {
            for (int f = 0; f < clipFrames; f++) writer.write(current);

            if (i < imagePaths.size() - 1) {
                Mat next = prepareFrame(imagePaths.get(i + 1), overlayText, frameSize);
                writeTransition(writer, current, next, transitionType, tranFrames);
                current.release();
                current = next;
            }
        }

        current.release();
        writer.release();
        return outPath;
    }

    // ── Backward-compatible overload (no transition) ───────────────
    public String compileVideo(
            List<String> imagePaths,
            int durationPerPhoto,
            String overlayText,
            String outputDir) throws IOException {
        return compileVideo(imagePaths, durationPerPhoto, overlayText, outputDir, "NONE");
    }

    // ── Frame preparation ──────────────────────────────────────────

    private Mat prepareFrame(String imgPath, String overlayText, Size frameSize)
            throws IOException {

        Mat frame = ImageUtils.loadMatFromPath(imgPath);
        Imgproc.resize(frame, frame, frameSize);

        com.wig3003.photoapp.util.BufferedImage customBi =
                ImageUtils.matToBufferedImage(frame);

        java.awt.image.BufferedImage javaBi = new java.awt.image.BufferedImage(
                customBi.getWidth(), customBi.getHeight(),
                java.awt.image.BufferedImage.TYPE_3BYTE_BGR);

        byte[] customData = customBi.getData();
        byte[] javaData   =
                ((DataBufferByte) javaBi.getRaster().getDataBuffer()).getData();
        System.arraycopy(customData, 0, javaData, 0, customData.length);

        if (overlayText != null && !overlayText.isBlank()) {
            Graphics2D g2d = javaBi.createGraphics();
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g2d.setFont(new Font("Arial", Font.BOLD, 32));
            g2d.setColor(Color.WHITE);
            FontMetrics fm = g2d.getFontMetrics();
            int textX = (1280 - fm.stringWidth(overlayText)) / 2;
            g2d.drawString(overlayText, textX, 720 - 40);
            g2d.dispose();
        }

        com.wig3003.photoapp.util.BufferedImage result =
                new com.wig3003.photoapp.util.BufferedImage(
                        javaBi.getWidth(), javaBi.getHeight(), 3, javaData);

        frame.release();
        return ImageUtils.bufferedImageToMat(result);
    }

    // ── Transition rendering ───────────────────────────────────────

    private void writeTransition(VideoWriter writer, Mat from, Mat to,
                                  String type, int frames) {
        if (frames <= 0 || "NONE".equals(type)) return;

        Mat tmp = new Mat();
        for (int t = 1; t <= frames; t++) {
            // alpha runs 1.0 → ~0.0 (from fully visible → invisible)
            double alpha = 1.0 - (double) t / frames;

            if ("FADE".equals(type)) {
                // Fade current clip to black
                from.convertTo(tmp, from.type(), alpha, 0.0);
            } else if ("CROSS".equals(type)) {
                // Cross-dissolve: blend current into next
                Core.addWeighted(from, alpha, to, 1.0 - alpha, 0.0, tmp);
            }

            writer.write(tmp);
        }
        tmp.release();
    }
}
