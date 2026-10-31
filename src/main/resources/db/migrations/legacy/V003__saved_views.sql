CREATE TABLE saved_view (
    id INTEGER PRIMARY KEY,
    name TEXT UNIQUE NOT NULL,
    filter_json TEXT NOT NULL,
    created_at INTEGER
);

ALTER TABLE segment ADD COLUMN view_id INTEGER REFERENCES saved_view(id) ON DELETE SET NULL;
