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
 * SongEntity (migration 2, story 8.1 — likes); playlists/history expand in
 * stories 8.2/8.3.
 */
@Database(
    entities = [QueueStateEntity::class, SongEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class SwayDatabase : RoomDatabase() {

    abstract fun queueStateDao(): QueueStateDao

    abstract fun libraryDao(): LibraryDao

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

        val ALL_MIGRATIONS = arrayOf<Migration>(MIGRATION_1_2)

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
