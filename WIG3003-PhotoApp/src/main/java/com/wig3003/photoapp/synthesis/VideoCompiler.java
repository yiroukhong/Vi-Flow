package com.wig3003.photoapp.synthesis;

import com.wig3003.photoapp.util.ImageUtils;

import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.Rect;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.opencv.videoio.VideoWriter;

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

/**
 * Winnie — Multimedia Synthesis
 * Video Compiler — Contract §6
 * AVI + XVID codec, 1280x720, 25fps fixed as per report decision.
 */
public class VideoCompiler {

    private static final int    FRAME_WIDTH  = 1280;
    private static final int    FRAME_HEIGHT = 720;
    private static final double FPS          = 25.0;

    public String compileVideo(
            List<String> imagePaths,
            int durationPerPhoto,
            String overlayText,
            String outputDir,
            String transitionType,
            String textPosition,
            int fps) throws IOException {

        // 1. Validate inputs
        if (imagePaths == null || imagePaths.isEmpty())
            throw new IllegalArgumentException("imagePaths must not be empty");
        if (durationPerPhoto <= 0)
            throw new IllegalArgumentException("durationPerPhoto must be > 0");
        if (outputDir == null || outputDir.isBlank())
            throw new IllegalArgumentException("outputDir must not be null or blank");

        // Normalise transitionType
        String transition = (transitionType == null) ? "NONE" : transitionType.toUpperCase();
        if (!transition.equals("FADE") && !transition.equals("CROSS"))
            transition = "NONE";

        // Normalise textPosition — default BOTTOM per report §3.2.4
        String position = (textPosition == null) ? "BOTTOM" : textPosition.toUpperCase();
        if (!position.equals("TOP") && !position.equals("CENTER"))
            position = "BOTTOM";

        // 2. Ensure output directory exists
        Files.createDirectories(Paths.get(outputDir));

        // 3. Set up VideoWriter — XVID codec, AVI container, 1280x720, 25fps
        //    AVI+XVID chosen per report §3.2.2 for JavaFX MediaPlayer compatibility
        String filename = "viflow_output_"
                + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss")
                        .format(new java.util.Date())
                + ".avi";
        String outPath = outputDir + File.separator + filename;

        int         fourcc    = VideoWriter.fourcc('X', 'V', 'I', 'D');
        Size        frameSize = new Size(FRAME_WIDTH, FRAME_HEIGHT);
        VideoWriter writer    = new VideoWriter();

        writer.open(outPath, fourcc, FPS, frameSize, true);

        if (!writer.isOpened()) {
            throw new IOException(
                "VideoWriter failed to open: " + outPath + "\n"
                + "XVID codec not found. Please install XviD from https://www.xvid.com/download/\n"
                + "and ensure opencv_java4120.dll and opencv_videoio_ffmpeg4120_64.dll\n"
                + "are present in C:/Windows/System32.");
        }

        // 4. Process each image
        // Total frames per clip = durationPerPhoto × FPS (per report §3.2.2)
        int perPhotoFrames = (int) (durationPerPhoto * FPS);

        for (int i = 0; i < imagePaths.size(); i++) {
            String imgPath = imagePaths.get(i);

            if (imgPath == null || imgPath.isBlank()) {
                System.err.println("Warning: skipping null/blank image path");
                continue;
            }

            // a. Load image
            Mat frame = ImageUtils.loadMatFromPath(imgPath);
            if (frame == null || frame.empty()) {
                System.err.println("Warning: could not load image, skipping: " + imgPath);
                continue;
            }

            // b. Letterbox resize to 1280x720 (per report §3.2.2)
            Mat letterboxed = letterboxResize(frame);

            // c. Convert Mat → custom BufferedImage
            com.wig3003.photoapp.util.BufferedImage customBi =
                    ImageUtils.matToBufferedImage(letterboxed);

            // d. Bridge to standard BufferedImage for Graphics2D
            //    BufferedImage bridge used for superior font quality (per report §3.2.2)
            BufferedImage javaBi = new BufferedImage(
                    customBi.getWidth(), customBi.getHeight(),
                    BufferedImage.TYPE_3BYTE_BGR);
            byte[] srcData  = customBi.getData();
            byte[] destData = ((DataBufferByte) javaBi.getRaster().getDataBuffer()).getData();
            System.arraycopy(srcData, 0, destData, 0, srcData.length);

            // e. Draw overlay text via Graphics2D (anti-aliased, per report §3.2.2)
            if (overlayText != null && !overlayText.isBlank()) {
                Graphics2D g2d = javaBi.createGraphics();
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                g2d.setFont(new Font("Arial", Font.BOLD, 32));
                g2d.setColor(Color.WHITE);
                FontMetrics fm = g2d.getFontMetrics();
                // textX = (1280 - textWidth) / 2, textY = 720 - 40 per report §3.2.4
                int textX = (FRAME_WIDTH - fm.stringWidth(overlayText)) / 2;
                int textY = computeTextY(position, fm);
                g2d.drawString(overlayText, textX, textY);
                g2d.dispose();
            }

            // f. Bridge back to custom BufferedImage
            byte[] modifiedData =
                    ((DataBufferByte) javaBi.getRaster().getDataBuffer()).getData();
            com.wig3003.photoapp.util.BufferedImage modifiedCustomBi =
                    new com.wig3003.photoapp.util.BufferedImage(
                            javaBi.getWidth(), javaBi.getHeight(),
                            customBi.getChannels(), modifiedData);

            // g. Convert back to Mat
            Mat overlaidFrame = ImageUtils.bufferedImageToMat(modifiedCustomBi);

            // h. Write main clip frames
            for (int f = 0; f < perPhotoFrames; f++) {
                writer.write(overlaidFrame);
            }

            // i. Write transition frames between clips (not after last clip)
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

        // 5. Release writer and return path
        writer.release();
        return outPath;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Computes text Y coordinate per report §3.2.4:
     * BOTTOM → 720 - 40
     * TOP    → ascent + 40
     * CENTER → vertical centre
     */
    private int computeTextY(String position, FontMetrics fm) {
        switch (position) {
            case "TOP":    return fm.getAscent() + 40;
            case "CENTER": return (FRAME_HEIGHT + fm.getAscent() - fm.getDescent()) / 2;
            case "BOTTOM":
            default:       return FRAME_HEIGHT - 40;
        }
    }

    /**
     * Letterbox-resizes src Mat to 1280x720, preserving aspect ratio.
     * Black bars fill empty space (letterbox/pillarbox).
     */
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

    /**
     * Writes transition frames between two clips.
     * FADE: A → black → B
     * CROSS: direct linear dissolve A → B
     */
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
            // CROSS dissolve
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