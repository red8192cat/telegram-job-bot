package com.jobbot.bot.tdlib

import com.jobbot.data.models.MediaAttachment
import com.jobbot.data.models.MediaType
import com.jobbot.infrastructure.monitoring.ErrorTracker
import com.jobbot.shared.getLogger
import kotlinx.coroutines.*
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.io.File
import java.util.*

/**
 * Downloads media attachments from TDLib messages
 * ENHANCED: Proper UTF-8 encoding support for Unicode filenames
 */
class MediaDownloader {
    private val logger = getLogger("MediaDownloader")
    
    companion object {
        private const val MAX_FILE_SIZE = 50 * 1024 * 1024L // 50MB limit
        private const val DOWNLOAD_TIMEOUT_MS = 45000L // 45 seconds per file
        private const val DOWNLOAD_POLL_INTERVAL_MS = 500L // Check every 500ms
        private const val MAX_POLL_ATTEMPTS = 90 // 45 seconds / 500ms
        private const val TMP_DIR = "/tmp/jobbot_media"
        
        @JvmStatic
        private var utf8Verified = false
    }
    
    init {
        // Ensure temp directory exists
        File(TMP_DIR).mkdirs()
    }
    
    /**
     * Download all media attachments from a TDLib message
     */
    suspend fun downloadMessageMedia(
        message: TdApi.Message, 
        client: Client?
    ): List<MediaAttachment> = withContext(Dispatchers.IO) {
        
        if (client == null) {
            logger.debug { "No TDLib client available for media download" }
            return@withContext emptyList()
        }
        
        val attachments = mutableListOf<MediaAttachment>()
        
        try {
            when (val content = message.content) {
                is TdApi.MessageAudio -> {
                    logger.debug { "Processing audio message" }
                    downloadAudio(content, client, content.caption?.text)?.let { 
                        attachments.add(it) 
                    }
                }
                
                is TdApi.MessagePhoto -> {
                    logger.debug { "Processing photo message" }
                    downloadPhoto(content, client, content.caption?.text)?.let { 
                        attachments.add(it) 
                    }
                }
                
                is TdApi.MessageVideo -> {
                    logger.debug { "Processing video message" }
                    downloadVideo(content, client, content.caption?.text)?.let { 
                        attachments.add(it) 
                    }
                }
                
                is TdApi.MessageDocument -> {
                    logger.debug { "Processing document message" }
                    downloadDocument(content, client, content.caption?.text)?.let { 
                        attachments.add(it) 
                    }
                }
                
                is TdApi.MessageVoiceNote -> {
                    logger.debug { "Processing voice note message" }
                    downloadVoiceNote(content, client, null)?.let { 
                        attachments.add(it) 
                    }
                }
                
                is TdApi.MessageAnimation -> {
                    logger.debug { "Processing animation/GIF message" }
                    downloadAnimation(content, client, content.caption?.text)?.let { 
                        attachments.add(it) 
                    }
                }
                
                else -> {
                    logger.debug { "Message type ${content.javaClass.simpleName} - no media to download" }
                }
            }
            
            logger.debug { "Downloaded ${attachments.size} media attachments for message ${message.id}" }
            
        } catch (e: Exception) {
            logger.error(e) { "Error downloading media for message ${message.id}" }
            ErrorTracker.logError("ERROR", "Media download failed: ${e.message}", e)
        }
        
        attachments
    }
    
    private suspend fun downloadAudio(
        content: TdApi.MessageAudio, 
        client: Client,
        caption: String?
    ): MediaAttachment? {
        val audio = content.audio
        
        if (audio.audio.size > MAX_FILE_SIZE) {
            logger.debug { "Audio too large: ${audio.audio.size / 1024 / 1024}MB (limit: ${MAX_FILE_SIZE / 1024 / 1024}MB)" }
            return null
        }
        
        val originalFileName = audio.fileName.ifBlank { "audio_${UUID.randomUUID()}.mp3" }
        val localPath = downloadFile(client, audio.audio.id, originalFileName)
        
        return if (localPath != null) {
            MediaAttachment(
                type = MediaType.AUDIO,
                filePath = localPath,
                originalFileName = originalFileName,
                fileSize = audio.audio.size.toLong(),
                mimeType = audio.mimeType,
                caption = caption,
                duration = audio.duration
            )
        } else null
    }
    
