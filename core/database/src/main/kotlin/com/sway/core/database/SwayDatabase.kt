package com.sway.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * THE Room database (AD-8): exactly one in the app, schema exported from
 * migration 1 onward, explicit migrations only, destructive fallback REFUSED
 * (a schema mismatch fails loudly at startup instead of silently wiping user
 * data). Entities: QueueStateEntity (migration 1, story 7.3);
 * SongEntity (migration 2, story 8.1 — likes); playlists + joins
 * (migration 3, story 8.2); history lands with story 8.3.
 */
@Database(
    entities = [QueueStateEntity::class, SongEntity::class, PlaylistEntity::class, PlaylistSongEntity::class],
    version = 3,
    exportSchema = true,
)
abstract class SwayDatabase : RoomDatabase() {

    abstract fun queueStateDao(): QueueStateDao

    abstract fun libraryDao(): LibraryDao

    abstract fun playlistDao(): PlaylistDao

    companion object {
        const val NAME = "sway.db"

        /**
         * Migration 1 -> 2 (story 8.1): birth of the song snapshot table with
         * the likedAt recency index. Purely additive; queue_state data is
         * untouched (survival asserted by MigrationTestHelper).
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `song_entities` (" +
                        "`sourceId` TEXT NOT NULL, " +
                        "`title` TEXT NOT NULL, " +
                        "`rawTitle` TEXT NOT NULL, " +
                        "`artistName` TEXT, " +
                        "`artistId` TEXT, " +
                        "`albumName` TEXT, " +
                        "`albumId` TEXT, " +
                        "`durationMs` INTEGER NOT NULL, " +
                        "`artworkUrl` TEXT, " +
                        "`likedAt` INTEGER, " +
                        "PRIMARY KEY(`sourceId`))",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_song_entities_likedAt` ON `song_entities` (`likedAt`)",
                )
            }
        }

        /**
         * Migration 2 -> 3 (story 8.2): playlist headers + ordered join rows.
         * Purely additive; likes and queue state untouched.
         */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `playlists` (" +
                        "`playlistId` TEXT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`rawName` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`playlistId`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `playlist_songs` (" +
                        "`playlistId` TEXT NOT NULL, " +
                        "`songId` TEXT NOT NULL, " +
                        "`position` INTEGER NOT NULL, " +
                        "`addedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`playlistId`, `songId`), " +
                        "FOREIGN KEY(`playlistId`) REFERENCES `playlists`(`playlistId`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE , " +
                        "FOREIGN KEY(`songId`) REFERENCES `song_entities`(`sourceId`) " +
                        "ON UPDATE NO ACTION ON DELETE NO ACTION)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_playlist_songs_playlistId_position` " +
                        "ON `playlist_songs` (`playlistId`, `position`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_playlist_songs_songId` ON `playlist_songs` (`songId`)",
                )
            }
        }

        val ALL_MIGRATIONS = arrayOf<Migration>(MIGRATION_1_2, MIGRATION_2_3)

        /**
         * Single build path. Deliberately NO destructive-fallback API is
         * configured: AD-8 refuses silent data loss; every version bump must
         * ship an explicit, schema-tested Migration added to [ALL_MIGRATIONS].
         */
        fun build(context: Context): SwayDatabase =
            Room.databaseBuilder(context.applicationContext, SwayDatabase::class.java, NAME)
                .addMigrations(*ALL_MIGRATIONS)
                .build()
    }
}
