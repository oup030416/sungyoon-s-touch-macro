package com.sungyoon.helper.update

data class AppUpdateDownloadProgress(
    val percent: Int,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val status: Int,
    val reason: Int,
)
