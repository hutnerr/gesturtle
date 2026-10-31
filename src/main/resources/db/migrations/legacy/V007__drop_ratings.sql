DROP INDEX IF EXISTS idx_image_rating;
ALTER TABLE image DROP COLUMN rating;
ALTER TABLE image DROP COLUMN difficulty;
ALTER TABLE image DROP COLUMN review_later;