    private suspend fun downloadPhoto(
        content: TdApi.MessagePhoto, 
        client: Client,
        caption: String?
    ): MediaAttachment? {
        val photoSize = content.photo.sizes
            .filter { it.photo.size <= MAX_FILE_SIZE }
            .maxByOrNull { it.width * it.height }
        
        if (photoSize == null) {
            logger.debug { "No suitable photo size found" }
            return null
        }
        
        val localPath = downloadFile(client, photoSize.photo.id, "photo_${UUID.randomUUID()}.jpg")
        
        return if (localPath != null) {
            MediaAttachment(
                type = MediaType.PHOTO,
                filePath = localPath,
                originalFileName = "photo.jpg",
                fileSize = photoSize.photo.size.toLong(),
                mimeType = "image/jpeg",
                caption = caption,
                width = photoSize.width,
                height = photoSize.height
            )
        } else null
    }
    
    private suspend fun downloadVideo(
        content: TdApi.MessageVideo, 
        client: Client,
        caption: String?
    ): MediaAttachment? {
        val video = content.video
        
        if (video.video.size > MAX_FILE_SIZE) {
            logger.debug { "Video too large: ${video.video.size / 1024 / 1024}MB" }
            return null
        }
        
        val extension = if (video.fileName.contains(".")) {
            video.fileName.substringAfterLast(".")
        } else "mp4"
        
        val localPath = downloadFile(client, video.video.id, "video_${UUID.randomUUID()}.$extension")
        
        return if (localPath != null) {
            MediaAttachment(
                type = MediaType.VIDEO,
                filePath = localPath,
                originalFileName = video.fileName.ifBlank { "video.$extension" },
                fileSize = video.video.size.toLong(),
                mimeType = video.mimeType,
                caption = caption,
                width = video.width,
                height = video.height,
                duration = video.duration
            )
        } else null
    }
    
    private suspend fun downloadDocument(
        content: TdApi.MessageDocument, 
        client: Client,
        caption: String?
    ): MediaAttachment? {
        val document = content.document
        
        if (document.document.size > MAX_FILE_SIZE) {
            logger.debug { "Document too large: ${document.document.size / 1024 / 1024}MB" }
            return null
        }
        
        val originalFileName = document.fileName.ifBlank { "document_${UUID.randomUUID()}" }
        val localPath = downloadFile(client, document.document.id, originalFileName)
        
        return if (localPath != null) {
            MediaAttachment(
                type = MediaType.DOCUMENT,
                filePath = localPath,
                originalFileName = originalFileName,
                fileSize = document.document.size.toLong(),
                mimeType = document.mimeType,
                caption = caption
            )
        } else null
    }
    
    private suspend fun downloadVoiceNote(
        content: TdApi.MessageVoiceNote, 
        client: Client,
        caption: String?
    ): MediaAttachment? {
        val voice = content.voiceNote
        
        if (voice.voice.size > MAX_FILE_SIZE) {
            logger.debug { "Voice note too large: ${voice.voice.size / 1024 / 1024}MB" }
            return null
        }
        
        val localPath = downloadFile(client, voice.voice.id, "voice_${UUID.randomUUID()}.ogg")
        
        return if (localPath != null) {
            MediaAttachment(
                type = MediaType.VOICE,
                filePath = localPath,
                originalFileName = "voice.ogg",
                fileSize = voice.voice.size.toLong(),
                mimeType = voice.mimeType,
                caption = caption,
                duration = voice.duration
            )
        } else null
    }
    
