package com.wig3003.photoapp.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FavouritesManagerTest {

    private static final Path FAVOURITES_FILE = Paths.get("data/annotations/favourites.json");

    @BeforeEach
    void setUp() throws IOException {
        Files.deleteIfExists(FAVOURITES_FILE);
    }

    @AfterEach
    void tearDown() throws IOException {
        Files.deleteIfExists(FAVOURITES_FILE);
    }

    // --- addFavourite ---

    @Test
    void addFavourite_createsFileOnFirstAdd() throws IOException {
        FavouritesManager.addFavourite("/photos/a.jpg");
        assertTrue(Files.exists(FAVOURITES_FILE));
    }

    @Test
    void addFavourite_duplicatePath_isNoOp() throws IOException {
        FavouritesManager.addFavourite("/photos/a.jpg");
        FavouritesManager.addFavourite("/photos/a.jpg");
        assertEquals(1, FavouritesManager.getFavourites().size());
    }

    @Test
    void addFavourite_multipleDistinctPaths_allStored() throws IOException {
        FavouritesManager.addFavourite("/photos/a.jpg");
        FavouritesManager.addFavourite("/photos/b.jpg");
        assertEquals(2, FavouritesManager.getFavourites().size());
    }

    // --- removeFavourite ---

    @Test
    void removeFavourite_existingPath_isRemoved() throws IOException {
        FavouritesManager.addFavourite("/photos/a.jpg");
        FavouritesManager.removeFavourite("/photos/a.jpg");
        assertFalse(FavouritesManager.isFavourite("/photos/a.jpg"));
    }

    @Test
    void removeFavourite_absentPath_doesNotThrow() throws IOException {
        FavouritesManager.addFavourite("/photos/a.jpg");
        assertDoesNotThrow(() -> FavouritesManager.removeFavourite("/photos/notPresent.jpg"));
    }

    @Test
    void removeFavourite_leavesOtherPathsIntact() throws IOException {
        FavouritesManager.addFavourite("/photos/a.jpg");
        FavouritesManager.addFavourite("/photos/b.jpg");
        FavouritesManager.removeFavourite("/photos/a.jpg");
        assertTrue(FavouritesManager.isFavourite("/photos/b.jpg"));
    }

    // --- isFavourite ---

    @Test
    void isFavourite_returnsFalse_whenFavouritesFileAbsent() throws IOException {
        assertFalse(FavouritesManager.isFavourite("/photos/a.jpg"));
    }

    @Test
    void isFavourite_returnsTrue_forAddedPath() throws IOException {
        FavouritesManager.addFavourite("/photos/a.jpg");
        assertTrue(FavouritesManager.isFavourite("/photos/a.jpg"));
    }

    @Test
    void isFavourite_returnsFalse_forUnaddedPath() throws IOException {
        FavouritesManager.addFavourite("/photos/a.jpg");
        assertFalse(FavouritesManager.isFavourite("/photos/b.jpg"));
    }

    // --- getFavourites ---

    @Test
    void getFavourites_returnsEmptyList_whenFavouritesFileAbsent() throws IOException {
        assertTrue(FavouritesManager.getFavourites().isEmpty());
    }

    @Test
    void getFavourites_sortedOldestFirst(@TempDir Path tempDir) throws IOException {
        Path older = tempDir.resolve("older.jpg");
        Path newer = tempDir.resolve("newer.jpg");
        Files.createFile(older);
        Files.createFile(newer);
        older.toFile().setLastModified(1_000L);
        newer.toFile().setLastModified(2_000L);

        // Add newer first — sort must be by last-modified, not insertion order
        FavouritesManager.addFavourite(newer.toString());
        FavouritesManager.addFavourite(older.toString());

        List<String> favs = FavouritesManager.getFavourites();
        assertEquals(older.toString(), favs.get(0), "oldest file should be first");
        assertEquals(newer.toString(), favs.get(1), "newest file should be last");
    }
}
