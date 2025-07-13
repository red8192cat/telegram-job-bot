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
 * FIXED: Robust error handling for TDLib download issues
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
    
    /**
     * Sanitize filename to avoid Unicode, special characters, and filesystem issues
     * FIXED: Handle Cyrillic characters, spaces, and other problematic characters
     */
    private fun sanitizeFilename(originalName: String): String {
        try {
            var sanitized = originalName
            
            // 1. Replace problematic characters with safe alternatives
            sanitized = sanitized
                // Replace multiple spaces with single underscore
                .replace(Regex("\\s+"), "_")
                // Replace Cyrillic and other non-ASCII with transliteration or removal
                .replace(Regex("[а-яё]", RegexOption.IGNORE_CASE)) { match ->
                    // Simple Cyrillic transliteration
                    when (match.value.lowercase()) {
                        "а" -> "a"; "б" -> "b"; "в" -> "v"; "г" -> "g"; "д" -> "d"
                        "е" -> "e"; "ё" -> "yo"; "ж" -> "zh"; "з" -> "z"; "и" -> "i"
                        "й" -> "y"; "к" -> "k"; "л" -> "l"; "м" -> "m"; "н" -> "n"
                        "о" -> "o"; "п" -> "p"; "р" -> "r"; "с" -> "s"; "т" -> "t"
                        "у" -> "u"; "ф" -> "f"; "х" -> "h"; "ц" -> "ts"; "ч" -> "ch"
                        "ш" -> "sh"; "щ" -> "sch"; "ъ" -> ""; "ы" -> "y"; "ь" -> ""
                        "э" -> "e"; "ю" -> "yu"; "я" -> "ya"
                        else -> "_"
                    }
                }
                // Remove any remaining non-ASCII characters
                .replace(Regex("[^\\x00-\\x7F]"), "_")
                // Replace other problematic characters
                .replace(Regex("[<>:\"/\\\\|?*]"), "_")
                // Remove control characters
                .replace(Regex("[\\x00-\\x1F\\x7F]"), "")
                // Replace multiple underscores with single
                .replace(Regex("_+"), "_")
                // Remove leading/trailing underscores and dots
                .trim('_', '.')
            
            // 2. Ensure filename is not empty and not too long
            if (sanitized.isBlank()) {
                sanitized = "media_file"
            }
            
            // 3. Limit length to avoid filesystem issues
            if (sanitized.length > 100) {
                val extension = if (sanitized.contains('.')) {
                    "." + sanitized.substringAfterLast('.')
                } else ""
                val nameWithoutExt = sanitized.substringBeforeLast('.')
                sanitized = nameWithoutExt.take(100 - extension.length) + extension
            }
            
            // 4. Add timestamp to make unique if still problematic
            if (sanitized.length < 3) {
                sanitized = "media_${System.currentTimeMillis()}"
            }
            
            logger.debug { "Filename sanitized: '$originalName' -> '$sanitized'" }
            return sanitized
            
        } catch (e: Exception) {
            logger.warn(e) { "Error sanitizing filename '$originalName', using fallback" }
            return "media_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}"
        }
    }
    
    init {
        // Ensure temp directory exists
        File(TMP_DIR).mkdirs()
    }
    
    /**
     * Download all media attachments from a TDLib message
     * Returns list of successfully downloaded attachments
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
                
                is TdApi.MessageAudio -> {
                    logger.debug { "Processing audio message" }
                    downloadAudio(content, client, content.caption?.text)?.let { 
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
    
    private suspend fun downloadPhoto(
        content: TdApi.MessagePhoto, 
        client: Client,
        caption: String?
    ): MediaAttachment? {
        // Get the largest photo size that's under our limit
        val photoSize = content.photo.sizes
            .filter { it.photo.size <= MAX_FILE_SIZE }
            .maxByOrNull { it.width * it.height }
        
        if (photoSize == null) {
            logger.debug { "No suitable photo size found (all exceed ${MAX_FILE_SIZE / 1024 / 1024}MB)" }
            return null
        }
        
        val file = photoSize.photo
        val localPath = downloadFile(client, file.id, "photo_${UUID.randomUUID()}.jpg")
        
        return if (localPath != null) {
            MediaAttachment(
                type = MediaType.PHOTO,
                filePath = localPath,
                originalFileName = "photo.jpg",
                fileSize = file.size.toLong(),
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
            logger.debug { "Video too large: ${video.video.size / 1024 / 1024}MB (limit: ${MAX_FILE_SIZE / 1024 / 1024}MB)" }
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
            logger.debug { "Document too large: ${document.document.size / 1024 / 1024}MB (limit: ${MAX_FILE_SIZE / 1024 / 1024}MB)" }
            return null
        }
        
        // FIXED: Better filename handling for documents
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
        
        // FIXED: Better filename handling for audio files
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
    
    private suspend fun downloadVoiceNote(
        content: TdApi.MessageVoiceNote, 
        client: Client,
        caption: String?
    ): MediaAttachment? {
        val voice = content.voiceNote
        
        if (voice.voice.size > MAX_FILE_SIZE) {
            logger.debug { "Voice note too large: ${voice.voice.size / 1024 / 1024}MB (limit: ${MAX_FILE_SIZE / 1024 / 1024}MB)" }
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
            logger.debug { "Animation too large: ${animation.animation.size / 1024 / 1024}MB (limit: ${MAX_FILE_SIZE / 1024 / 1024}MB)" }
            return null
        }
        
        // FIXED: Better filename handling for animations
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
     * Download a file from TDLib with robust error handling
     * FIXED: Better handling of TDLib download issues where files don't exist
     * FIXED: Sanitize filenames to avoid Unicode/special character issues
     * Returns local file path on success, null on failure
     */
    private suspend fun downloadFile(
        client: Client, 
        fileId: Int, 
        fileName: String
    ): String? = withContext(Dispatchers.IO) {
        
        try {
            // FIXED: Sanitize filename to avoid Unicode/space issues
            val sanitizedFileName = sanitizeFilename(fileName)
            val localPath = File(TMP_DIR, sanitizedFileName).absolutePath
            val deferred = CompletableDeferred<String?>()
            
            logger.debug { "Starting download: fileId=$fileId, original='$fileName', sanitized='$sanitizedFileName'" }
            
            // Start the download
            client.send(TdApi.DownloadFile(fileId, 1, 0, 0, false)) { result ->
                when (result) {
                    is TdApi.File -> {
                        if (result.local.isDownloadingCompleted) {
                            // File already downloaded or download completed immediately
                            handleCompletedDownload(result, localPath, deferred)
                        } else {
                            // Download started, will be handled by polling
                            logger.debug { "Download started for fileId=$fileId, will poll for completion" }
                        }
                    }
                    is TdApi.Error -> {
                        logger.warn { "Download failed for fileId=$fileId: ${result.message}" }
                        deferred.complete(null)
                    }
                }
            }
            
            // Start polling for download completion
            val pollJob = launch {
                var pollCount = 0
                
                while (isActive && !deferred.isCompleted && pollCount < MAX_POLL_ATTEMPTS) {
                    delay(DOWNLOAD_POLL_INTERVAL_MS)
                    pollCount++
                    
                    // Check file status
                    client.send(TdApi.GetFile(fileId)) { fileResult ->
                        when (fileResult) {
                            is TdApi.File -> {
                                if (fileResult.local.isDownloadingCompleted) {
                                    handleCompletedDownload(fileResult, localPath, deferred)
                                } else {
                                    logger.debug { "Poll $pollCount: Download still in progress for fileId=$fileId" }
                                }
                            }
                            is TdApi.Error -> {
                                logger.warn { "Error getting file status during polling: ${fileResult.message}" }
                                deferred.complete(null)
                            }
                        }
                    }
                }
                
                // If we've exhausted our poll attempts, fail the download
                if (pollCount >= MAX_POLL_ATTEMPTS && !deferred.isCompleted) {
                    logger.warn { "Download timeout for fileId=$fileId after $pollCount polls" }
                    deferred.complete(null)
                }
            }
            
            // Wait for download completion or timeout
            try {
                withTimeout(DOWNLOAD_TIMEOUT_MS + 1000) { // Extra 1 second for cleanup
                    deferred.await()
                }
            } catch (e: TimeoutCancellationException) {
                logger.warn { "Overall download timeout for fileId=$fileId" }
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
     * Handle completed download with robust error checking
     * FIXED: Check if source file actually exists before copying
     * FIXED: Handle Unicode/encoding issues with TDLib source paths
     */
    private fun handleCompletedDownload(
        file: TdApi.File,
        targetPath: String,
        deferred: CompletableDeferred<String?>
    ) {
        try {
            val sourcePath = file.local.path
            
            if (sourcePath.isNullOrBlank()) {
                logger.warn { "Download completed but source path is empty" }
                deferred.complete(null)
                return
            }
            
            logger.debug { "Attempting to copy from TDLib path: '$sourcePath' to '$targetPath'" }
            
            val sourceFile = File(sourcePath)
            
            if (!sourceFile.exists()) {
                logger.warn { "Download completed but source file doesn't exist: $sourcePath" }
                
                // ADDITIONAL: Try to find the file with different encoding or similar name
                val parentDir = sourceFile.parentFile
                if (parentDir?.exists() == true) {
                    logger.debug { "Searching for alternative files in: ${parentDir.absolutePath}" }
                    
                    val alternativeFile = findAlternativeFile(parentDir, sourceFile.name)
                    if (alternativeFile != null) {
                        logger.info { "Found alternative file: ${alternativeFile.absolutePath}" }
                        return handleFileFound(alternativeFile, targetPath, deferred)
                    }
                }
                
                deferred.complete(null)
                return
            }
            
            handleFileFound(sourceFile, targetPath, deferred)
            
        } catch (e: Exception) {
            logger.error(e) { "Failed to handle completed download" }
            deferred.complete(null)
        }
    }
    
    /**
     * Try to find a file with similar name when exact filename doesn't work
     * This helps with Unicode/encoding issues
     */
    private fun findAlternativeFile(parentDir: File, originalName: String): File? {
        try {
            val files = parentDir.listFiles() ?: return null
            
            // First, try to find files with similar size and timestamp (recently modified)
            val recentFiles = files.filter { 
                it.isFile && 
                it.lastModified() > System.currentTimeMillis() - 60000 && // Modified in last minute
                it.length() > 0
            }.sortedByDescending { it.lastModified() }
            
            if (recentFiles.isNotEmpty()) {
                logger.debug { "Found ${recentFiles.size} recent files, using most recent: ${recentFiles.first().name}" }
                return recentFiles.first()
            }
            
            // Second, try exact name matching (case-insensitive)
            val exactMatch = files.find { 
                it.isFile && 
                it.name.equals(originalName, ignoreCase = true)
            }
            if (exactMatch != null) {
                logger.debug { "Found exact case-insensitive match: ${exactMatch.name}" }
                return exactMatch
            }
            
            // Third, try partial name matching
            val baseName = originalName.substringBeforeLast('.')
            val extension = if (originalName.contains('.')) originalName.substringAfterLast('.') else ""
            
            val partialMatch = files.find { file ->
                file.isFile && 
                (file.name.contains(baseName, ignoreCase = true) || 
                 (extension.isNotEmpty() && file.name.endsWith(".$extension", ignoreCase = true)))
            }
            
            if (partialMatch != null) {
                logger.debug { "Found partial match: ${partialMatch.name}" }
                return partialMatch
            }
            
            logger.debug { "No alternative file found among ${files.size} files" }
            return null
            
        } catch (e: Exception) {
            logger.warn(e) { "Error searching for alternative file" }
            return null
        }
    }
    
    /**
     * Handle copying a found file to the target location
     */
    private fun handleFileFound(
        sourceFile: File,
        targetPath: String,
        deferred: CompletableDeferred<String?>
    ) {
        try {
            if (!sourceFile.canRead()) {
                logger.warn { "Source file is not readable: ${sourceFile.absolutePath}" }
                deferred.complete(null)
                return
            }
            
            if (sourceFile.length() == 0L) {
                logger.warn { "Source file is empty: ${sourceFile.absolutePath}" }
                deferred.complete(null)
                return
            }
            
            // Try to copy the file
            val targetFile = File(targetPath)
            sourceFile.copyTo(targetFile, overwrite = true)
            
            // Verify the copy was successful
            if (!targetFile.exists() || targetFile.length() != sourceFile.length()) {
                logger.warn { "File copy verification failed: target=${targetFile.exists()}, sizes: ${sourceFile.length()} -> ${targetFile.length()}" }
                deferred.complete(null)
                return
            }
            
            logger.debug { "File downloaded and copied successfully: $targetPath (${targetFile.length()} bytes)" }
            deferred.complete(targetPath)
            
        } catch (e: Exception) {
            logger.error(e) { "Failed to copy file: ${sourceFile.absolutePath} -> $targetPath" }
            deferred.complete(null)
        }
    }
    
    /**
     * Clean up downloaded media files
     */
    fun cleanupMediaFiles(attachments: List<MediaAttachment>) {
        attachments.forEach { attachment ->
            try {
                val file = File(attachment.filePath)
                if (file.exists() && file.delete()) {
                    logger.debug { "Cleaned up media file: ${attachment.filePath}" }
                } else {
                    logger.debug { "Media file not found or couldn't delete: ${attachment.filePath}" }
                }
            } catch (e: Exception) {
                logger.warn(e) { "Error cleaning up media file: ${attachment.filePath}" }
            }
        }
    }
    
    /**
     * Clean up old temp files (older than 1 hour)
     */
    fun cleanupOldTempFiles() {
        try {
            val tempDir = File(TMP_DIR)
            if (!tempDir.exists()) return
            
            val oneHourAgo = System.currentTimeMillis() - (60 * 60 * 1000)
            var cleanedCount = 0
            
            tempDir.listFiles()?.forEach { file ->
                if (file.lastModified() < oneHourAgo) {
                    if (file.delete()) {
                        cleanedCount++
                    }
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