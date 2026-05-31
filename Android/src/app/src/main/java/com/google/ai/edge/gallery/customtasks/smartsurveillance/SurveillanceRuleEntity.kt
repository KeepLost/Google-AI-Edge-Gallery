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
    actionJson = actionJson,
    actionType = actionType,
    actionContent = actionContent,
    active = active,
    createdAt = createdAt,
    updatedAt = updatedAt,
  )
