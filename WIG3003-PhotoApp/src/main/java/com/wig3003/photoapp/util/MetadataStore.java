package com.wig3003.photoapp.util;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

public class MetadataStore {

    private static final Path ANNOTATIONS_FILE =
        Paths.get("data/annotations/annotations.json");

    private static final Gson GSON = new Gson();

    // ------------------------------------------------------------------ //
    //  Public API                                                          //
    // ------------------------------------------------------------------ //

    public static void saveAnnotation(String absolutePath, String annotation) {
        if (annotation == null)
            throw new IllegalArgumentException("annotation must not be null");

        Map<String, String> map = loadAnnotations();
        map.put(absolutePath, annotation);
        writeAnnotations(map);
    }

    public static String getAnnotation(String absolutePath) {
        Map<String, String> map = loadAnnotations();
        return map.getOrDefault(absolutePath, null);
    }

    public static void deleteAnnotation(String absolutePath) {
        Map<String, String> map = loadAnnotations();
        map.remove(absolutePath);
        writeAnnotations(map);
    }

    public static boolean hasAnnotation(String absolutePath) {
        if (!Files.exists(ANNOTATIONS_FILE)) return false;
        Map<String, String> map = loadAnnotations();
        return map.containsKey(absolutePath);
    }

    // ------------------------------------------------------------------ //
    //  Private helpers                                                     //
    // ------------------------------------------------------------------ //

    private static Map<String, String> loadAnnotations() {
        if (!Files.exists(ANNOTATIONS_FILE)) return new HashMap<>();
        try {
            String json = Files.readString(ANNOTATIONS_FILE, StandardCharsets.UTF_8);
            Type type = new TypeToken<Map<String, String>>() {}.getType();
            Map<String, String> map = GSON.fromJson(json, type);
            return map != null ? map : new HashMap<>();
        } catch (IOException e) {
            return new HashMap<>();
        }
    }

    private static void writeAnnotations(Map<String, String> map) {
        try {
            Files.createDirectories(ANNOTATIONS_FILE.getParent());
            String json = GSON.toJson(map);
            Files.writeString(ANNOTATIONS_FILE, json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write annotations.json", e);
        }
    }
}

