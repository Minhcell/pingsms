package com.pingsim

data class PingRecord(
    val phone: String,
    val method: String,   // "SMS" hoặc "HLR"
    val code: String,     // mã kiểm tra (SMS) hoặc "" (HLR)
    val outcome: PingManager.Outcome,
    val detail: String,
    val time: String,
    val elapsedMs: Long
)
