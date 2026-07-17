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

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.security.keymint.Algorithm
import android.hardware.security.keymint.BlockMode
import android.hardware.security.keymint.KeyParameter
import android.hardware.security.keymint.KeyParameterValue
import android.hardware.security.keymint.KeyPurpose
import android.hardware.security.keymint.PaddingMode
import android.hardware.security.keymint.Tag
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.security.KeyStore2
import android.security.KeyStoreException
import android.security.KeyStoreOperation
import android.security.KeyStoreSecurityLevel
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.system.Os
import android.system.OsConstants
import android.system.keystore2.Domain
import android.system.keystore2.KeyDescriptor
import android.util.Log
import android.util.LruCache
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal object VaultAccessController {
    @Volatile
    private var unlocked = false

    fun unlock() {
        unlocked = true
    }

    fun lock(context: Context) {
        unlocked = false
        FileVaultManager.clearDecryptedCache(context)
    }

    fun isUnlocked(): Boolean = unlocked
}

class FileVaultManager(private val context: Context) {
    private val KEY_ALIAS = "ax_vault_master_key"
    private val ALGORITHM = "AES/GCM/NoPadding"
    private val IO_BUFFER_SIZE = 32768

    private val dbHelper = VaultDbHelper(context)

    companion object {
        private const val TAG = "FileVaultManager"
        private const val BRIDGE_DIR_NAME = ".vault_bridge"
        private const val LEGACY_BRIDGE_DIR_NAME = "vault_bridge"
        private const val MEDIA_BRIDGE_DIR_NAME = "AxionVaultBridge"
        private const val MEDIA_BRIDGE_PREFIX = "vault_"
        private const val IV_SIZE = 12
        private const val GCM_TAG_SIZE = 16
        private val thumbnailCache = LruCache<String, Bitmap>(50)

        internal fun clearDecryptedCache(context: Context) {
            clearMediaBridge(context)
            clearLegacyBridgeDirs(context)
            File(context.cacheDir, "thumb_cache").deleteRecursively()
            context.cacheDir.listFiles { file -> file.name.startsWith("v_thumb_") }?.forEach { it.delete() }
            thumbnailCache.evictAll()
        }

        private fun mediaBridgeRelativePath(mimeType: String): String {
            val directory = if (mimeType.startsWith("video/", true)) {
                Environment.DIRECTORY_MOVIES
            } else {
                Environment.DIRECTORY_PICTURES
            }
            return "$directory/$MEDIA_BRIDGE_DIR_NAME/"
        }

        private fun clearMediaBridge(context: Context) {
            deleteMediaBridgeRows(
                context,
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                mediaBridgeRelativePath("image/jpeg")
            )
            deleteMediaBridgeRows(
                context,
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                mediaBridgeRelativePath("video/mp4")
            )
            File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                MEDIA_BRIDGE_DIR_NAME
            ).deleteRecursively()
            File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                MEDIA_BRIDGE_DIR_NAME
            ).deleteRecursively()
        }

        private fun deleteMediaBridgeRows(context: Context, collection: Uri, relativePath: String) {
            try {
                val selection = "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND " +
                    "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
                val args = arrayOf(relativePath, "$MEDIA_BRIDGE_PREFIX%")
                context.contentResolver.delete(collection, selection, args)
            } catch (e: Exception) {
                Log.w(TAG, "deleteMediaBridgeRows failed path=$relativePath", e)
            }
        }

        private fun clearLegacyBridgeDirs(context: Context) {
            val roots = buildList {
                add(context.cacheDir)
                context.externalCacheDir?.let { add(it) }
                addAll(context.externalMediaDirs.filterNotNull())
            }
            roots.forEach { root ->
                File(root, BRIDGE_DIR_NAME).deleteRecursively()
                File(root, LEGACY_BRIDGE_DIR_NAME).deleteRecursively()
            }
        }
    }
    
    private val vaultDir: File by lazy {
        File(context.filesDir, "vault").apply {
            if (!exists()) mkdirs()
            try { File(this, ".nomedia").createNewFile() } catch (e: Exception) {}
        }
    }

    private val metadataFile: File by lazy { File(vaultDir, "metadata.json") }

    init {
        ensureKeyExists()
    }

    private fun ensureKeyExists() {
        try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (!keyStore.containsAlias(KEY_ALIAS)) {
                val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                keyGenerator.init(KeyGenParameterSpec.Builder(KEY_ALIAS, 
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build())
                keyGenerator.generateKey()
            }
        } catch (e: Exception) {}
    }

    private fun getSecretKey(): SecretKey? {
        return try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
        } catch (e: Exception) { null }
    }

    private fun decryptToOutput(vaultFile: VaultFile, output: OutputStream): Boolean {
        var operation: KeyStoreOperation? = null
        var finished = false
        return try {
            FileInputStream(vaultFile.file).use { input ->
                val iv = ByteArray(IV_SIZE)
                if (input.read(iv) != IV_SIZE) {
                    Log.w(TAG, "decryptToOutput short iv id=${vaultFile.id}")
                    return false
                }

                val decryptOperation = createDecryptOperation(iv)
                operation = decryptOperation
                val buffer = ByteArray(IO_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    val decrypted = decryptOperation.update(
                        if (read == buffer.size) buffer else buffer.copyOf(read)
                    )
                    if (decrypted != null && decrypted.isNotEmpty()) output.write(decrypted)
                }
                val finalBlock = decryptOperation.finish(null, null)
                finished = true
                if (finalBlock != null && finalBlock.isNotEmpty()) output.write(finalBlock)
                output.flush()
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "decryptToOutput failed id=${vaultFile.id}", e)
            false
        } finally {
            if (!finished) {
                try {
                    operation?.abort()
                } catch (e: KeyStoreException) {
                    Log.w(TAG, "decryptToOutput abort failed id=${vaultFile.id}", e)
                }
            }
        }
    }

    private fun createDecryptOperation(iv: ByteArray): KeyStoreOperation {
        val descriptor = KeyDescriptor().apply {
            domain = Domain.APP
            nspace = KeyProperties.NAMESPACE_APPLICATION.toLong()
            alias = KEY_ALIAS
            blob = null
        }
        val entry = KeyStore2.getInstance().getKeyEntry(descriptor)
        val securityLevel = entry.iSecurityLevel
            ?: throw IllegalStateException("Missing vault key security level")
        val parameters = listOf(
            keyParameter(Tag.PURPOSE, KeyParameterValue.keyPurpose(KeyPurpose.DECRYPT)),
            keyParameter(Tag.ALGORITHM, KeyParameterValue.algorithm(Algorithm.AES)),
            keyParameter(Tag.BLOCK_MODE, KeyParameterValue.blockMode(BlockMode.GCM)),
            keyParameter(Tag.PADDING, KeyParameterValue.paddingMode(PaddingMode.NONE)),
            keyParameter(Tag.NONCE, KeyParameterValue.blob(iv)),
            keyParameter(Tag.MAC_LENGTH, KeyParameterValue.integer(GCM_TAG_SIZE * 8))
        )
        return KeyStoreSecurityLevel(securityLevel).createOperation(entry.metadata.key, parameters)
    }

    private fun keyParameter(tag: Int, value: KeyParameterValue): KeyParameter =
        KeyParameter().apply {
            this.tag = tag
            this.value = value
        }

    internal fun openDecryptedFile(vaultFile: VaultFile): ParcelFileDescriptor? {
        if (!VaultAccessController.isUnlocked()) {
            Log.w(TAG, "openDecryptedFile denied locked id=${vaultFile.id}")
            return null
        }

        val tempFile = File.createTempFile("vault_", ".tmp", context.cacheDir)
        var writer: ParcelFileDescriptor? = null
        var reader: ParcelFileDescriptor? = null
        return try {
            val outputDescriptor = ParcelFileDescriptor.open(
                tempFile,
                ParcelFileDescriptor.MODE_READ_WRITE
            )
            writer = outputDescriptor
            if (!tempFile.delete()) return null
            val inputDescriptor = ParcelFileDescriptor.dup(outputDescriptor.fileDescriptor)
            reader = inputDescriptor
            val decrypted = ParcelFileDescriptor.AutoCloseOutputStream(outputDescriptor).use { output ->
                decryptToOutput(vaultFile, output)
            }
            writer = null
            if (!decrypted || !VaultAccessController.isUnlocked()) {
                null
            } else {
                Os.lseek(inputDescriptor.fileDescriptor, 0, OsConstants.SEEK_SET)
                reader = null
                inputDescriptor
            }
        } catch (e: Exception) {
            Log.w(TAG, "openDecryptedFile failed id=${vaultFile.id}", e)
            null
        } finally {
            tempFile.delete()
            writer?.close()
            reader?.close()
        }
    }

    fun migrateLegacyIfNeeded() {
        if (!metadataFile.exists()) return
        try {
            val key = getSecretKey() ?: return
            val cipher = Cipher.getInstance(ALGORITHM)
            val json = FileInputStream(metadataFile).use { fis ->
                val iv = ByteArray(IV_SIZE)
                if (fis.read(iv) != IV_SIZE) return
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
                val data = fis.readBytes()
                val decrypted = cipher.doFinal(data)
                JSONObject(String(decrypted, Charsets.UTF_8))
            }
            val keys = json.keys()
            while (keys.hasNext()) {
                val id = keys.next()
                val entry = json.optJSONObject(id) ?: continue
                dbHelper.insertFile(id, entry.optString("name", "Unknown"), 
                    entry.optLong("size", 0L), entry.optString("mime", "application/octet-stream"), 
                    entry.optString("path", null))
            }
            metadataFile.renameTo(File(vaultDir, "metadata.json.migrated"))
        } catch (e: Exception) { metadataFile.delete() }
    }

    fun importFile(uri: Uri): Boolean {
        return try {
            val originalName = getFileName(uri) ?: "file_${System.currentTimeMillis()}"
            val mimeType = resolveMimeType(originalName, uri)
            val fileId = UUID.randomUUID().toString()
            val destFile = File(vaultDir, "$fileId.bin")
            val originalPath = getFilePathFromUri(uri)
            
            val key = getSecretKey() ?: return false
            val cipher = Cipher.getInstance(ALGORITHM)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            var size = 0L
            context.contentResolver.openInputStream(uri)?.use { isStream ->
                FileOutputStream(destFile).use { fos ->
                    fos.write(cipher.iv)
                    val buffer = ByteArray(IO_BUFFER_SIZE)
                    while (true) {
                        val read = isStream.read(buffer)
                        if (read == -1) break
                        size += read
                        val encrypted = cipher.update(buffer, 0, read)
                        if (encrypted != null) fos.write(encrypted)
                    }
                    val finalBlock = cipher.doFinal()
                    if (finalBlock != null) fos.write(finalBlock)
                    fos.flush()
                }
            } ?: return false
            
            dbHelper.insertFile(fileId, originalName, size, mimeType, originalPath)
            cleanupOriginal(uri, originalPath)
            true
        } catch (e: Exception) { false }
    }

    private fun resolveMimeType(name: String, uri: Uri): String {
        return when {
            name.endsWith(".apk", true) -> "application/vnd.android.package-archive"
            name.endsWith(".pdf", true) -> "application/pdf"
            else -> context.contentResolver.getType(uri) ?: "application/octet-stream"
        }
    }

    private fun cleanupOriginal(uri: Uri, path: String?) {
        try {
            val safDeleted = try { DocumentsContract.deleteDocument(context.contentResolver, uri) } catch (e: Exception) { false }
            if (!safDeleted && path != null) {
                val file = File(path)
                if (file.exists() && file.delete()) {
                    context.contentResolver.delete(MediaStore.Files.getContentUri("external"), "_data=?", arrayOf(path))
                }
            }
        } catch (e: Exception) {}
    }

    fun restoreFiles(vaultFiles: List<VaultFile>): Int {
        var count = 0
        vaultFiles.forEach { if (restoreFile(it)) count++ }
        if (count > 0) clearDecryptedCache(context)
        return count
    }

    fun restoreFile(vaultFile: VaultFile): Boolean {
        val targetFile = if (!vaultFile.originalPath.isNullOrEmpty()) {
            val originalFile = File(vaultFile.originalPath)
            originalFile.parentFile?.mkdirs()
            originalFile
        } else {
            val restoreDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Vault_Restored").apply { if (!exists()) mkdirs() }
            File(restoreDir, vaultFile.name)
        }

        val decryptedFile = openDecryptedFile(vaultFile) ?: return false
        return try {
            ParcelFileDescriptor.AutoCloseInputStream(decryptedFile).use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output, IO_BUFFER_SIZE)
                    output.flush()
                }
            }
            val scanIntent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE).apply {
                data = Uri.fromFile(targetFile)
            }
            context.sendBroadcast(scanIntent)
            deleteFile(vaultFile)
            true
        } catch (e: Exception) {
            targetFile.delete()
            false
        }
    }

    fun deleteFiles(vaultFiles: List<VaultFile>) {
        vaultFiles.forEach { deleteFile(it) }
        if (vaultFiles.isNotEmpty()) clearDecryptedCache(context)
    }

    fun deleteFile(vaultFile: VaultFile): Boolean {
        dbHelper.deleteFile(vaultFile.id)
        thumbnailCache.remove(vaultFile.id)
        File(context.cacheDir, "thumb_cache/${vaultFile.id}").delete()
        File(context.cacheDir, "thumb_cache/${vaultFile.id}_v").delete()
        return vaultFile.file.delete()
    }

    fun getFileById(id: String): VaultFile? {
        val entry = dbHelper.getFileById(id) ?: return null
        val binFile = File(vaultDir, "${entry.id}.bin")
        return if (binFile.exists()) attachFile(entry, binFile) else null
    }

    fun getVaultFiles(): List<VaultFile> {
        val dbFiles = dbHelper.getAllFiles()
        val binFiles = vaultDir.listFiles { f -> f.extension == "bin" }?.associateBy { it.nameWithoutExtension } ?: emptyMap()
        return dbFiles.mapNotNull { entry ->
            val binFile = binFiles[entry.id]
            if (binFile != null) attachFile(entry, binFile)
            else { dbHelper.deleteFile(entry.id); null }
        }
    }

    private fun attachFile(entry: VaultFile, file: File): VaultFile {
        val size = (file.length() - IV_SIZE - GCM_TAG_SIZE).coerceAtLeast(0)
        if (size != entry.size) dbHelper.updateFileSize(entry.id, size)
        return entry.copy(size = size, file = file)
    }

    fun decryptToBitmap(vaultFile: VaultFile): Bitmap? {
        thumbnailCache.get(vaultFile.id)?.let { return it }

        val thumbCache = File(context.cacheDir, "thumb_cache").apply { if (!exists()) mkdirs() }
        val cachedThumb = File(thumbCache, vaultFile.id)
        if (cachedThumb.exists()) {
            val bitmap = BitmapFactory.decodeFile(cachedThumb.absolutePath)
            if (bitmap != null) {
                thumbnailCache.put(vaultFile.id, bitmap)
                return bitmap
            }
        }

        return try {
            val decryptedFile = openDecryptedFile(vaultFile) ?: return null
            ParcelFileDescriptor.AutoCloseInputStream(decryptedFile).use { input ->
                val options = BitmapFactory.Options().apply { inSampleSize = 4 }
                val bitmap = BitmapFactory.decodeStream(input, null, options)
                if (bitmap != null) {
                    thumbnailCache.put(vaultFile.id, bitmap)
                    FileOutputStream(cachedThumb).use { fos ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 70, fos)
                    }
                }
                bitmap
            }
        } catch (e: Exception) { null }
    }

    fun decryptToVideoThumbnail(vaultFile: VaultFile): Bitmap? {
        val cacheKey = vaultFile.id + "_v"
        thumbnailCache.get(cacheKey)?.let { return it }

        val thumbCache = File(context.cacheDir, "thumb_cache").apply { if (!exists()) mkdirs() }
        val cachedThumb = File(thumbCache, cacheKey)
        if (cachedThumb.exists()) {
            val bitmap = BitmapFactory.decodeFile(cachedThumb.absolutePath)
            if (bitmap != null) {
                thumbnailCache.put(cacheKey, bitmap)
                return bitmap
            }
        }

        return try {
            val decryptedFile = openDecryptedFile(vaultFile) ?: return null
            val bitmap = decryptedFile.use { descriptor ->
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(descriptor.fileDescriptor)
                    retriever.getFrameAtTime(1000000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                } finally {
                    retriever.release()
                }
            }
            if (bitmap != null) {
                thumbnailCache.put(cacheKey, bitmap)
                FileOutputStream(cachedThumb).use { fos ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 70, fos)
                }
            }
            bitmap
        } catch (e: Exception) { null }
    }

    private fun getFileName(uri: Uri): String? {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index != -1) return cursor.getString(index)
            }
        }
        return null
    }

    private fun getFilePathFromUri(uri: Uri): String? {
        if (uri.scheme != "content") return uri.path
        context.contentResolver.query(uri, arrayOf("_data"), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex("_data")
                if (index != -1) return cursor.getString(index)
            }
        }
        return null
    }

    private inner class VaultDbHelper(context: Context) : SQLiteOpenHelper(context, "vault.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE files (id TEXT PRIMARY KEY, name TEXT, size INTEGER, mime TEXT, path TEXT, added INTEGER)")
        }
        override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {}
        fun insertFile(id: String, name: String, size: Long, mime: String, path: String?) {
            val values = ContentValues().apply {
                put("id", id); put("name", name); put("size", size)
                put("mime", mime); put("path", path); put("added", System.currentTimeMillis())
            }
            writableDatabase.insertWithOnConflict("files", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        }
        fun getFileById(id: String): VaultFile? {
            readableDatabase.query("files", null, "id=?", arrayOf(id), null, null, null).use { cursor ->
                if (cursor.moveToFirst()) {
                    return VaultFile(
                        cursor.getString(0), cursor.getString(1), cursor.getLong(2),
                        cursor.getString(3), cursor.getString(4), File(""), cursor.getLong(5)
                    )
                }
            }
            return null
        }

        fun getAllFiles(): List<VaultFile> {
            val list = mutableListOf<VaultFile>()
            readableDatabase.query("files", arrayOf("id", "name", "size", "mime", "path", "added"), null, null, null, null, "added DESC").use { cursor ->
                while (cursor.moveToNext()) {
                    list.add(VaultFile(cursor.getString(0), cursor.getString(1), cursor.getLong(2),
                        cursor.getString(3), cursor.getString(4), File(""), cursor.getLong(5)))
                }
            }
            return list
        }
        fun deleteFile(id: String) { writableDatabase.delete("files", "id=?", arrayOf(id)) }
        fun updateFileSize(id: String, size: Long) {
            val values = ContentValues().apply { put("size", size) }
            writableDatabase.update("files", values, "id=?", arrayOf(id))
        }
    }
}

data class VaultFile(
    val id: String, val name: String, val size: Long, val mimeType: String,
    val originalPath: String?, val file: File, val added: Long = 0L
)
