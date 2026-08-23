package com.sway.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * THE Room database (AD-8): exactly one in the app, schema exported from
 * migration 1 onward, explicit migrations only, destructive fallback REFUSED
 * (a schema mismatch fails loudly at startup instead of silently wiping user
 * data). Entities arrive per epic: migration 1 births QueueStateEntity
 * (story 7.3); likes/playlists/history expand it in E8.
 */
@Database(
    entities = [QueueStateEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class SwayDatabase : RoomDatabase() {

    abstract fun queueStateDao(): QueueStateDao

    companion object {
        const val NAME = "sway.db"

        /**
         * Single build path. Deliberately NO `.fallbackToDestructiveMigration()`:
         * AD-8 refuses silent data loss; every future version bump must ship an
         * explicit, schema-tested Migration added to this builder.
         */
        fun build(context: Context): SwayDatabase =
            Room.databaseBuilder(context.applicationContext, SwayDatabase::class.java, NAME)
                .build()
    }
}
