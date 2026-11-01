package dev.baseline.data.db

import androidx.room3.Entity
import java.time.Instant

/**
 * One generated insight per page: [scope] is "today", "sleep" or "activity"; [scopeKey] is an ISO date, or an activity row id.
 * [engine] is "nano" or "cloud"; [model] is the cloud model id, or the Nano base model name.
 */
@Entity(tableName = "ai_insight", primaryKeys = ["scope", "scopeKey"])
data class AiInsightEntity(
    val scope: String,
    val scopeKey: String,
    val fingerprint: String,
    val text: String,
    val engine: String,
    val model: String?,
    val createdAt: Instant,
)