    private suspend fun downloadAnimation(
        content: TdApi.MessageAnimation, 
        client: Client,
        caption: String?
    ): MediaAttachment? {
        val animation = content.animation
        
        if (animation.animation.size > MAX_FILE_SIZE) {
            logger.debug { "Animation too large: ${animation.animation.size / 1024 / 1024}MB" }
            return null
        }
        
        val originalFileName = animation.fileName.ifBlank { "animation_${UUID.randomUUID()}.gif" }
        val localPath = downloadFile(client, animation.animation.id, originalFileName)
        
        return if (localPath != null) {
            MediaAttachment(
                type = MediaType.ANIMATION,
                filePath = localPath,
                originalFileName = originalFileName,
                fileSize = animation.animation.size.toLong(),
                mimeType = animation.mimeType,
                caption = caption,
                width = animation.width,
                height = animation.height,
                duration = animation.duration
            )
        } else null
    }
    
    /**
     * Download file with proper verification and retry logic
     */
    private suspend fun downloadFile(
        client: Client, 
        fileId: Int, 
        fileName: String
    ): String? = withContext(Dispatchers.IO) {
        
        try {
            val safeFileName = generateSafeFilename(fileName)
            val targetPath = File(TMP_DIR, safeFileName).absolutePath
            val deferred = CompletableDeferred<String?>()
            
            logger.debug { "Starting download: fileId=$fileId, targetPath=$targetPath" }
            
            // Force download with higher priority and offset 0
            client.send(TdApi.DownloadFile(fileId, 32, 0, 0, true)) { result ->
                when (result) {
                    is TdApi.File -> {
                        logger.debug { "Download response: completed=${result.local.isDownloadingCompleted}, size=${result.size}, local_size=${result.local.downloadedSize}" }
                        logger.debug { "Download paths: remote=${result.remote.id}, local=${result.local.path}" }
                        
                        if (result.local.isDownloadingCompleted && result.local.downloadedSize == result.size) {
                            handleCompletedDownload(result, targetPath, deferred)
                        } else {
                            logger.debug { "Download started for fileId=$fileId, polling... (progress: ${result.local.downloadedSize}/${result.size})" }
                        }
                    }
                    is TdApi.Error -> {
                        logger.warn { "Download failed for fileId=$fileId: ${result.message}" }
                        deferred.complete(null)
                    }
                }
            }
            
            // Aggressive polling with progress tracking
            val pollJob = launch {
                var pollCount = 0
                var lastProgress = 0L
                var stuckCount = 0
                
                while (isActive && !deferred.isCompleted && pollCount < MAX_POLL_ATTEMPTS) {
                    delay(DOWNLOAD_POLL_INTERVAL_MS)
                    pollCount++
                    
                    client.send(TdApi.GetFile(fileId)) { fileResult ->
                        when (fileResult) {
                            is TdApi.File -> {
                                val progress = fileResult.local.downloadedSize
                                val total = fileResult.size
                                
                                logger.debug { "Poll $pollCount: Progress $progress/$total bytes (${(progress * 100 / total.coerceAtLeast(1))}%)" }
                                
                                if (fileResult.local.isDownloadingCompleted && progress == total && total > 0) {
                                    logger.debug { "Download completed successfully: $progress bytes" }
                                    handleCompletedDownload(fileResult, targetPath, deferred)
                                } else if (progress == lastProgress) {
                                    stuckCount++
                                    if (stuckCount > 10) { // 5 seconds without progress
                                        logger.warn { "Download appears stuck at $progress bytes, retrying..." }
                                        // Retry the download
                                        client.send(TdApi.DownloadFile(fileId, 32, 0, 0, true)) { }
                                        stuckCount = 0
                                    }
                                } else {
                                    stuckCount = 0
                                    lastProgress = progress
                                }
                            }
                            is TdApi.Error -> {
                                logger.warn { "Error getting file status: ${fileResult.message}" }
                                deferred.complete(null)
                            }
                        }
                    }
                }
                
                if (pollCount >= MAX_POLL_ATTEMPTS && !deferred.isCompleted) {
                    logger.warn { "Download timeout for fileId=$fileId after $pollCount polls" }
                    deferred.complete(null)
                }
            }
            
            try {
                withTimeout(DOWNLOAD_TIMEOUT_MS + 1000) {
                    deferred.await()
                }
            } catch (e: TimeoutCancellationException) {
                logger.warn { "Overall timeout for fileId=$fileId" }
                pollJob.cancel()
                null
            } finally {
                pollJob.cancel()
            }
            
        } catch (e: Exception) {
            logger.error(e) { "Error downloading file $fileId" }
            null
        }
    }
    
