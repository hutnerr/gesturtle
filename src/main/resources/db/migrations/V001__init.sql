CREATE TABLE image (
    id INTEGER PRIMARY KEY,
    hash TEXT UNIQUE NOT NULL,
    ext TEXT NOT NULL,
    original_name TEXT,
    width INTEGER,
    height INTEGER,
    byte_size INTEGER,
    imported_at INTEGER NOT NULL,
    source_path TEXT,
    favorite INTEGER NOT NULL DEFAULT 0,
    last_seen_at INTEGER,
    seen_count INTEGER NOT NULL DEFAULT 0,
    deleted_at INTEGER,
    credit TEXT,
    notes TEXT
);

-- uid (here and on playlist, saved_view, and preset) is a key that stays the same on every machine, so
-- the meta/ files (the part of a library git tracks) can refer to the row. Images have one already: their
-- hash. New rows get theirs from the triggers MetaStore installs.
CREATE TABLE tag (
    id INTEGER PRIMARY KEY,
    name TEXT NOT NULL,
    parent_id INTEGER REFERENCES tag(id),
    created_at INTEGER,
    group_only INTEGER NOT NULL DEFAULT 0,
    exclusive_children INTEGER NOT NULL DEFAULT 0,
    color TEXT,
    description TEXT,
    uid TEXT,
    UNIQUE(parent_id, name)
);

CREATE TABLE image_tag (
    image_id INTEGER REFERENCES image(id) ON DELETE CASCADE,
    tag_id INTEGER REFERENCES tag(id) ON DELETE CASCADE,
    PRIMARY KEY (image_id, tag_id)
);

CREATE TABLE playlist (
    id INTEGER PRIMARY KEY,
    name TEXT UNIQUE NOT NULL,
    created_at INTEGER,
    cover_image_id INTEGER REFERENCES image(id) ON DELETE SET NULL,
    uid TEXT
);

CREATE TABLE playlist_item (
    playlist_id INTEGER REFERENCES playlist(id) ON DELETE CASCADE,
    image_id INTEGER REFERENCES image(id) ON DELETE CASCADE,
    position INTEGER,
    PRIMARY KEY (playlist_id, image_id)
);

CREATE TABLE preset (
    id INTEGER PRIMARY KEY,
    name TEXT NOT NULL,
    created_at INTEGER,
    last_used_at INTEGER,
    use_count INTEGER DEFAULT 0,
    pinned INTEGER DEFAULT 0,
    uid TEXT
);

CREATE TABLE saved_view (
    id INTEGER PRIMARY KEY,
    name TEXT UNIQUE NOT NULL,
    filter_json TEXT NOT NULL,
    created_at INTEGER,
    uid TEXT
);

CREATE TABLE segment (
    id INTEGER PRIMARY KEY,
    preset_id INTEGER REFERENCES preset(id) ON DELETE CASCADE,
    position INTEGER,
    source_kind TEXT,
    filter_json TEXT,
    playlist_id INTEGER REFERENCES playlist(id),
    item_count INTEGER,
    order_mode TEXT,
    seconds_per_image INTEGER,
    tick_behavior TEXT,
    break_seconds INTEGER,
    memory_show_seconds INTEGER,
    memory_hide_seconds INTEGER,
    ramp_to_seconds INTEGER,
    view_id INTEGER REFERENCES saved_view(id) ON DELETE SET NULL
);

CREATE TABLE practice_session (
    id INTEGER PRIMARY KEY,
    preset_id INTEGER REFERENCES preset(id) ON DELETE SET NULL,
    started_at INTEGER,
    ended_at INTEGER,
    images_shown INTEGER
);

CREATE TABLE view_event (
    id INTEGER PRIMARY KEY,
    image_id INTEGER REFERENCES image(id) ON DELETE CASCADE,
    session_id INTEGER REFERENCES practice_session(id) ON DELETE SET NULL,
    seen_at INTEGER,
    shown_ms INTEGER
);

CREATE TABLE setting (
    key TEXT PRIMARY KEY,
    value TEXT
);

-- Things changed since their meta/ file was last written: kind is image, tag, playlist, view, preset, or
-- settings, and key is the hash or uid ('' for settings). Repeats are fine (no key): inside a trigger,
-- the triggering statement's conflict handling would override an OR IGNORE here.
CREATE TABLE meta_dirty (
    kind TEXT NOT NULL,
    key TEXT NOT NULL
);

-- Each meta/ file as last read or written, so opening the library only re-reads files that changed.
CREATE TABLE meta_file (
    path TEXT PRIMARY KEY,
    size INTEGER NOT NULL,
    modified INTEGER NOT NULL
);

CREATE INDEX idx_image_favorite ON image(favorite);
CREATE INDEX idx_image_imported_at ON image(imported_at);
CREATE INDEX idx_image_last_seen_at ON image(last_seen_at);
CREATE INDEX idx_image_deleted_at ON image(deleted_at);
CREATE INDEX idx_image_tag_tag_id ON image_tag(tag_id);
CREATE INDEX idx_tag_parent_id ON tag(parent_id);
CREATE INDEX idx_view_event_image_id ON view_event(image_id);
CREATE UNIQUE INDEX idx_tag_uid ON tag(uid);
CREATE UNIQUE INDEX idx_playlist_uid ON playlist(uid);
CREATE UNIQUE INDEX idx_saved_view_uid ON saved_view(uid);
CREATE UNIQUE INDEX idx_preset_uid ON preset(uid);
