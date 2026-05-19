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
 */
public class VideoCompiler {

    private static final int    FRAME_WIDTH  = 1280;
    private static final int    FRAME_HEIGHT = 720;
    private static final double FPS          = 25.0;

    /**
     * Compiles a list of images into an AVI video with overlay text.
     *
     * @param imagePaths       Ordered list of image file paths (oldest-first, do NOT re-sort)
     * @param durationPerPhoto Duration in seconds each image is shown (must be > 0)
     * @param overlayText      Text drawn on every frame via Graphics2D
     * @param outputDir        Directory to save the output AVI file
     * @param transitionType   Transition between clips: "NONE", "FADE", or "CROSS"
     * @param textPosition     Position of overlay text: "TOP", "CENTER", or "BOTTOM"
     * @return Output file path of the saved AVI
     * @throws IOException              If an image cannot be loaded or the video cannot be saved
     * @throws IllegalArgumentException If any input parameter is invalid
     */
    public String compileVideo(
            List<String> imagePaths,
            int durationPerPhoto,
            String overlayText,
            String outputDir,
            String transitionType,
            String textPosition,
            int fps) throws IOException {

        // 1. Validate inputs
        if (imagePaths == null || imagePaths.isEmpty()) {
            throw new IllegalArgumentException("imagePaths must not be empty");
        }
        if (durationPerPhoto <= 0) {
            throw new IllegalArgumentException("durationPerPhoto must be > 0");
        }
        if (outputDir == null || outputDir.isBlank()) {
            throw new IllegalArgumentException("outputDir must not be null or blank");
        }

        // Normalise transitionType
        String transition = (transitionType == null) ? "NONE" : transitionType.toUpperCase();
        if (!transition.equals("FADE") && !transition.equals("CROSS")) {
            transition = "NONE";
        }

        // Normalise textPosition — default to BOTTOM per contract §6
        String position = (textPosition == null) ? "BOTTOM" : textPosition.toUpperCase();
        if (!position.equals("TOP") && !position.equals("CENTER")) {
            position = "BOTTOM";
        }

        // 2. Ensure output directory exists
        Files.createDirectories(Paths.get(outputDir));

        // 3. Set up VideoWriter — mp4v codec, MP4 container, 1280x720, 25fps
        String filename  = "viflow_output_"
                + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss")
                        .format(new java.util.Date())
                + ".mp4";
        String outPath   = outputDir + File.separator + filename;

        int totalFrames = durationPerPhoto * fps;
        // Transition frames: 12 per boundary, written (N-1) times
        int transFramesTotal = transition.equals("NONE") ? 0 : (imagePaths.size() - 1) * 12;
        VideoWriter writer    = new VideoWriter();
        int         fourcc    = VideoWriter.fourcc('m', 'p', '4', 'v');
        Size        frameSize = new Size(FRAME_WIDTH, FRAME_HEIGHT);

        writer.open(outPath, fourcc, (double) fps, frameSize, true);

        if (!writer.isOpened()) {
            throw new IOException("VideoWriter failed to open: " + outPath
                    + " — check mp4v codec is available on this machine");
        }

        // 4. Process each image
        // Reserve transition budget first, then distribute remainder evenly across clips
        int maxTotalFrames    = 990;
        int availableForClips = maxTotalFrames - transFramesTotal;
        int perPhotoFrames    = Math.max(1,
                Math.min(totalFrames, availableForClips / imagePaths.size()));

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

            // b. Letterbox resize — preserves aspect ratio, fills remainder with black
            //    Fixes portrait images being stretched into 1280x720 landscape frame
            Mat letterboxed = letterboxResize(frame);

            // c. Convert Mat → custom BufferedImage (Yirou's util)
            com.wig3003.photoapp.util.BufferedImage customBi =
                    ImageUtils.matToBufferedImage(letterboxed);

            // d. Bridge to standard java.awt.image.BufferedImage for Graphics2D
            BufferedImage javaBi = new BufferedImage(
                    customBi.getWidth(),
                    customBi.getHeight(),
                    BufferedImage.TYPE_3BYTE_BGR);

            byte[] srcData  = customBi.getData();
            byte[] destData = ((DataBufferByte) javaBi.getRaster().getDataBuffer()).getData();
            System.arraycopy(srcData, 0, destData, 0, srcData.length);

            // e. Draw overlay text via Graphics2D at the specified position
            if (overlayText != null && !overlayText.isBlank()) {
                Graphics2D g2d = javaBi.createGraphics();
                g2d.setRenderingHint(
                        RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                g2d.setFont(new Font("Arial", Font.BOLD, 32));
                g2d.setColor(Color.WHITE);

                FontMetrics fm    = g2d.getFontMetrics();
                int         textX = (FRAME_WIDTH - fm.stringWidth(overlayText)) / 2;
                int         textY = computeTextY(position, fm);
                g2d.drawString(overlayText, textX, textY);
                g2d.dispose();
            }

            // f. Bridge back: standard BufferedImage → custom BufferedImage
            byte[] modifiedData = ((DataBufferByte) javaBi.getRaster().getDataBuffer()).getData();
            com.wig3003.photoapp.util.BufferedImage modifiedCustomBi =
                    new com.wig3003.photoapp.util.BufferedImage(
                            javaBi.getWidth(),
                            javaBi.getHeight(),
                            customBi.getChannels(),
                            modifiedData);

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
     * Computes the Y coordinate for overlay text based on position.
     * TOP    → near top of frame (font height + 40px margin)
     * CENTER → vertical centre of frame
     * BOTTOM → near bottom of frame (frame height - 40px margin)
     */
    private int computeTextY(String position, FontMetrics fm) {
        switch (position) {
            case "TOP":
                return fm.getAscent() + 40;
            case "CENTER":
                return (FRAME_HEIGHT + fm.getAscent() - fm.getDescent()) / 2;
            case "BOTTOM":
            default:
                return FRAME_HEIGHT - 40;
        }
    }

    /**
     * Resizes a Mat into a 1280x720 frame while preserving aspect ratio.
     * Empty space is filled with black (letterbox for landscape,
     * pillarbox for portrait images).
     */
    private Mat letterboxResize(Mat src) {
        Mat canvas = Mat.zeros(FRAME_HEIGHT, FRAME_WIDTH, src.type());

        double scaleW = (double) FRAME_WIDTH  / src.cols();
        double scaleH = (double) FRAME_HEIGHT / src.rows();
        double scale  = Math.min(scaleW, scaleH); // fit inside, no cropping

        int scaledW = (int) (src.cols() * scale);
        int scaledH = (int) (src.rows() * scale);

        // Centre the scaled image on the black canvas
        int offsetX = (FRAME_WIDTH  - scaledW) / 2;
        int offsetY = (FRAME_HEIGHT - scaledH) / 2;

        Mat resized = new Mat();
        Imgproc.resize(src, resized, new Size(scaledW, scaledH));

        Rect roi = new Rect(offsetX, offsetY, scaledW, scaledH);
        resized.copyTo(canvas.submat(roi));

        resized.release();
        return canvas;
    }

    /**
     * Writes transition frames between two clips.
     * FADE: A → black → B (two-phase, 6 frames each).
     * CROSS: direct linear dissolve A → B (12 frames).
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
            // CROSS: direct linear dissolve from A to B
            for (int t = 0; t < transFrames; t++) {
                double alpha = (double) t / (transFrames - 1);
                double beta  = 1.0 - alpha;
                Mat blended  = new Mat();
                Core.addWeighted(frameA, beta, frameB, alpha, 0, blended);
                writer.write(blended);
                blended.release();
            }
        }
    }
}