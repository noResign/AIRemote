package com.noresign.paboot.data

/** 一条「最近连接」记录：可读名称 + 连接成功过的 daemon 地址与 token。 */
data class SavedConnection(
    val baseUrl: String,
    val token: String,
    /** 用户起的名字，用于区分多台电脑；旧记录可能为空。 */
    val name: String = "",
)
