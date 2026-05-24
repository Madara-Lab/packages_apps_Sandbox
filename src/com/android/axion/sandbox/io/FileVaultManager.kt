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

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.os.UserHandle
import android.provider.BaseColumns
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import android.util.LruCache
import org.json.JSONObject
import java.io.*
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
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
    private val IO_BUFFER_SIZE = 65536

    private val dbHelper = VaultDbHelper(context)

    companion object {
        private const val TAG = "FileVaultManager"
        private const val BRIDGE_DIR_NAME = ".vault_bridge"
        private const val LEGACY_BRIDGE_DIR_NAME = "vault_bridge"
        private const val MEDIA_BRIDGE_DIR_NAME = "AxionVaultBridge"
        private const val MEDIA_BRIDGE_PREFIX = "vault_"
        private val mediaBridgeLocks = ConcurrentHashMap<String, Any>()
        private val thumbnailCache = LruCache<String, Bitmap>(50)

        internal fun getBridgeDir(context: Context): File {
            val baseDir = context.externalMediaDirs.filterNotNull().firstOrNull()
                ?: context.externalCacheDir
                ?: context.cacheDir
            return File(baseDir, BRIDGE_DIR_NAME).apply {
                if (!exists()) mkdirs()
                try { File(this, ".nomedia").createNewFile() } catch (e: Exception) {}
                setExecutable(true, false)
                setReadable(true, false)
            }
        }

        internal fun getBridgeFile(context: Context, vaultFile: VaultFile): File =
            File(getBridgeDir(context), "u${UserHandle.myUserId()}_${vaultFile.id}_${vaultFile.name}")

        internal fun clearBridgeCache(context: Context, force: Boolean = false) {
            val activeDir = getBridgeDir(context)
            clearBridgeDir(activeDir, force)
            if (force) {
                clearBridgeDir(File(context.cacheDir, BRIDGE_DIR_NAME), true)
                clearBridgeDir(File(context.cacheDir, LEGACY_BRIDGE_DIR_NAME), true)
                context.externalCacheDir?.let {
                    clearBridgeDir(File(it, BRIDGE_DIR_NAME), true)
                    clearBridgeDir(File(it, LEGACY_BRIDGE_DIR_NAME), true)
                }
                context.externalMediaDirs.filterNotNull().forEach {
                    clearBridgeDir(File(it, BRIDGE_DIR_NAME), true)
                    clearBridgeDir(File(it, LEGACY_BRIDGE_DIR_NAME), true)
                }
            }
        }

        internal fun clearDecryptedCache(context: Context) {
            clearMediaBridge(context)
            clearBridgeCache(context, true)
            File(context.cacheDir, "thumb_cache").deleteRecursively()
            context.cacheDir.listFiles { file -> file.name.startsWith("v_thumb_") }?.forEach { it.delete() }
            thumbnailCache.evictAll()
        }

        private fun mediaCollection(mimeType: String): Uri? = when {
            mimeType.startsWith("image/", true) ->
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            mimeType.startsWith("video/", true) ->
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else -> null
        }

        private fun mediaBridgeRelativePath(mimeType: String): String {
            val directory = if (mimeType.startsWith("video/", true)) {
                Environment.DIRECTORY_MOVIES
            } else {
                Environment.DIRECTORY_PICTURES
            }
            return "$directory/$MEDIA_BRIDGE_DIR_NAME/"
        }

        private fun mediaBridgeName(vaultFile: VaultFile): String =
            "$MEDIA_BRIDGE_PREFIX${UserHandle.myUserId()}_${vaultFile.id}_${vaultFile.name}"

        private fun findMediaBridgeUri(
            context: Context,
            collection: Uri,
            displayName: String,
            relativePath: String,
            expectedSize: Long
        ): Uri? = try {
            val projection = arrayOf(
                BaseColumns._ID,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.IS_PENDING
            )
            val selection = "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND " +
                "${MediaStore.MediaColumns.RELATIVE_PATH}=?"
            val args = arrayOf(displayName, relativePath)
            context.contentResolver.query(collection, projection, selection, args, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val uri = ContentUris.withAppendedId(collection, cursor.getLong(0))
                    if (cursor.getLong(1) == expectedSize && cursor.getInt(2) == 0) return uri
                    context.contentResolver.delete(uri, null, null)
                }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "findMediaBridgeUri failed name=$displayName", e)
            null
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

        private fun clearBridgeDir(dir: File, force: Boolean) {
            val files = dir.listFiles() ?: return
            val now = System.currentTimeMillis()
            files.forEach { file ->
                if (force || now - file.lastModified() > 300_000) file.delete()
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

    internal fun getMasterKey(): SecretKey? =
        if (VaultAccessController.isUnlocked()) getSecretKey() else null

    private fun decryptToOutput(vaultFile: VaultFile, output: OutputStream): Boolean {
        val key = getSecretKey() ?: run {
            Log.w(TAG, "decryptToOutput missing key id=${vaultFile.id}")
            return false
        }
        val cipher = Cipher.getInstance(ALGORITHM)
        FileInputStream(vaultFile.file).use { fis ->
            val iv = ByteArray(12)
            if (fis.read(iv) != 12) {
                Log.w(TAG, "decryptToOutput short iv id=${vaultFile.id}")
                return false
            }
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))

            val buffer = ByteArray(IO_BUFFER_SIZE)
            while (true) {
                val read = fis.read(buffer)
                if (read == -1) break

                val decrypted = cipher.update(buffer, 0, read)
                if (decrypted != null) output.write(decrypted)
            }
            val finalBlock = cipher.doFinal()
            if (finalBlock != null) output.write(finalBlock)
            output.flush()
        }
        return true
    }

    fun migrateLegacyIfNeeded() {
        if (!metadataFile.exists()) return
        try {
            val key = getSecretKey() ?: return
            val cipher = Cipher.getInstance(ALGORITHM)
            val json = FileInputStream(metadataFile).use { fis ->
                val iv = ByteArray(12)
                if (fis.read(iv) != 12) return
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

    fun prepareFileForSharing(vaultFile: VaultFile): Boolean {
        if (!VaultAccessController.isUnlocked()) {
            Log.w(TAG, "prepareFileForSharing denied locked id=${vaultFile.id}")
            return false
        }

        val tempFile = getBridgeFile(context, vaultFile)
        if (tempFile.exists() && tempFile.length() == vaultFile.size) {
            Log.i(TAG, "prepareFileForSharing bridge hit id=${vaultFile.id} length=${tempFile.length()} path=${tempFile.absolutePath}")
            return true
        }

        return try {
            FileOutputStream(tempFile).use { output ->
                if (!decryptToOutput(vaultFile, output)) {
                    tempFile.delete()
                    return false
                }
            }
            if (!VaultAccessController.isUnlocked()) {
                tempFile.delete()
                return false
            }
            tempFile.parentFile?.setExecutable(true, false)
            tempFile.parentFile?.setReadable(true, false)
            tempFile.setReadable(true, false)
            Log.i(TAG, "prepareFileForSharing ready id=${vaultFile.id} length=${tempFile.length()} expected=${vaultFile.size} path=${tempFile.absolutePath}")
            true
        } catch (e: Exception) {
            Log.w(TAG, "prepareFileForSharing failed id=${vaultFile.id}", e)
            tempFile.delete()
            false
        }
    }

    fun prepareMediaStoreUri(vaultFile: VaultFile): Uri? =
        synchronized(mediaBridgeLocks.computeIfAbsent(vaultFile.id) { Any() }) {
            prepareMediaStoreUriLocked(vaultFile)
        }

    private fun prepareMediaStoreUriLocked(vaultFile: VaultFile): Uri? {
        val collection = mediaCollection(vaultFile.mimeType) ?: return null
        if (!VaultAccessController.isUnlocked()) {
            Log.w(TAG, "prepareMediaStoreUri denied locked id=${vaultFile.id}")
            return null
        }

        val relativePath = mediaBridgeRelativePath(vaultFile.mimeType)
        val displayName = mediaBridgeName(vaultFile)
        findMediaBridgeUri(
            context,
            collection,
            displayName,
            relativePath,
            vaultFile.size
        )?.let {
            Log.i(TAG, "prepareMediaStoreUri hit id=${vaultFile.id} uri=$it")
            return it
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, vaultFile.mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.SIZE, vaultFile.size)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            put(MediaStore.MediaColumns.DATE_MODIFIED, System.currentTimeMillis() / 1000)
        }

        val uri = try {
            context.contentResolver.insert(collection, values)
        } catch (e: Exception) {
            Log.w(TAG, "prepareMediaStoreUri insert failed id=${vaultFile.id}", e)
            null
        } ?: return null

        return try {
            context.contentResolver.openOutputStream(uri, "w")?.use { output ->
                if (!decryptToOutput(vaultFile, output)) {
                    context.contentResolver.delete(uri, null, null)
                    return null
                }
            } ?: run {
                context.contentResolver.delete(uri, null, null)
                return null
            }
            if (!VaultAccessController.isUnlocked()) {
                context.contentResolver.delete(uri, null, null)
                return null
            }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            values.put(MediaStore.MediaColumns.SIZE, vaultFile.size)
            values.put(MediaStore.MediaColumns.DATE_MODIFIED, System.currentTimeMillis() / 1000)
            context.contentResolver.update(uri, values, null, null)
            Log.i(TAG, "prepareMediaStoreUri ready id=${vaultFile.id} uri=$uri length=${vaultFile.size}")
            uri
        } catch (e: Exception) {
            Log.w(TAG, "prepareMediaStoreUri failed id=${vaultFile.id}", e)
            context.contentResolver.delete(uri, null, null)
            null
        }
    }

    fun importFile(uri: Uri): Boolean {
        return try {
            val originalName = getFileName(uri) ?: "file_${System.currentTimeMillis()}"
            val size = getFileSize(uri)
            val mimeType = resolveMimeType(originalName, uri)
            val fileId = UUID.randomUUID().toString()
            val destFile = File(vaultDir, "$fileId.bin")
            
            val key = getSecretKey() ?: return false
            val cipher = Cipher.getInstance(ALGORITHM)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            
            context.contentResolver.openInputStream(uri)?.use { isStream ->
                FileOutputStream(destFile).use { fos ->
                    fos.write(cipher.iv)
                    val buffer = ByteArray(IO_BUFFER_SIZE)
                    while (true) {
                        val read = isStream.read(buffer)
                        if (read == -1) break
                        val encrypted = cipher.update(buffer, 0, read)
                        if (encrypted != null) fos.write(encrypted)
                    }
                    val finalBlock = cipher.doFinal()
                    if (finalBlock != null) fos.write(finalBlock)
                    fos.flush()
                }
            } ?: return false
            
            dbHelper.insertFile(fileId, originalName, size, mimeType, getFilePathFromUri(uri))
            cleanupOriginal(uri, getFilePathFromUri(uri))
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

        return try {
            val key = getSecretKey() ?: return false
            val cipher = Cipher.getInstance(ALGORITHM)
            FileInputStream(vaultFile.file).use { fis ->
                val iv = ByteArray(12)
                if (fis.read(iv) != 12) return false
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
                FileOutputStream(targetFile).use { fos ->
                    val buffer = ByteArray(IO_BUFFER_SIZE)
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
            }
            val scanIntent = android.content.Intent(android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE).apply {
                data = Uri.fromFile(targetFile)
            }
            context.sendBroadcast(scanIntent)
            deleteFile(vaultFile)
            true
        } catch (e: Exception) { false }
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
        return if (binFile.exists()) entry.copy(file = binFile) else null
    }

    fun getVaultFiles(): List<VaultFile> {
        val dbFiles = dbHelper.getAllFiles()
        val binFiles = vaultDir.listFiles { f -> f.extension == "bin" }?.associateBy { it.nameWithoutExtension } ?: emptyMap()
        return dbFiles.mapNotNull { entry ->
            val binFile = binFiles[entry.id]
            if (binFile != null) entry.copy(file = binFile)
            else { dbHelper.deleteFile(entry.id); null }
        }
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
            val key = getSecretKey() ?: return null
            val cipher = Cipher.getInstance(ALGORITHM)
            FileInputStream(vaultFile.file).use { fis ->
                val iv = ByteArray(12)
                if (fis.read(iv) != 12) return null
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
                val data = fis.readBytes()
                val decryptedData = cipher.doFinal(data)
                val options = BitmapFactory.Options().apply { inSampleSize = 4 }
                val bitmap = BitmapFactory.decodeByteArray(decryptedData, 0, decryptedData.size, options)
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
            val tempFile = File(context.cacheDir, "v_thumb_${vaultFile.id}.mp4")
            val key = getSecretKey() ?: return null
            val cipher = Cipher.getInstance(ALGORITHM)
            FileInputStream(vaultFile.file).use { fis ->
                val iv = ByteArray(12)
                if (fis.read(iv) != 12) return null
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
                
                FileOutputStream(tempFile).use { fos ->
                    val buffer = ByteArray(IO_BUFFER_SIZE)
                    var totalRead = 0
                    val maxRead = 1024 * 512
                    while (totalRead < maxRead) {
                        val read = fis.read(buffer, 0, minOf(buffer.size, maxRead - totalRead))
                        if (read == -1) break
                        val decrypted = cipher.update(buffer, 0, read)
                        if (decrypted != null) {
                            fos.write(decrypted)
                            totalRead += decrypted.size
                        }
                    }
                    val finalBlock = cipher.doFinal()
                    if (finalBlock != null) fos.write(finalBlock)
                }
            }
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(tempFile.absolutePath)
            val bitmap = retriever.getFrameAtTime(1000000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            retriever.release()
            tempFile.delete()
            if (bitmap != null) {
                thumbnailCache.put(cacheKey, bitmap)
                FileOutputStream(cachedThumb).use { fos ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 70, fos)
                }
            }
            bitmap
        } catch (e: Exception) { null }
    }

    fun clearPublicBridge(force: Boolean = false) {
        if (force) clearDecryptedCache(context) else clearBridgeCache(context)
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

    private fun getFileSize(uri: Uri): Long {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index != -1) return cursor.getLong(index)
            }
        }
        return 0L
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
    }
}

data class VaultFile(
    val id: String, val name: String, val size: Long, val mimeType: String,
    val originalPath: String?, val file: File, val added: Long = 0L
)