    /**
     * CLEAN SOLUTION: Handle completed download with proper UTF-8 configuration
     */
    private fun handleCompletedDownload(
        file: TdApi.File,
        targetPath: String,
        deferred: CompletableDeferred<String?>
    ) {
        try {
            val tdlibSourcePath = file.local.path
            val expectedSize = file.size.toLong()
            val downloadedSize = file.local.downloadedSize.toLong()
            
            if (tdlibSourcePath.isNullOrBlank()) {
                logger.warn { "TDLib source path is empty" }
                deferred.complete(null)
                return
            }
            
            // STRICT VERIFICATION: Must have downloaded the full file
            if (downloadedSize != expectedSize) {
                logger.warn { "Download incomplete: downloaded $downloadedSize/$expectedSize bytes" }
                deferred.complete(null)
                return
            }
            
            if (expectedSize == 0L) {
                logger.warn { "File has 0 bytes - invalid download" }
                deferred.complete(null)
                return
            }
            
            logger.debug { "TDLib reports file at: '$tdlibSourcePath' (verified size: $expectedSize bytes)" }
            
            // Verify UTF-8 encoding is working (one-time check)
            verifyUtf8Configuration()
            
            // Try direct file access first (should work with proper UTF-8)
            val directFile = File(tdlibSourcePath)
            if (tryDirectFileAccess(directFile, expectedSize, targetPath, deferred)) {
                return
            }
            
            // Fallback: Use NIO approach
            val parentDir = directFile.parentFile
            if (parentDir?.exists() == true) {
                val sourceFile = findFileWithNio(parentDir, expectedSize)
                if (sourceFile != null) {
                    logger.info { "✅ Found file via NIO fallback: ${sourceFile.fileName}" }
                    copyFileWithNio(sourceFile, targetPath, deferred)
                    return
                }
            } else {
                logger.warn { "Parent directory does not exist: ${parentDir?.absolutePath}" }
            }
            
            logger.warn { "Could not locate file with any approach" }
            deferred.complete(null)
            
        } catch (e: Exception) {
            logger.error(e) { "Error handling completed download" }
            deferred.complete(null)
        }
    }
    
    /**
     * Verify UTF-8 encoding configuration (one-time check)
     */
    private fun verifyUtf8Configuration() {
        // Only run this check once per instance
        if (utf8Verified) return
        
        val fileEncoding = System.getProperty("file.encoding")
        val sunJnuEncoding = System.getProperty("sun.jnu.encoding")
        val defaultCharset = java.nio.charset.Charset.defaultCharset()
        
        logger.info { "UTF-8 Configuration Check:" }
        logger.info { "  file.encoding: $fileEncoding" }
        logger.info { "  sun.jnu.encoding: $sunJnuEncoding" }
        logger.info { "  default charset: $defaultCharset" }
        
        if (fileEncoding == "UTF-8" && sunJnuEncoding == "UTF-8") {
            logger.info { "✅ UTF-8 encoding properly configured!" }
        } else {
            logger.warn { "⚠️ UTF-8 encoding not fully configured - some Unicode filenames may fail" }
            logger.warn { "  Consider setting JVM args: -Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8" }
        }
        
        // Mark as checked
        utf8Verified = true
    }
    
