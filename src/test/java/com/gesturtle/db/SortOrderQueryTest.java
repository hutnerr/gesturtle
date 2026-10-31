package com.gesturtle.db;

import com.gesturtle.db.dao.ImageDao;
import com.gesturtle.filter.Filter;
import com.gesturtle.filter.FilterCodec;
import com.gesturtle.model.ImageRecord;
import com.gesturtle.model.SortOrder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** The Gallery's sort orders through real SQLite, and the cleanup of the short-lived rating fields. */
class SortOrderQueryTest {
    private Database database;
    private ImageDao imageDao;
    private int delta, alpha, charlie, bravo;

    @AfterEach
    void tearDown() {
        database.close();
    }

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        database = new Database(tempDir.resolve("test.db"));
        imageDao = new ImageDao(database.connection());
        long now = System.currentTimeMillis();
        delta = imageDao.insert("h1", "png", "delta.png", 10, 10, 300, now - 4000, null);
        alpha = imageDao.insert("h2", "png", "alpha.png", 10, 10, 100, now - 3000, null);
        charlie = imageDao.insert("h3", "png", "Charlie.png", 10, 10, 400, now - 2000, null);
        bravo = imageDao.insert("h4", "png", "bravo.png", 10, 10, 200, now - 1000, null);
        imageDao.recordView(alpha, null, now, 1000);
        imageDao.recordView(alpha, null, now + 1, 1000);
        imageDao.recordView(charlie, null, now - 500, 1000);
    }

    private List<Integer> order(SortOrder sort) {
        return imageDao.query(new Filter.All(), sort, null).stream().map(ImageRecord::id).toList();
    }

    @Test
    void sortOrders() {
        assertEquals(List.of(bravo, charlie, alpha, delta), order(SortOrder.NEWEST));
        assertEquals(List.of(delta, alpha, charlie, bravo), order(SortOrder.OLDEST));
        assertEquals(List.of(alpha, bravo, charlie, delta), order(SortOrder.NAME)); // case-insensitive
        assertEquals(List.of(delta, charlie, bravo, alpha), order(SortOrder.NAME_DESC));
        assertEquals(alpha, order(SortOrder.MOST_PRACTICED).get(0));
        assertEquals(List.of(alpha, charlie), order(SortOrder.RECENTLY_PRACTICED).subList(0, 2));
        // Never-practiced images come first when looking for the least recently practiced.
        assertEquals(Set.of(delta, bravo), Set.copyOf(order(SortOrder.LEAST_RECENTLY_PRACTICED).subList(0, 2)));
        assertEquals(charlie, order(SortOrder.LARGEST).get(0));
        assertEquals(4, order(SortOrder.RANDOM).size());
    }

    @Test
    void aSavedRatingSortFallsBackToNewest() {
        assertEquals(SortOrder.NEWEST, SortOrder.parse("RATING"));
    }

    @Test
    void ratingColumnsAreGone() throws SQLException {
        List<String> columns = new ArrayList<>();
        try (ResultSet rs = database.connection().createStatement().executeQuery("PRAGMA table_info(image)")) {
            while (rs.next()) columns.add(rs.getString("name"));
        }
        assertFalse(columns.contains("rating"));
        assertFalse(columns.contains("difficulty"));
        assertFalse(columns.contains("review_later"));
    }

    @Test
    void filtersSavedWithRatingConditionsStillLoad() {
        String json = "{\"version\":1,\"filter\":{\"type\":\"and\",\"children\":["
                + "{\"type\":\"rating\",\"comparison\":\"GE\",\"stars\":3},{\"type\":\"favorite\"}]}}";
        assertEquals(new Filter.And(List.of(new Filter.All(), new Filter.FavoritePredicate())), FilterCodec.decode(json));
    }
}
