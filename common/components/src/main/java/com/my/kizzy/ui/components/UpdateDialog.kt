/*
 *
 *  ******************************************************************
 *  *  * Copyright (C) 2022
 *  *  * UpdateDialog.kt is part of Kizzy
 *  *  *  and can not be copied and/or distributed without the express
 *  *  * permission of yzziK(Vaibhav)
 *  *  *****************************************************************
 *
 *
 */

package com.my.kizzy.ui.components

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.my.kizzy.resources.R
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
                SelectionContainer {
                    Text(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        text = newVersionLog,
                    )
                }
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
        newVersionLog = "1. Fix bugs\n2. Fix bugs\n3. Fix bugs\n4. Fix bugs\n5. Fix bugs\n6. Fix bugs\n7. Fix bugs\n8. Fix bugs\n9. Fix bugs\n10. Fix bugs\n11. Fix bugs\n12. Fix bugs\n13. Fix bugs\n14. Fix bugs\n15. Fix bugs\n16. Fix bugs\n17. Fix bugs\n18. Fix bugs\n19. Fix bugs\n20. Fix bugs\n21. Fix bugs\n22. Fix bugs\n23. Fix bugs\n24. Fix bugs\n25. Fix bugs\n26. Fix bugs\n27. Fix bugs\n28. Fix bugs\n29. Fix bugs\n30. Fix bugs\n31. Fix bugs\n32. Fix bugs\n33. Fix bugs\n34. Fix bugs\n35. Fix bugs\n36. Fix bugs\n37. Fix bugs\n38. Fix bugs\n39. Fix bugs\n40. Fix bugs\n41. Fix bugs\n42. Fix bugs\n43. Fix bugs\n44. Fix bugs\n45. Fix bugs\n46. Fix bugs\n47. Fix bugs\n48. Fix bugs\n49. Fix bugs\n50. Fix bugs\n51. Fix bugs\n52. Fix bugs\n53. Fix bugs\n54. Fix bugs\n55. Fix bugs\n56. Fix bugs\n57. Fix bugs\n58. Fix bugs\n59. Fix bugs\n60. Fix bugs\n61. Fix bugs\n62. Fix bugs\n63. Fix bugs\n64. Fix bugs\n65. Fix bugs\n66. Fix bugs\n67. Fix bugs\n68. Fix bugs\n69. Fix bugs\n70. Fix bugs\n71. Fix bugs\n72. Fix bugs\n73. Fix bugs\n74. Fix bugs\n75. Fix bugs\n76. Fix bugs\n77. Fix bugs\n78. Fix bugs\n79. Fix bugs\n80. Fix bugs\n81. Fix bugs\n82. Fix bugs\n83. Fix bugs\n84. Fix bugs\n85. Fix bugs\n86. Fix bugs\n87. Fix bugs\n88. Fix bugs\n89. Fix bugs\n90. Fix bugs\n91. Fix bugs\n92. Fix bugs\n93. Fix bugs\n94. Fix bugs\n95. Fix bugs\n96. Fix bugs\n97. Fix bugs\n98. Fix bugs\n99. Fix bugs\n100. Fix bugs",
        newVersionPublishDate = "2021-10-10",
        newVersionSize = 1000000,
        onDismissRequest = {},
        modifier = Modifier
            .height(500.dp)
            .width(300.dp)
    )
}