    /**
     * Try direct file access (should work with proper UTF-8 encoding)
     */
    private fun tryDirectFileAccess(
        directFile: File, 
        expectedSize: Long, 
        targetPath: String, 
        deferred: CompletableDeferred<String?>
    ): Boolean {
        try {
            if (!directFile.exists()) {
                logger.debug { "Direct file access failed: file does not exist" }
                return false
            }
            
            if (!directFile.isFile()) {
                logger.debug { "Direct file access failed: not a regular file" }
                return false
            }
            
            val actualSize = directFile.length()
            if (actualSize != expectedSize) {
                logger.debug { "Direct file access failed: size mismatch (expected $expectedSize, got $actualSize)" }
                return false
            }
            
            if (actualSize == 0L) {
                logger.debug { "Direct file access failed: file has 0 bytes" }
                return false
            }
            
            // Test actual read access
            directFile.inputStream().use { stream ->
                val buffer = ByteArray(1024)
                val bytesRead = stream.read(buffer)
                if (bytesRead > 0) {
                    logger.info { "✅ Direct file access successful with UTF-8! ($actualSize bytes)" }
                    copyFileToTarget(directFile, targetPath, deferred)
                    return true
                }
            }
            
            return false
            
        } catch (e: Exception) {
            logger.debug { "Direct file access failed: ${e.javaClass.simpleName} - ${e.message}" }
            return false
        }
    }
    
    /**
     * Find file using NIO which properly handles the filesystem encoding
     */
    private fun findFileWithNio(directory: File, expectedSize: Long): java.nio.file.Path? {
        return try {
            logger.info { "Searching for file with NIO (size: $expectedSize bytes)" }
            
            val dirPath = directory.toPath()
            
            // Use NIO directory stream which handles filesystem encoding correctly
            java.nio.file.Files.newDirectoryStream(dirPath).use { stream ->
                for (filePath in stream) {
                    try {
                        // Check if it's a regular file
                        if (!java.nio.file.Files.isRegularFile(filePath)) continue
                        
                        // Check size using NIO (more reliable than File.length())
                        val fileSize = java.nio.file.Files.size(filePath)
                        
                        if (fileSize == expectedSize) {
                            logger.info { "Found matching file: size=$fileSize, path=${filePath.fileName}" }
                            
                            // Verify we can actually read this file
                            if (java.nio.file.Files.isReadable(filePath)) {
                                logger.info { "File is readable via NIO" }
                                return filePath
                            } else {
                                logger.warn { "File found but not readable" }
                            }
                        }
                    } catch (e: Exception) {
                        logger.debug { "Error checking file ${filePath.fileName}: ${e.message}" }
                    }
                }
            }
            
            logger.warn { "No matching files found in directory" }
            null
            
        } catch (e: Exception) {
            logger.error(e) { "Error in findFileWithNio" }
            null
        }
    }
    
    /**
     * Copy file using NIO to bypass encoding issues
     */
    private fun copyFileWithNio(
        sourcePath: java.nio.file.Path,
        targetPath: String,
        deferred: CompletableDeferred<String?>
    ) {
        try {
            val targetNioPath = java.nio.file.Paths.get(targetPath)
            
            // Ensure target directory exists
            targetNioPath.parent?.let { parent ->
                java.nio.file.Files.createDirectories(parent)
            }
            
            logger.debug { "Copying with NIO: ${sourcePath.fileName} -> $targetPath" }
            
            // Use NIO copy which handles encoding properly
            java.nio.file.Files.copy(
                sourcePath, 
                targetNioPath, 
                java.nio.file.StandardCopyOption.REPLACE_EXISTING
            )
            
            // Verify the copy using NIO
            if (java.nio.file.Files.exists(targetNioPath)) {
                val copiedSize = java.nio.file.Files.size(targetNioPath)
                val originalSize = java.nio.file.Files.size(sourcePath)
                
                if (copiedSize == originalSize && copiedSize > 0) {
                    // Final verification: try to read first few bytes
                    try {
                        java.nio.file.Files.newInputStream(targetNioPath).use { stream ->
                            val testBytes = stream.readNBytes(1024)
                            if (testBytes.isNotEmpty()) {
                                logger.info { "✅ File successfully copied with NIO ($copiedSize bytes)" }
                                deferred.complete(targetPath)
                                return
                            }
                        }
                    } catch (e: Exception) {
                        logger.warn { "Copied file not readable: ${e.message}" }
                    }
                } else {
                    logger.warn { "Copy verification failed: original=$originalSize, copied=$copiedSize" }
                }
            } else {
                logger.warn { "Target file does not exist after NIO copy" }
            }
            
            logger.warn { "NIO copy failed verification" }
            deferred.complete(null)
            
        } catch (e: Exception) {
            logger.error(e) { "Error copying file with NIO: ${e.message}" }
            deferred.complete(null)
        }
    }
    
