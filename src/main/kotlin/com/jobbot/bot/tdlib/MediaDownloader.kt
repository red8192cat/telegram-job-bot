package com.jobbot.bot.tdlib

import com.jobbot.data.models.MediaAttachment
import com.jobbot.data.models.MediaType
import com.jobbot.infrastructure.monitoring.ErrorTracker
import com.jobbot.shared.getLogger
import kotlinx.coroutines.*
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.*

/**
 * Downloads media attachments from TDLib messages
 * FOCUSED FIX: Handle Unicode filename encoding mismatch between TDLib and filesystem
 */
class MediaDownloader {
    private val logger = getLogger("MediaDownloader")
    
    companion object {
        private const val MAX_FILE_SIZE = 50 * 1024 * 1024L // 50MB limit
        private const val DOWNLOAD_TIMEOUT_MS = 45000L // 45 seconds per file
        private const val DOWNLOAD_POLL_INTERVAL_MS = 500L // Check every 500ms
        private const val MAX_POLL_ATTEMPTS = 90 // 45 seconds / 500ms
        private const val TMP_DIR = "/tmp/jobbot_media"
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
     * FOCUSED FIX: Download file with proper verification and retry logic
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
            
            // ENHANCED: Force download with higher priority and offset 0
            client.send(TdApi.DownloadFile(fileId, 32, 0, 0, true)) { result ->
                when (result) {
                    is TdApi.File -> {
                        logger.debug { "Download response: completed=${result.local.isDownloadingCompleted}, size=${result.size}, local_size=${result.local.downloadedSize}" }
                        
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
            
            // ENHANCED: More aggressive polling with progress tracking
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
     * FOCUSED FIX: Handle completed download with strict verification
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
            
            val parentDir = File(tdlibSourcePath).parentFile
            if (parentDir?.exists() != true) {
                logger.warn { "Parent directory doesn't exist: ${parentDir?.absolutePath}" }
                deferred.complete(null)
                return
            }
            
            logger.debug { "Listing files in directory: ${parentDir.absolutePath}" }
            
            // Find file that matches the expected size AND is recent AND is not empty
            val now = System.currentTimeMillis()
            val candidateFiles = parentDir.listFiles()
                ?.filter { 
                    it.isFile && 
                    it.length() > 0 &&  // MUST have content
                    it.canRead() &&
                    it.length() == expectedSize &&  // EXACT size match
                    (now - it.lastModified()) < 300000  // Modified within last 5 minutes
                }
                ?.sortedByDescending { it.lastModified() }
            
            if (candidateFiles.isNullOrEmpty()) {
                logger.warn { "No files found matching size $expectedSize in directory: ${parentDir.absolutePath}" }
                
                // Debug: show what files are actually there
                val allFiles = parentDir.listFiles()
                logger.debug { "Directory contains ${allFiles?.size ?: 0} files:" }
                allFiles?.forEach { file ->
                    val age = (now - file.lastModified()) / 1000
                    logger.debug { "  ${file.name}: ${file.length()} bytes (age: ${age}s)" }
                }
                
                // FALLBACK: Try to find ANY file with the correct size (ignore timestamp)
                val sizeMatcher = allFiles?.find { 
                    it.isFile && it.length() == expectedSize && it.length() > 0 && it.canRead()
                }
                
                if (sizeMatcher != null) {
                    logger.info { "Found size-matching file ignoring timestamp: ${sizeMatcher.name}" }
                    copyFileToTarget(sizeMatcher, targetPath, deferred)
                    return
                } else {
                    logger.warn { "No files with correct size $expectedSize found at all" }
                    deferred.complete(null)
                    return
                }
            }
            
            val actualFile = candidateFiles.first()
            logger.info { "Found matching file: ${actualFile.name} (${actualFile.length()} bytes)" }
            
            copyFileToTarget(actualFile, targetPath, deferred)
            
        } catch (e: Exception) {
            logger.error(e) { "Error handling completed download" }
            deferred.complete(null)
        }
    }
    
    /**
     * Copy file to target with verification
     */
    private fun copyFileToTarget(
        sourceFile: File,
        targetPath: String,
        deferred: CompletableDeferred<String?>
    ) {
        try {
            val targetFile = File(targetPath)
            sourceFile.copyTo(targetFile, overwrite = true)
            
            // Verify copy
            if (!targetFile.exists() || targetFile.length() != sourceFile.length() || targetFile.length() == 0L) {
                logger.warn { "File copy verification failed: exists=${targetFile.exists()}, size=${targetFile.length()}/${sourceFile.length()}" }
                deferred.complete(null)
                return
            }
            
            logger.info { "Successfully copied file to: $targetPath (${targetFile.length()} bytes)" }
            deferred.complete(targetPath)
            
        } catch (e: Exception) {
            logger.error(e) { "Error copying file to target" }
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