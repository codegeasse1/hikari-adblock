package com.codegeasse1.hikariadblock.data.entities

data class AppStat(
    val appName: String,
    val packageName: String,
    val totalQueries: Int,
    val blockedQueries: Int
)