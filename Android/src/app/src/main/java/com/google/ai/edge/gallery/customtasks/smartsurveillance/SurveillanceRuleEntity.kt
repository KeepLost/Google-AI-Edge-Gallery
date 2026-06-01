package com.google.ai.edge.gallery.customtasks.smartsurveillance

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "rules")
data class SurveillanceRuleEntity(
  @PrimaryKey val id: String,
  val name: String,
  @ColumnInfo(name = "raw_prompt") val rawPrompt: String,
  @ColumnInfo(name = "trigger_json") val triggerJson: String,
  /**
   * Concise, visually-checkable condition sent to Gemma during realtime analysis (for example
   * "a person is standing near the door"). This is the only per-rule semantic content the model
   * sees while monitoring; the action/TTS text is never sent. Empty for rules created before the
   * detection_feature migration; callers fall back to [triggerJson]/[rawPrompt].
   */
  @ColumnInfo(name = "detection_feature", defaultValue = "") val detectionFeature: String = "",
  @ColumnInfo(name = "action_json") val actionJson: String,
  @ColumnInfo(name = "action_type") val actionType: String,
  @ColumnInfo(name = "action_content") val actionContent: String,
  val active: Boolean,
  @ColumnInfo(name = "created_at") val createdAt: Long,
  @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

fun ParsedSurveillanceRule.toEntity(): SurveillanceRuleEntity =
  SurveillanceRuleEntity(
    id = id,
    name = name,
    rawPrompt = rawPrompt,
    triggerJson = triggerJson,
    detectionFeature = detectionFeature,
    actionJson = actionJson,
    actionType = actionType,
    actionContent = actionContent,
    active = active,
    createdAt = createdAt,
    updatedAt = updatedAt,
  )
