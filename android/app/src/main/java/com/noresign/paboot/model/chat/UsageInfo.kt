package com.noresign.paboot.model.chat

data class UsageInfo(
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val costUsd: Double? = null,
)
