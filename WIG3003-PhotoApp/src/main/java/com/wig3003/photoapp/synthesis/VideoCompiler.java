package com.wig3003.photoapp.synthesis;

import com.wig3003.photoapp.util.ImageUtils;

import org.opencv.core.Mat;
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
     * @param overlayText      Text drawn at bottom-center of every frame via Graphics2D
     * @param outputDir        Directory to save the output AVI file
     * @param transitionType   Transition between clips: "NONE", "FADE", or "CROSS"
     * @return Output file path of the saved AVI
     * @throws IOException              If an image cannot be loaded or the video cannot be saved
     * @throws IllegalArgumentException If any input parameter is invalid
     */
    public String compileVideo(
            List<String> imagePaths,
            int durationPerPhoto,
            String overlayText,
            String outputDir,
            String transitionType) throws IOException {

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

        // Normalise transitionType — default to NONE if null or unrecognised
        String transition = (transitionType == null) ? "NONE" : transitionType.toUpperCase();
        if (!transition.equals("FADE") && !transition.equals("CROSS")) {
            transition = "NONE";
        }

        // 2. Ensure output directory exists
        Files.createDirectories(Paths.get(outputDir));

        // 3. Set up VideoWriter — XVID codec, AVI container, 1280x720, 25fps
        String filename  = "video_" + System.currentTimeMillis() + ".avi";
        String outPath   = outputDir + File.separator + filename;

        VideoWriter writer    = new VideoWriter();
        int fourcc = VideoWriter.fourcc('M', 'J', 'P', 'G');
        Size        frameSize = new Size(FRAME_WIDTH, FRAME_HEIGHT);

        writer.open(outPath, fourcc, FPS, frameSize, true);

        if (!writer.isOpened()) {
            throw new IOException("VideoWriter failed to open: " + outPath
                    + " — check XVID codec is available on this machine");
        }

        // 4. Process each image
        int totalFramesPerClip = durationPerPhoto * (int) FPS; // e.g. 2s * 25fps = 50 frames

        for (int i = 0; i < imagePaths.size(); i++) {
            String imgPath = imagePaths.get(i);

            if (imgPath == null || imgPath.isBlank()) {
                System.err.println("Warning: skipping null/blank image path");
                continue;
            }

            // a. Load and resize to 1280x720
            Mat frame = ImageUtils.loadMatFromPath(imgPath);
            if (frame == null || frame.empty()) {
                System.err.println("Warning: could not load image, skipping: " + imgPath);
                continue;
            }
            Imgproc.resize(frame, frame, frameSize);

            // b. Convert Mat → custom BufferedImage (Yirou's util)
            com.wig3003.photoapp.util.BufferedImage customBi =
                    ImageUtils.matToBufferedImage(frame);

            // c. Bridge to standard java.awt.image.BufferedImage for Graphics2D
            BufferedImage javaBi = new BufferedImage(
                    customBi.getWidth(),
                    customBi.getHeight(),
                    BufferedImage.TYPE_3BYTE_BGR);

            byte[] srcData  = customBi.getData(); // defensive copy per Yirou's impl
            byte[] destData = ((DataBufferByte) javaBi.getRaster().getDataBuffer()).getData();
            System.arraycopy(srcData, 0, destData, 0, srcData.length);

            // d. Draw overlay text via Graphics2D — NOT Imgproc.putText() per contract §6
            if (overlayText != null && !overlayText.isBlank()) {
                Graphics2D g2d = javaBi.createGraphics();
                g2d.setRenderingHint(
                        RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                g2d.setFont(new Font("Arial", Font.BOLD, 32));
                g2d.setColor(Color.WHITE);

                FontMetrics fm    = g2d.getFontMetrics();
                int         textX = (FRAME_WIDTH - fm.stringWidth(overlayText)) / 2;
                int         textY = FRAME_HEIGHT - 40; // bottom-center per contract §6
                g2d.drawString(overlayText, textX, textY);
                g2d.dispose();
            }

            // e. Bridge back: standard BufferedImage → custom BufferedImage
            byte[] modifiedData = ((DataBufferByte) javaBi.getRaster().getDataBuffer()).getData();
            com.wig3003.photoapp.util.BufferedImage modifiedCustomBi =
                    new com.wig3003.photoapp.util.BufferedImage(
                            javaBi.getWidth(),
                            javaBi.getHeight(),
                            customBi.getChannels(),
                            modifiedData);

            // f. Convert back to Mat
            Mat overlaidFrame = ImageUtils.bufferedImageToMat(modifiedCustomBi);

            // g. Write main clip frames
            for (int f = 0; f < totalFramesPerClip; f++) {
                writer.write(overlaidFrame);
            }

            // h. Write transition frames between clips (not after the last clip)
            if (!transition.equals("NONE") && i < imagePaths.size() - 1) {
                String nextPath = imagePaths.get(i + 1);
                Mat    nextFrame = ImageUtils.loadMatFromPath(nextPath);

                if (nextFrame != null && !nextFrame.empty()) {
                    Imgproc.resize(nextFrame, nextFrame, frameSize);
                    writeTransitionFrames(writer, overlaidFrame, nextFrame, transition);
                    nextFrame.release();
                }
            }

            overlaidFrame.release();
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
     * Writes transition frames between two clips.
     * FADE: blends from frameA to frameB over 12 frames (~0.5s at 25fps).
     * CROSS: same as FADE — cross-dissolve approximated by linear blend.
     */
    private void writeTransitionFrames(VideoWriter writer, Mat frameA, Mat frameB,
                                       String transition) {
        int transFrames = 12; // ~0.5 seconds

        for (int t = 0; t < transFrames; t++) {
            double alpha = (double) t / transFrames; // 0.0 → 1.0
            double beta  = 1.0 - alpha;

            Mat blended = new Mat();
            org.opencv.core.Core.addWeighted(frameA, beta, frameB, alpha, 0, blended);
            writer.write(blended);
            blended.release();
        }
    }
}