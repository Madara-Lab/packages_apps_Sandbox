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
package com.android.axion.sandbox.io

import android.content.ContentProvider
import android.content.ContentValues
import android.content.ClipDescription
import android.content.Context
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.BaseColumns
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

class VaultFileProvider : ContentProvider() {
    private val bridgeLock = Any()

    private fun caller(): String = try {
        "${getCallingPackage() ?: "unknown"}/${Binder.getCallingUid()}"
    } catch (e: SecurityException) {
        "invalid/${Binder.getCallingUid()}"
    }

    override fun onCreate(): Boolean {
        context?.let {
            if (!VaultAccessController.isUnlocked()) FileVaultManager.clearDecryptedCache(it)
        }
        return true
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (!VaultAccessController.isUnlocked()) {
            Log.w(TAG, "openFile denied locked caller=${caller()} uri=$uri mode=$mode")
            return null
        }

        val context = context ?: return null
        val fileId = uri.lastPathSegment ?: return null
        val vaultManager = FileVaultManager(context)
        val vaultFile = vaultManager.getFileById(fileId) ?: run {
            Log.w(TAG, "openFile missing id=$fileId caller=${caller()} uri=$uri")
            return null
        }
        val tempFile = FileVaultManager.getBridgeFile(context, vaultFile)
        Log.i(TAG, "openFile caller=${caller()} id=$fileId mode=$mode mime=${vaultFile.mimeType} exists=${tempFile.exists()} length=${tempFile.length()} expected=${vaultFile.size} path=${tempFile.absolutePath}")

        return if (tempFile.exists() && tempFile.length() == vaultFile.size) {
            ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)
        } else {
            openSeekableFile(context, vaultManager, vaultFile)
        }
    }

    private fun openSeekableFile(context: Context, vaultManager: FileVaultManager, vaultFile: VaultFile): ParcelFileDescriptor? =
        synchronized(bridgeLock) {
            try {
                val tempFile = FileVaultManager.getBridgeFile(context, vaultFile)
                if (tempFile.exists() && tempFile.length() == vaultFile.size) {
                    return ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)
                }

                val key = vaultManager.getMasterKey() ?: run {
                    Log.w(TAG, "openSeekableFile missing key caller=${caller()} id=${vaultFile.id}")
                    return null
                }
                FileInputStream(vaultFile.file).use { fis ->
                    val iv = ByteArray(12)
                    if (fis.read(iv) == 12) {
                        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))

                        FileOutputStream(tempFile).use { fos ->
                            val buffer = ByteArray(65536)
                            while (true) {
                                val read = fis.read(buffer)
                                if (read == -1) break
                                val decrypted = cipher.update(buffer, 0, read)
                                if (decrypted != null) fos.write(decrypted)
                            }
                            val finalBlock = cipher.doFinal()
                            if (finalBlock != null) fos.write(finalBlock)
                            fos.flush()
                        }
                    } else {
                        Log.w(TAG, "openSeekableFile short iv caller=${caller()} id=${vaultFile.id}")
                    }
                }
                tempFile.parentFile?.setExecutable(true, false)
                tempFile.parentFile?.setReadable(true, false)
                tempFile.setReadable(true, false)
                Log.i(TAG, "openSeekableFile ready caller=${caller()} id=${vaultFile.id} length=${tempFile.length()} expected=${vaultFile.size} path=${tempFile.absolutePath}")
                ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)
            } catch (e: Exception) {
                Log.w(TAG, "openSeekableFile failed caller=${caller()} id=${vaultFile.id}", e)
                null
            }
        }

    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor? {
        val descriptor = openFile(uri, mode) ?: return null
        val length = descriptor.statSize
        Log.i(TAG, "openAssetFile caller=${caller()} uri=$uri mode=$mode length=$length")
        return AssetFileDescriptor(
            descriptor,
            0,
            if (length >= 0) length else AssetFileDescriptor.UNKNOWN_LENGTH
        )
    }

    override fun openTypedAssetFile(uri: Uri, mimeTypeFilter: String, opts: Bundle?): AssetFileDescriptor? {
        val type = getType(uri) ?: return null
        val matches = ClipDescription.compareMimeTypes(type, mimeTypeFilter)
        Log.i(TAG, "openTypedAssetFile caller=${caller()} uri=$uri filter=$mimeTypeFilter type=$type matches=$matches")
        if (!matches) return null
        return openAssetFile(uri, "r")
    }

    override fun getStreamTypes(uri: Uri, mimeTypeFilter: String): Array<String>? {
        val type = getType(uri) ?: return null
        val matches = ClipDescription.compareMimeTypes(type, mimeTypeFilter)
        Log.i(TAG, "getStreamTypes caller=${caller()} uri=$uri filter=$mimeTypeFilter type=$type matches=$matches")
        return if (matches) arrayOf(type) else null
    }

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor? {
        if (!VaultAccessController.isUnlocked()) {
            Log.w(TAG, "query denied locked caller=${caller()} uri=$uri projection=${projection?.contentToString()}")
            return null
        }

        val context = context ?: return null
        val fileId = uri.lastPathSegment ?: return null
        val vaultManager = FileVaultManager(context)
        val vaultFile = vaultManager.getFileById(fileId) ?: run {
            Log.w(TAG, "query missing id=$fileId caller=${caller()} uri=$uri projection=${projection?.contentToString()}")
            return null
        }

        val columnNames = projection ?: arrayOf(
            BaseColumns._ID, OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED
        )
        
        val cursor = MatrixCursor(columnNames, 1)
        val row = cursor.newRow()
        val needsDataPath = columnNames.contains(MediaStore.MediaColumns.DATA)
        val bridgePath = if (needsDataPath && vaultManager.prepareFileForSharing(vaultFile)) {
            FileVaultManager.getBridgeFile(context, vaultFile).absolutePath
        } else {
            null
        }
        Log.i(TAG, "query caller=${caller()} id=$fileId projection=${columnNames.contentToString()} mime=${vaultFile.mimeType} needsData=$needsDataPath bridge=$bridgePath size=${vaultFile.size}")

        for (column in columnNames) {
            when (column) {
                BaseColumns._ID -> row.add(1)
                OpenableColumns.DISPLAY_NAME, MediaStore.MediaColumns.DISPLAY_NAME -> row.add(vaultFile.name)
                OpenableColumns.SIZE, MediaStore.MediaColumns.SIZE -> row.add(vaultFile.size)
                MediaStore.MediaColumns.MIME_TYPE -> row.add(vaultFile.mimeType)
                MediaStore.MediaColumns.DATA -> row.add(bridgePath)
                MediaStore.MediaColumns.DATE_MODIFIED -> row.add(System.currentTimeMillis() / 1000)
                else -> row.add(null)
            }
        }
        return cursor
    }

    override fun getType(uri: Uri): String? {
        if (!VaultAccessController.isUnlocked()) {
            Log.w(TAG, "getType denied locked caller=${caller()} uri=$uri")
            return null
        }

        val context = context ?: return null
        val fileId = uri.lastPathSegment ?: return null
        val type = FileVaultManager(context).getFileById(fileId)?.mimeType
        Log.i(TAG, "getType caller=${caller()} id=$fileId type=$type uri=$uri")
        return type
    }

    private companion object {
        private const val TAG = "VaultFileProvider"
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0
}
