-- 20260406120000_fix_equipped_offline_skins_fk.sql
-- Remove FOREIGN KEY (texture_key) from equipped_offline_skins

CREATE TABLE equipped_offline_skins_new (
    minecraft_user_uuid TEXT PRIMARY KEY,
    texture_key TEXT NOT NULL,
    variant TEXT NOT NULL CHECK (variant IN ('CLASSIC', 'SLIM', 'UNKNOWN')),
    cape_id TEXT,
    FOREIGN KEY (minecraft_user_uuid) REFERENCES minecraft_users(uuid) ON DELETE CASCADE
);

INSERT INTO equipped_offline_skins_new SELECT * FROM equipped_offline_skins;
DROP TABLE equipped_offline_skins;
ALTER TABLE equipped_offline_skins_new RENAME TO equipped_offline_skins;
