-- Create table to track equipped skins for offline/cracked accounts
CREATE TABLE equipped_offline_skins (
    minecraft_user_uuid TEXT PRIMARY KEY,
    texture_key TEXT NOT NULL,
    variant TEXT NOT NULL CHECK (variant IN ('CLASSIC', 'SLIM', 'UNKNOWN')),
    cape_id TEXT,
    FOREIGN KEY (minecraft_user_uuid) REFERENCES minecraft_users(uuid) ON DELETE CASCADE,
    FOREIGN KEY (texture_key) REFERENCES custom_minecraft_skin_textures(texture_key) ON DELETE CASCADE
);
