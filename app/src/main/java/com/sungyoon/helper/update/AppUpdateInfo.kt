package com.sungyoon.helper.update

data class AppUpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val downloadUrl: String,
    val releaseNotes: String,
    val assetSizeBytes: Long,
)
