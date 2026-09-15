package com.airemote.airemote.data

/** 一条「最近连接」记录：连接成功过的 daemon 地址与 token。 */
data class SavedConnection(
    val baseUrl: String,
    val token: String,
)
