package com.google.ai.edge.gallery.customtasks.smartsurveillance

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SurveillanceRuleDao {
  @Query("SELECT * FROM rules ORDER BY updated_at DESC") fun observeRules(): Flow<List<SurveillanceRuleEntity>>

  @Query("SELECT * FROM rules WHERE active = 1 ORDER BY updated_at DESC") suspend fun getActiveRules(): List<SurveillanceRuleEntity>

  @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertRule(rule: SurveillanceRuleEntity)

  @Update suspend fun updateRule(rule: SurveillanceRuleEntity)

  @Delete suspend fun deleteRule(rule: SurveillanceRuleEntity)
}
