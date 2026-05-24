/*
 * Copyright (C) 2025-2026 AxionOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.axion.sandbox.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.android.axion.sandbox.R
import com.android.axion.sandbox.io.FileVaultManager
import com.android.axion.sandbox.io.VaultFile
import kotlinx.coroutines.*

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun VaultTab(
    isUnlocked: Boolean = false,
    onUnlockRequest: () -> Unit = {},
    onPickingFilesChange: (Boolean) -> Unit = {}
) {
    if (!isUnlocked) {
        LockedVaultScreen(onUnlockRequest)
        return
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val vaultManager = remember { FileVaultManager(context) }
    var files by remember { mutableStateOf<List<VaultFile>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    val preparedMediaUris = remember { mutableStateMapOf<String, Uri>() }

    fun refreshFiles() {
        val shouldEagerPrepareVideo = isLoading
        scope.launch(Dispatchers.IO) {
            try {
                vaultManager.migrateLegacyIfNeeded()
                val list = vaultManager.getVaultFiles()
                val eagerFile = if (shouldEagerPrepareVideo) {
                    list.firstOrNull { it.mimeType.startsWith("video/") }
                } else {
                    null
                }
                val eagerUri = eagerFile?.let { vaultManager.prepareMediaStoreUri(it) }
                withContext(Dispatchers.Main) {
                    if (eagerFile != null && eagerUri != null) {
                        preparedMediaUris[eagerFile.id] = eagerUri
                    }
                    files = list
                    isLoading = false
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { isLoading = false }
            }
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                onPickingFilesChange(false)
                refreshFiles()
                scope.launch(Dispatchers.IO) { vaultManager.clearPublicBridge() }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var selectedFiles by remember { mutableStateOf(setOf<String>()) }
    val isMultiSelectMode by remember { derivedStateOf { selectedFiles.isNotEmpty() } }

    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showRestoreConfirm by remember { mutableStateOf(false) }
    
    var isProcessing by remember { mutableStateOf(false) }
    var processingMessage by remember { mutableStateOf("") }

    fun toggleSelection(id: String) {
        selectedFiles = if (selectedFiles.contains(id)) selectedFiles - id else selectedFiles + id
    }

    LaunchedEffect(Unit) { refreshFiles() }

    LaunchedEffect(files) {
        val activeIds = files.mapTo(HashSet(files.size)) { it.id }
        preparedMediaUris.keys.toList().forEach {
            if (it !in activeIds) preparedMediaUris.remove(it)
        }

        val (pendingVideos, pendingImages) = files
            .filter { it.isMediaStoreShareable() && preparedMediaUris[it.id] == null }
            .partition { it.mimeType.startsWith("video/") }
        val pendingMedia = pendingVideos + pendingImages
        withContext(Dispatchers.IO) {
            pendingMedia.forEach { file ->
                ensureActive()
                val uri = vaultManager.prepareMediaStoreUri(file)
                if (uri != null) {
                    withContext(Dispatchers.Main) {
                        preparedMediaUris[file.id] = uri
                    }
                }
            }
        }
    }

    val pickFilesLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            isProcessing = true
            processingMessage = "Importing..."
            scope.launch(Dispatchers.IO) {
                try {
                    uris.forEach { vaultManager.importFile(it) }
                } finally {
                    withContext(Dispatchers.Main) { 
                        isProcessing = false
                        refreshFiles() 
                    }
                }
            }
        }
    }

    fun executeRestore() {
        isProcessing = true
        processingMessage = "Restoring..."
        scope.launch(Dispatchers.IO) {
            try {
                val toRestore = files.filter { selectedFiles.contains(it.id) }
                vaultManager.restoreFiles(toRestore)
            } finally {
                withContext(Dispatchers.Main) {
                    isProcessing = false
                    selectedFiles = emptySet()
                    refreshFiles()
                }
            }
        }
    }

    fun executeDelete() {
        isProcessing = true
        processingMessage = "Deleting..."
        scope.launch(Dispatchers.IO) {
            try {
                val toDelete = files.filter { selectedFiles.contains(it.id) }
                vaultManager.deleteFiles(toDelete)
            } finally {
                withContext(Dispatchers.Main) {
                    isProcessing = false
                    selectedFiles = emptySet()
                    refreshFiles()
                }
            }
        }
    }

    fun handleOpenFile(file: VaultFile) {
        isProcessing = true
        processingMessage = "Opening..."
        val preparedUri = preparedMediaUris[file.id]
        scope.launch(Dispatchers.IO) {
            try {
                val shareUri = preparedUri ?: withTimeoutOrNull(45000) {
                    vaultManager.prepareMediaStoreUri(file)
                        ?: if (vaultManager.prepareFileForSharing(file)) vaultContentUri(file) else null
                }
                
                withContext(Dispatchers.Main) {
                    isProcessing = false
                    if (shareUri != null) {
                        if (file.isMediaStoreShareable() && shareUri.authority != VAULT_AUTHORITY) {
                            preparedMediaUris[file.id] = shareUri
                        }
                        onPickingFilesChange(true)
                        openVaultFileInternal(context, file, shareUri)
                    } else {
                        Toast.makeText(context, "Failed to open", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isProcessing = false
                    Toast.makeText(context, "Error", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val videos = remember(files) { files.filter { it.mimeType.startsWith("video/") } }
    val images = remember(files) { files.filter { it.mimeType.startsWith("image/") } }
    val otherFiles = remember(files) { files.filter { !it.mimeType.startsWith("image/") && !it.mimeType.startsWith("video/") } }

    Scaffold(
        floatingActionButton = {
            if (!isMultiSelectMode) {
                ExtendedFloatingActionButton(
                    onClick = { 
                        onPickingFilesChange(true)
                        pickFilesLauncher.launch(arrayOf("*/*")) 
                    },
                    icon = { Icon(Icons.Default.Add, null) },
                    text = { Text("Import") },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    shape = MaterialTheme.shapes.large
                )
            }
        },
        bottomBar = {
            if (isMultiSelectMode) {
                BottomAppBar(
                    actions = {
                        IconButton(onClick = { selectedFiles = emptySet() }) { Icon(Icons.Default.Close, null) }
                        Text("${selectedFiles.size} selected")
                    },
                    floatingActionButton = {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(onClick = { showRestoreConfirm = true }) {
                                Icon(Icons.Default.Restore, null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Restore")
                            }
                            FilledTonalButton(
                                onClick = { showDeleteConfirm = true },
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer,
                                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                                )
                            ) {
                                Icon(Icons.Default.Delete, null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Delete")
                            }
                        }
                    }
                )
            }
        },
        containerColor = Color.Transparent
    ) { paddingValues ->
        if (isLoading) {
            VaultLoadingScreen(paddingValues)
        } else if (files.isEmpty()) {
            EmptyVaultScreen(paddingValues)
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize().padding(paddingValues),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (images.isNotEmpty()) {
                    item(span = { GridItemSpan(3) }) { SectionHeader("Images") }
                    items(images, key = { it.id }) { file ->
                        MediaGridItem(file, vaultManager, selectedFiles.contains(file.id), false,
                            onClick = { if (isMultiSelectMode) toggleSelection(file.id) else handleOpenFile(file) },
                            onLongClick = { toggleSelection(file.id) })
                    }
                }

                if (videos.isNotEmpty()) {
                    item(span = { GridItemSpan(3) }) { Spacer(modifier = Modifier.height(16.dp)); SectionHeader("Videos") }
                    items(videos, key = { it.id }) { file ->
                        MediaGridItem(file, vaultManager, selectedFiles.contains(file.id), true,
                            onClick = { if (isMultiSelectMode) toggleSelection(file.id) else handleOpenFile(file) },
                            onLongClick = { toggleSelection(file.id) })
                    }
                }

                if (otherFiles.isNotEmpty()) {
                    item(span = { GridItemSpan(3) }) { Spacer(modifier = Modifier.height(16.dp)); SectionHeader("Files") }
                    items(otherFiles, key = { it.id }, span = { GridItemSpan(3) }) { file ->
                        FileListItem(file, selectedFiles.contains(file.id),
                            onClick = { if (isMultiSelectMode) toggleSelection(file.id) else handleOpenFile(file) },
                            onLongClick = { toggleSelection(file.id) })
                    }
                }
                item(span = { GridItemSpan(3) }) { Spacer(modifier = Modifier.height(80.dp)) }
            }
        }
    }

    if (showDeleteConfirm || showRestoreConfirm) {
        val isDelete = showDeleteConfirm
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false; showRestoreConfirm = false },
            title = { Text(if (isDelete) "Delete" else "Restore") },
            text = { Text(if (isDelete) "Permanently delete ${selectedFiles.size} files?" else "Restore ${selectedFiles.size} files?") },
            confirmButton = {
                TextButton(onClick = { if (isDelete) executeDelete() else executeRestore(); showDeleteConfirm = false; showRestoreConfirm = false }) {
                    Text(if (isDelete) "Delete" else "Restore", color = if (isDelete) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false; showRestoreConfirm = false }) { Text("Cancel") } }
        )
    }

    if (isProcessing) {
        Dialog(onDismissRequest = {}) {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                Row(
                    modifier = Modifier
                        .widthIn(min = 240.dp)
                        .padding(horizontal = 24.dp, vertical = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    LoadingIndicator(modifier = Modifier.size(48.dp))
                    Text(
                        text = processingMessage,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun VaultLoadingScreen(paddingValues: PaddingValues) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues),
        contentAlignment = Alignment.Center
    ) {
        LoadingIndicator()
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(text = title.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold, letterSpacing = 1.sp, modifier = Modifier.padding(vertical = 8.dp))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaGridItem(file: VaultFile, vaultManager: FileVaultManager, isSelected: Boolean, isVideo: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    var bitmap by remember(file.id) { mutableStateOf<Bitmap?>(null) }
    
    LaunchedEffect(file.id) {
        if (bitmap == null) {
            withContext(Dispatchers.IO) {
                val decoded = if (isVideo) vaultManager.decryptToVideoThumbnail(file) 
                              else vaultManager.decryptToBitmap(file)
                withContext(Dispatchers.Main) { bitmap = decoded }
            }
        }
    }

    Box(modifier = Modifier.aspectRatio(1f).clip(MaterialTheme.shapes.large).combinedClickable(onClick = onClick, onLongClick = onLongClick).background(MaterialTheme.colorScheme.surfaceBright)) {
        if (bitmap != null) {
            Image(bitmap = bitmap!!.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(if (isVideo) Icons.Default.VideoFile else Icons.Default.Image, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f), modifier = Modifier.align(Alignment.Center))
        }
        if (isVideo) Icon(Icons.Default.PlayCircle, null, tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(28.dp).align(Alignment.Center))
        if (isSelected) Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f))) {
            Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(8.dp).size(24.dp).background(Color.White, CircleShape).clip(CircleShape).align(Alignment.TopEnd))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileListItem(file: VaultFile, isSelected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick),
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surfaceBright, shape = MaterialTheme.shapes.large) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            val icon = when {
                file.mimeType.startsWith("video/") -> Icons.Default.VideoFile
                file.mimeType.startsWith("audio/") -> Icons.Default.AudioFile
                file.mimeType == "application/pdf" -> Icons.Default.PictureAsPdf
                file.mimeType == "application/vnd.android.package-archive" -> Icons.Default.Android
                file.mimeType.contains("zip") -> Icons.Default.FolderZip
                else -> Icons.Default.InsertDriveFile
            }
            Box(modifier = Modifier.size(40.dp).clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(20.dp))
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(file.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(formatFileSize(file.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (isSelected) Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
        }
    }
}

private fun vaultContentUri(file: VaultFile): Uri =
    Uri.parse("content://$VAULT_AUTHORITY/${file.id}")

private fun VaultFile.isMediaStoreShareable(): Boolean =
    mimeType.startsWith("image/") || mimeType.startsWith("video/")

private fun openVaultFileInternal(context: Context, file: VaultFile, uri: Uri) {
    try {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, file.mimeType)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (uri.authority == VAULT_AUTHORITY) {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        val chooser = Intent.createChooser(intent, "Open with")
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    } catch (e: Exception) {}
}

private const val VAULT_AUTHORITY = "com.android.axion.sandbox.vault"

private fun formatFileSize(size: Long): String {
    if (size <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
    return String.format("%.1f %s", size / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LockedVaultScreen(onUnlockRequest: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Box(modifier = Modifier.size(96.dp).clip(MaterialTheme.shapes.extraLarge).background(MaterialTheme.colorScheme.surfaceBright), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Lock, null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(modifier = Modifier.height(24.dp))
            Text(stringResource(R.string.vault_locked_title), style = MaterialTheme.typography.titleMediumEmphasized)
            Spacer(modifier = Modifier.height(8.dp))
            Text(stringResource(R.string.vault_locked_description), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(24.dp))
            FilledTonalButton(onClick = onUnlockRequest, shape = MaterialTheme.shapes.extraLarge) {
                Icon(Icons.Filled.LockOpen, null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.action_unlock))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun EmptyVaultScreen(paddingValues: PaddingValues) {
    Box(modifier = Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Icon(Icons.Outlined.FolderOpen, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
            Spacer(modifier = Modifier.height(16.dp))
            Text(stringResource(R.string.vault_empty_title), style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Text(stringResource(R.string.vault_empty_description), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
