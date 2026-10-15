-- Older attempts retain their existing fixed playback paths through a null reference.
ALTER TABLE processing_attempts ADD COLUMN playlist_key VARCHAR(1024);
