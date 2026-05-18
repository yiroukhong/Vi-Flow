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

public class FavouritesManager {

    private static final Path FAVOURITES_FILE = Paths.get("data/annotations/favourites.json");
    private static final Gson GSON = new Gson();
    private static final Type LIST_TYPE = new TypeToken<List<String>>(){}.getType();

    private static List<String> load() throws IOException {
        if (!Files.exists(FAVOURITES_FILE)) {
            return new ArrayList<>();
        }
        String json = Files.readString(FAVOURITES_FILE, StandardCharsets.UTF_8);
        List<String> list = GSON.fromJson(json, LIST_TYPE);
        return list != null ? new ArrayList<>(list) : new ArrayList<>();
    }

    private static void save(List<String> paths) throws IOException {
        Files.createDirectories(FAVOURITES_FILE.getParent());
        Files.writeString(FAVOURITES_FILE, GSON.toJson(paths), StandardCharsets.UTF_8);
    }

    // Add image to favourites list
    public static void addFavourite(String absolutePath) throws IOException {
        List<String> paths = load();
        if (!paths.contains(absolutePath)) {
            paths.add(absolutePath);
            save(paths);
        }
    }

    // Remove image from favourites list
    public static void removeFavourite(String absolutePath) throws IOException {
        List<String> paths = load();
        if (paths.remove(absolutePath)) {
            save(paths);
        }
    }

    // Check if image is currently a favourite (used for button toggle state in UI)
    public static boolean isFavourite(String absolutePath) throws IOException {
        if (!Files.exists(FAVOURITES_FILE)) {
            return false;
        }
        return load().contains(absolutePath);
    }

    // Get full ordered favourites list, sorted by file last-modified date (oldest first)
    public static List<String> getFavourites() throws IOException {
        List<String> paths = load();
        paths.sort(Comparator.comparingLong(p -> new File(p).lastModified()));
        return paths;
    }
}
