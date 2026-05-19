package com.wig3003.photoapp.util;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class FavouritesManager {

    private static final Path FAVOURITES_FILE = Paths.get("data/annotations/favourites.json");
    private static final Gson GSON = new Gson();
    private static final Type LIST_TYPE = new TypeToken<List<String>>(){}.getType();

    // In-memory cache to prevent constant disk I/O and UI flickering
    private static List<String> cachedFavourites = null;

    private static List<String> load() {
        if (cachedFavourites != null) {
            return cachedFavourites;
        }

        if (!Files.exists(FAVOURITES_FILE)) {
            cachedFavourites = new CopyOnWriteArrayList<>();
            return cachedFavourites;
        }

        try {
            String json = Files.readString(FAVOURITES_FILE, StandardCharsets.UTF_8);
            List<String> list = GSON.fromJson(json, LIST_TYPE);
            cachedFavourites = list != null ? new CopyOnWriteArrayList<>(list) : new CopyOnWriteArrayList<>();
        } catch (IOException e) {
            e.printStackTrace();
            cachedFavourites = new CopyOnWriteArrayList<>();
        }
        
        return cachedFavourites;
    }

    private static void save(List<String> paths) {
        // Run file writing in a background thread to keep UI smooth
        new Thread(() -> {
            try {
                Files.createDirectories(FAVOURITES_FILE.getParent());
                Files.writeString(FAVOURITES_FILE, GSON.toJson(paths), StandardCharsets.UTF_8);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }).start();
    }

    public static void addFavourite(String absolutePath) {
        List<String> paths = load();
        if (!paths.contains(absolutePath)) {
            paths.add(absolutePath);
            save(paths);
        }
    }

    // Add multiple images to favourites list
    public static void addFavourites(List<String> absolutePaths) {
        List<String> paths = load();
        boolean changed = false;
        for (String path : absolutePaths) {
            if (!paths.contains(path)) {
                paths.add(path);
                changed = true;
            }
        }
        if (changed) {
            save(paths);
        }
    }

    // Remove image from favourites list
    public static void removeFavourite(String absolutePath) {
        List<String> paths = load();
        if (paths.remove(absolutePath)) {
            save(paths);
        }
    }

    // Remove multiple images from favourites list
    public static void removeFavourites(List<String> absolutePaths) {
        List<String> paths = load();
        boolean changed = false;
        for (String path : absolutePaths) {
            if (paths.remove(path)) {
                changed = true;
            }
        }
        if (changed) {
            save(paths);
        }
    }

    // Check if image is currently a favourite (used for button toggle state in UI)
    public static boolean isFavourite(String absolutePath) {
        return load().contains(absolutePath);
    }

    // Get full ordered favourites list, sorted by file last-modified date (oldest first)
    public static List<String> getFavourites() {
        List<String> paths = new ArrayList<>(load());
        paths.sort(Comparator.comparingLong(p -> new File(p).lastModified()));
        return paths;
    }
}