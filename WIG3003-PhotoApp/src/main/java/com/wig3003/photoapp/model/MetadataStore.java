package com.wig3003.photoapp.model;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
// CW added
import java.util.ArrayList;
import java.util.List;
// CW added-end

public class MetadataStore {

    private static final MetadataStore INSTANCE = new MetadataStore();

    private final Map<String, String> annotations = new HashMap<>();
    private final Path storageFile;

    
    // CW: remembered app library image paths, including imported folder images
    private final Map<String, String> libraryImages = new HashMap<>();
    // CW: change end
    // CW: library storage folder
    private final Path libraryDir;
    // CW: change end


    // CW modified
    private MetadataStore() {
        // CW: shared app folder for annotations and library images
        Path appDir = Paths.get(System.getProperty("user.home"), ".viflow");

        storageFile = appDir.resolve("annotations.properties");

        // CW: store saved library images under ~/.viflow/library
        libraryDir = appDir.resolve("library");

        load();
    }
    // CW change end


    public static MetadataStore getInstance() {
        return INSTANCE;
    }

    // ── Annotation CRUD ───────────────────────────────────────────────────────

    // CW: added shared library directory accessor
    public Path getLibraryDirectory() {
        try {
            Files.createDirectories(libraryDir);
        } catch (IOException ignored) {
            // non-fatal
        }
        return libraryDir;
    }
    // CW: change end

    // CW: returns imported folder images plus images saved into ~/.viflow/library
    public List<String> getLibraryPaths() {
        List<String> paths = new ArrayList<>();

        for (String path : libraryImages.keySet()) {
            if (Files.exists(Paths.get(path))) {
                paths.add(path);
            }
        }

        Path dir = getLibraryDirectory();

        try (var stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> isImageFile(path.getFileName().toString()))
                    .map(path -> path.toAbsolutePath().toString())
                    .forEach(path -> {
                        if (!paths.contains(path)) {
                            paths.add(path);
                        }
                    });
        } catch (IOException ignored) {
        }

        paths.sort(String::compareToIgnoreCase);
        return paths;
    }
    // CW: change end


    // CW: remember an image path as part of the app library
    public void saveLibraryImagePath(String path) {
        if (path == null || path.isBlank()) return;
        libraryImages.put(path, path);
        persist();
    }
    // CW: change end

    // CW: remember many imported image paths as part of the app library
    public void saveLibraryImagePaths(Collection<String> paths) {
        if (paths == null) return;
        for (String path : paths) {
            if (path != null && !path.isBlank()) {
                libraryImages.put(path, path);
            }
        }
        persist();
    }
    // CW: change end

    // CW: added helper for unique output filenames in the library
    public Path createLibraryImagePath(String prefix, String extension) {
        String cleanPrefix = prefix == null || prefix.isBlank() ? "image" : prefix;
        String cleanExt = extension == null || extension.isBlank() ? ".png" : extension;
        if (!cleanExt.startsWith(".")) cleanExt = "." + cleanExt;

        String filename = cleanPrefix + "_" + System.currentTimeMillis() + cleanExt;
        return getLibraryDirectory().resolve(filename);
    }
    // CW: change end

    // CW: added shared image extension check
    private boolean isImageFile(String name) {
        String lower = name.toLowerCase();
        return lower.endsWith(".png")
                || lower.endsWith(".jpg")
                || lower.endsWith(".jpeg")
                || lower.endsWith(".bmp")
                || lower.endsWith(".webp")
                || lower.endsWith(".tif")
                || lower.endsWith(".tiff");
    }
    // CW: change end
    
    public static void saveAnnotation(String absolutePath, String annotation) {
    if (annotation == null)
        throw new IllegalArgumentException("annotation must not be null");
    INSTANCE.annotations.put(absolutePath, annotation);
    INSTANCE.persist();
}

public static String getAnnotation(String absolutePath) {
    return INSTANCE.annotations.getOrDefault(absolutePath, null);
}

public static void deleteAnnotation(String absolutePath) {
    INSTANCE.annotations.remove(absolutePath);
    INSTANCE.persist();
}

public static boolean hasAnnotation(String absolutePath) {
    return INSTANCE.annotations.containsKey(absolutePath);
}
    /** Count of paths in the given collection that have annotations. */
    public int annotationCount(Collection<String> paths) {
        int n = 0;
        for (String p : paths) {
            if (annotations.containsKey(p)) n++;
        }
        return n;
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    private void load() {
        if (!Files.exists(storageFile)) return;
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(storageFile)) {
            props.load(in);
        } catch (IOException e) {
            return;
        }
        //CW change
        for (String key : props.stringPropertyNames()) {
            String value = props.getProperty(key);

            if (key.startsWith("annotation.")) {
                annotations.put(key.substring("annotation.".length()), value);
            } else if (key.startsWith("library.")) {
                libraryImages.put(value, value);
            } else {
                // CW: support old annotation format
                annotations.put(key, value);
            }
        }
        // CW change end 
    }

    private void persist() {
        try {
            Files.createDirectories(storageFile.getParent());
            Properties props = new Properties();
            // CW
            annotations.forEach((path, text) ->
                    props.setProperty("annotation." + path, text));

            int i = 0;
            for (String path : libraryImages.keySet()) {
                props.setProperty("library." + i, path);
                i++;
            }
            //CW
            try (OutputStream out = Files.newOutputStream(storageFile)) {
                props.store(out, "Vi-Flow annotations");
            }
        } catch (IOException ignored) {
            // non-fatal — annotations remain in memory for the session
        }
    }
}
