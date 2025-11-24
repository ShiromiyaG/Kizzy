/*
 * ******************************************************************
 * * * Copyright (C) 2022
 * * * UpdateDialog.kt is part of Kizzy
 * * * and can not be copied and/or distributed without the express
 * * * permission of yzziK(Vaibhav)
 * * *****************************************************************
 */

package com.my.kizzy.ui.components

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import dev.jeziellago.compose.markdowntext.MarkdownText
import com.my.kizzy.resources.R
import kotlinx.coroutines.delay
import java.io.File

fun Int.formatSize(): String =
    (this / 1024f / 1024f)
        .takeIf { it > 0f }
        ?.run { " ${String.format("%.2f", this)} MB" } ?: ""

@Composable
fun UpdateDialog(
    modifier: Modifier = Modifier,
    newVersionPublishDate: String,
    newVersionSize: Int,
    newVersionLog: String,
    downloadUrl: String = "https://github.com/ShiromiyaG/Kizzy/releases/latest/download/app-release.apk",
    onDismissRequest: () -> Unit = {}
) {
    val context = LocalContext.current
    var isDownloading by remember { mutableStateOf(false) }
    var downloadId by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(downloadId) {
        if (downloadId != null) {
            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            while (isDownloading) {
                val query = DownloadManager.Query().setFilterById(downloadId!!)
                val cursor = downloadManager.query(query)
                if (cursor.moveToFirst()) {
                    val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    when (status) {
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            isDownloading = false
                            cursor.close()
                            installApk(context, downloadId!!)
                            onDismissRequest()
                            return@LaunchedEffect
                        }
                        DownloadManager.STATUS_FAILED -> {
                            isDownloading = false
                            cursor.close()
                            return@LaunchedEffect
                        }
                    }
                }
                cursor.close()
                delay(500)
            }
        }
    }

    AlertDialog(
        modifier = modifier,
        onDismissRequest = if (isDownloading) ({}) else onDismissRequest,
        icon = {
            if (isDownloading) {
                CircularProgressIndicator()
            } else {
                Icon(
                    imageVector = Icons.Outlined.Update,
                    contentDescription = "Update",
                )
            }
        },
        title = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(text = if (isDownloading) stringResource(R.string.update_downloading) else stringResource(R.string.change_log))
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "$newVersionPublishDate ${newVersionSize.formatSize()}",
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
        },
        text = {
            Column {
                if (isDownloading) {
                    LinearProgressIndicator(modifier = Modifier.width(280.dp))
                    Spacer(modifier = Modifier.height(8.dp))
                }
                
                // Usando Markdown em vez de Text simples
                MarkdownText(
                    markdown = newVersionLog,
                    modifier = Modifier.verticalScroll(rememberScrollState())
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    isDownloading = true
                    downloadId = downloadApk(context, downloadUrl)
                },
                enabled = !isDownloading
            ) {
                Text(
                    text = if (isDownloading) stringResource(R.string.update_downloading) else stringResource(R.string.update)
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismissRequest,
                enabled = !isDownloading
            ) {
                Text(text = stringResource(R.string.cancel))
            }
        },
    )
}

private fun downloadApk(context: Context, url: String): Long {
    val request = DownloadManager.Request(Uri.parse(url))
        .setTitle(context.getString(R.string.update_download_title))
        .setDescription(context.getString(R.string.update_download_description))
        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "Kizzy-update.apk")
        .setAllowedOverMetered(true)
        .setAllowedOverRoaming(true)

    val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    return downloadManager.enqueue(request)
}

private fun installApk(context: Context, downloadId: Long) {
    val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    val uri = downloadManager.getUriForDownloadedFile(downloadId)
    if (uri != null) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            val apkFile = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Kizzy-update.apk")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val apkUri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.provider",
                    apkFile
                )
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else {
                setDataAndType(Uri.fromFile(apkFile), "application/vnd.android.package-archive")
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}

@Preview
@Composable
fun UpdateDialogPreview() {
    UpdateDialog(
        newVersionLog = """
## What's Changed 🎉

### New Features ✨
* Added dark mode support
* Implemented **user profiles**
* New notification system

### Bug Fixes 🐛
- Fixed crash on startup
- Resolved memory leak in `MainActivity`
- Fixed UI rendering issues

### Improvements 🚀
1. Better performance
2. Reduced app size by 20%
3. Updated dependencies

[View full changelog](https://github.com/example/repo)
        """.trimIndent(),
        newVersionPublishDate = "2025-11-24",
        newVersionSize = 5242880,
        onDismissRequest = {},
    )
}
