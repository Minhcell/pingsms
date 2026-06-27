package com.pingsim

data class PingRecord(
    val phone: String,
    val code: String,
    val outcome: PingManager.Outcome,
    val detail: String,
    val time: String,
    val elapsedMs: Long
)
