package com.gesturtle.db;

import com.gesturtle.db.dao.ImageDao;
import com.gesturtle.db.dao.TagDao;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TagDaoTest {
    private Database database;
    private TagDao tagDao;
    private ImageDao imageDao;

    @AfterEach
    void tearDown() {
        database.close();
    }

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        database = new Database(tempDir.resolve("test.db"));
        tagDao = new TagDao(database.connection());
        imageDao = new ImageDao(database.connection());
    }

    @Test
    void reparentingATagUnderItsOwnDescendantIsRejected() {
        int animal = tagDao.create("animal", null);
        int dog = tagDao.create("dog", animal);
        int husky = tagDao.create("husky", dog);

        assertThrows(IllegalStateException.class, () -> tagDao.reparent(animal, husky));
    }

    @Test
    void reparentingATagUnderItselfIsRejected() {
        int animal = tagDao.create("animal", null);
        assertThrows(IllegalStateException.class, () -> tagDao.reparent(animal, animal));
    }

    @Test
    void duplicateSiblingNamesAreRejected() {
        int animal = tagDao.create("animal", null);
        tagDao.create("dog", animal);
        assertThrows(IllegalStateException.class, () -> tagDao.create("dog", animal));
    }

    @Test
    void deletingATagReassignsChildrenToItsParent() {
        int animal = tagDao.create("animal", null);
        int dog = tagDao.create("dog", animal);
        int husky = tagDao.create("husky", dog);

        tagDao.deleteReassignChildren(dog);

        assertEquals(animal, tagDao.get(husky).orElseThrow().parentId());
    }

    @Test
    void groupOnlyAndExclusiveChildrenAreStoredPerTag() {
        int view = tagDao.create("view", null);
        int front = tagDao.create("front", view);
        assertFalse(tagDao.get(view).orElseThrow().groupOnly());

        tagDao.setGroupOnly(view, true);
        tagDao.setExclusiveChildren(view, true);

        assertTrue(tagDao.get(view).orElseThrow().groupOnly());
        assertTrue(tagDao.get(view).orElseThrow().exclusiveChildren());
        assertFalse(tagDao.get(front).orElseThrow().groupOnly());
    }

    @Test
    void addingAChildOfAnExclusiveParentReplacesItsSibling() {
        int view = tagDao.create("view", null);
        int front = tagDao.create("front", view);
        int back = tagDao.create("back", view);
        int hands = tagDao.create("hands", null);
        tagDao.setExclusiveChildren(view, true);
        int image = imageDao.insert("h1", "png", "a.png", 10, 10, 10, 0L, null);

        imageDao.addTags(image, List.of(front, hands));
        imageDao.addTags(image, List.of(back));

        assertEquals(Set.of(back, hands), Set.copyOf(imageDao.findById(image).orElseThrow().tagIds()));
    }

    @Test
    void whenTwoExclusiveSiblingsAreAddedTogetherTheLaterWins() {
        int view = tagDao.create("view", null);
        int front = tagDao.create("front", view);
        int back = tagDao.create("back", view);
        tagDao.setExclusiveChildren(view, true);
        int image = imageDao.insert("h1", "png", "a.png", 10, 10, 10, 0L, null);

        imageDao.addTags(image, List.of(front, back));

        assertEquals(List.of(back), imageDao.findById(image).orElseThrow().tagIds());
    }

    @Test
    void siblingsOfANonExclusiveParentAccumulate() {
        int figure = tagDao.create("figure", null);
        int hands = tagDao.create("hands", figure);
        int feet = tagDao.create("feet", figure);
        int image = imageDao.insert("h1", "png", "a.png", 10, 10, 10, 0L, null);

        imageDao.addTags(image, List.of(hands));
        imageDao.addTags(image, List.of(feet));

        assertEquals(Set.of(hands, feet), Set.copyOf(imageDao.findById(image).orElseThrow().tagIds()));
    }
}