    /**
     * Enhanced copy verification using NIO instead of legacy File operations
     */
    private fun copyFileToTarget(
        sourceFile: File,
        targetPath: String,
        deferred: CompletableDeferred<String?>
    ) {
        try {
            // Use NIO for more reliable file operations
            val sourcePath = sourceFile.toPath()
            val targetNioPath = java.nio.file.Paths.get(targetPath)
            
            // Ensure target directory exists
            targetNioPath.parent?.let { parent ->
                java.nio.file.Files.createDirectories(parent)
            }
            
            logger.debug { "Copying ${sourcePath.fileName} to $targetPath" }
            
            // Use NIO copy for better reliability
            java.nio.file.Files.copy(
                sourcePath, 
                targetNioPath, 
                java.nio.file.StandardCopyOption.REPLACE_EXISTING
            )
            
            // Verify using NIO
            if (java.nio.file.Files.exists(targetNioPath)) {
                val copiedSize = java.nio.file.Files.size(targetNioPath)
                val originalSize = java.nio.file.Files.size(sourcePath)
                
                if (copiedSize == originalSize && copiedSize > 0) {
                    // Test readability
                    try {
                        java.nio.file.Files.newInputStream(targetNioPath).use { stream ->
                            val testByte = stream.read()
                            if (testByte != -1) {
                                logger.info { "Successfully copied and verified file: $targetPath ($copiedSize bytes)" }
                                deferred.complete(targetPath)
                                return
                            }
                        }
                    } catch (e: Exception) {
                        logger.warn { "Copied file not readable: ${e.message}" }
                    }
                } else {
                    logger.warn { "Size verification failed: original=$originalSize, copied=$copiedSize" }
                }
            } else {
                logger.warn { "Target file does not exist after copy" }
            }
            
            deferred.complete(null)
            
        } catch (e: Exception) {
            logger.error(e) { "Error copying file to target: ${e.message}" }
            deferred.complete(null)
        }
    }
    
    /**
     * Generate a safe ASCII filename to avoid Unicode issues
     */
    private fun generateSafeFilename(originalName: String): String {
        val extension = if (originalName.contains('.')) {
            "." + originalName.substringAfterLast('.')
        } else ""
        
        val timestamp = System.currentTimeMillis()
        val random = (1000..9999).random()
        
        return "media_${timestamp}_${random}$extension"
    }
    
    /**
     * Clean up downloaded media files
     */
    fun cleanupMediaFiles(attachments: List<MediaAttachment>) {
        attachments.forEach { attachment ->
            try {
                val file = File(attachment.filePath)
                if (file.exists() && file.delete()) {
                    logger.debug { "Cleaned up: ${attachment.filePath}" }
                }
            } catch (e: Exception) {
                logger.warn(e) { "Error cleaning up: ${attachment.filePath}" }
            }
        }
    }
    
    /**
     * Clean up old temp files
     */
    fun cleanupOldTempFiles() {
        try {
            val tempDir = File(TMP_DIR)
            if (!tempDir.exists()) return
            
            val oneHourAgo = System.currentTimeMillis() - (60 * 60 * 1000)
            var cleanedCount = 0
            
            tempDir.listFiles()?.forEach { file ->
                if (file.lastModified() < oneHourAgo && file.delete()) {
                    cleanedCount++
                }
            }
            
            if (cleanedCount > 0) {
                logger.debug { "Cleaned up $cleanedCount old temp files" }
            }
            
        } catch (e: Exception) {
            logger.warn(e) { "Error cleaning up old temp files" }
        }
    }
}