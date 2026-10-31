-- Keys that stay the same on every machine, so the meta/ files (the part of a library git tracks) can
-- refer to tags, playlists, views, and presets. Images already have one: their hash. New rows get theirs
-- from the triggers MetaStore installs.
ALTER TABLE tag ADD COLUMN uid TEXT;
ALTER TABLE playlist ADD COLUMN uid TEXT;
ALTER TABLE saved_view ADD COLUMN uid TEXT;
ALTER TABLE preset ADD COLUMN uid TEXT;
UPDATE tag SET uid = lower(hex(randomblob(16)));
UPDATE playlist SET uid = lower(hex(randomblob(16)));
UPDATE saved_view SET uid = lower(hex(randomblob(16)));
UPDATE preset SET uid = lower(hex(randomblob(16)));
CREATE UNIQUE INDEX idx_tag_uid ON tag(uid);
CREATE UNIQUE INDEX idx_playlist_uid ON playlist(uid);
CREATE UNIQUE INDEX idx_saved_view_uid ON saved_view(uid);
CREATE UNIQUE INDEX idx_preset_uid ON preset(uid);

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
