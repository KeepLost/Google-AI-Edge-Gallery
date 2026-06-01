package com.google.ai.edge.gallery.customtasks.smartsurveillance

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [SurveillanceRuleEntity::class], version = 2, exportSchema = false)
abstract class SurveillanceDatabase : RoomDatabase() {
  abstract fun ruleDao(): SurveillanceRuleDao

  companion object {
    /**
     * v1 -> v2: add the perception-only `detection_feature` column used by realtime analysis.
     * Non-destructive: existing rows default to "" and the prompt builder falls back to the
     * stored trigger/raw prompt for those rules.
     */
    val MIGRATION_1_2: Migration =
      object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
          db.execSQL("ALTER TABLE rules ADD COLUMN detection_feature TEXT NOT NULL DEFAULT ''")
        }
      }
  }
}
