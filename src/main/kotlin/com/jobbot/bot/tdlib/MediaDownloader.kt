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
 * UPDATED: Now downloads video thumbnails for proper previews
 */
class MediaDownloader {
    private val logger = getLogger("MediaDownloader")
    
    companion object {
        private const val MAX_FILE_SIZE = 50 * 1024 * 1024L // 50MB limit
        private const val DOWNLOAD_TIMEOUT_MS = 45000L // 45 seconds per file
        private const val DOWNLOAD_POLL_INTERVAL_MS = 500L // Check every 500ms
        private const val MAX_POLL_ATTEMPTS = 90 // 45 seconds / 500ms
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
        
        // Simple and correct logic for display filename
        val originalFileName = when {
            // If we have both artist and title, show as music (no extension) - matches Telegram
            !audio.title.isNullOrBlank() && !audio.performer.isNullOrBlank() -> 
                "${audio.performer} - ${audio.title}"
            
            // If we have just title, show it (no extension)
            !audio.title.isNullOrBlank() -> 
                audio.title
            
            // If we have just performer, show it (no extension)  
            !audio.performer.isNullOrBlank() -> 
                audio.performer
            
            // Otherwise, use original filename exactly as-is (no modifications)
            !audio.fileName.isNullOrBlank() -> 
                audio.fileName
            
            // Ultimate fallback (should rarely happen)
            else -> "audio"
        }
        
        val filePath = downloadFile(client, audio.audio.id)
        
        logger.debug { "Audio metadata - fileName: '${audio.fileName}', title: '${audio.title}', performer: '${audio.performer}'" }
        logger.debug { "Using filename: '$originalFileName'" }
        
        return if (filePath != null) {
            MediaAttachment(
                type = MediaType.AUDIO,
                filePath = filePath,
                originalFileName = originalFileName,
                fileSize = audio.audio.size.toLong(),
                mimeType = audio.mimeType,
                caption = caption,
                duration = audio.duration,
                // Pass the original metadata from TDLib
                performer = audio.performer?.takeIf { it.isNotBlank() },  // Only if not empty
                title = audio.title?.takeIf { it.isNotBlank() }           // Only if not empty
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
        
        val filePath = downloadFile(client, photoSize.photo.id)
        
        return if (filePath != null) {
            // Use the actual filename from filesystem
            val actualFilename = File(filePath).name
            
            MediaAttachment(
                type = MediaType.PHOTO,
                filePath = filePath,
                originalFileName = actualFilename, // e.g., "5447242563103878913_121.jpg"
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
        
        val filePath = downloadFile(client, video.video.id)
        
        return if (filePath != null) {
            // Use TDLib filename if available, otherwise filesystem name
            val originalFileName = when {
                !video.fileName.isNullOrBlank() && !video.fileName.startsWith("tmp") -> 
                    video.fileName // Use TDLib filename if meaningful
                else -> 
                    File(filePath).name // Use actual filesystem name (e.g., "5447242562647651938.mp4")
            }
            
            // NEW: Download thumbnail if available
            val thumbnailPath = video.thumbnail?.let { thumbnail ->
                try {
                    logger.debug { "Downloading video thumbnail: fileId=${thumbnail.file.id}, format=${thumbnail.format.javaClass.simpleName}" }
                    downloadFile(client, thumbnail.file.id)
                } catch (e: Exception) {
                    logger.debug(e) { "Failed to download video thumbnail" }
                    null
                }
            }
            
            logger.debug { "Video metadata - fileName: '${video.fileName}'" }
            logger.debug { "Using filename: '$originalFileName'" }
            if (thumbnailPath != null) {
                logger.debug { "Downloaded thumbnail: $thumbnailPath" }
            } else {
                logger.debug { "No thumbnail available for video" }
            }
            
            MediaAttachment(
                type = MediaType.VIDEO,
                filePath = filePath,
                originalFileName = originalFileName,
                fileSize = video.video.size.toLong(),
                mimeType = video.mimeType,
                caption = caption,
                width = video.width,
                height = video.height,
                duration = video.duration,
                thumbnailPath = thumbnailPath // NEW: Include thumbnail path
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
        
        val filePath = downloadFile(client, document.document.id)
        
        return if (filePath != null) {
            // Use TDLib filename if available, otherwise filesystem name
            val originalFileName = when {
                !document.fileName.isNullOrBlank() && !document.fileName.startsWith("tmp") -> 
                    document.fileName // Use TDLib filename if meaningful
                else -> 
                    File(filePath).name // Use actual filesystem name
            }
            
            logger.debug { "Document metadata - fileName: '${document.fileName}'" }
            logger.debug { "Using filename: '$originalFileName'" }
            
            MediaAttachment(
                type = MediaType.DOCUMENT,
                filePath = filePath,
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
        
        val filePath = downloadFile(client, voice.voice.id)
        
        return if (filePath != null) {
            MediaAttachment(
                type = MediaType.VOICE,
                filePath = filePath,
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
        
        val filePath = downloadFile(client, animation.animation.id)
        
        return if (filePath != null) {
            // Use TDLib filename if available, otherwise filesystem name  
            val originalFileName = when {
                !animation.fileName.isNullOrBlank() && !animation.fileName.startsWith("tmp") -> 
                    animation.fileName // Use TDLib filename if meaningful
                else -> 
                    File(filePath).name // Use actual filesystem name
            }
            
            // NEW: Download thumbnail if available (animations can have thumbnails too)
            val thumbnailPath = animation.thumbnail?.let { thumbnail ->
                try {
                    logger.debug { "Downloading animation thumbnail: fileId=${thumbnail.file.id}" }
                    downloadFile(client, thumbnail.file.id)
                } catch (e: Exception) {
                    logger.debug(e) { "Failed to download animation thumbnail" }
                    null
                }
            }
            
            logger.debug { "Animation metadata - fileName: '${animation.fileName}'" }
            logger.debug { "Using filename: '$originalFileName'" }
            if (thumbnailPath != null) {
                logger.debug { "Downloaded animation thumbnail: $thumbnailPath" }
            }
            
            MediaAttachment(
                type = MediaType.ANIMATION,
                filePath = filePath,
                originalFileName = originalFileName,
                fileSize = animation.animation.size.toLong(),
                mimeType = animation.mimeType,
                caption = caption,
                width = animation.width,
                height = animation.height,
                duration = animation.duration,
                thumbnailPath = thumbnailPath // NEW: Include thumbnail path for animations too
            )
        } else null
    }
    
    /**
     * Download file and return original TDLib path (no copying)
     */
    private suspend fun downloadFile(
        client: Client, 
        fileId: Int
    ): String? = withContext(Dispatchers.IO) {
        
        try {
            val deferred = CompletableDeferred<String?>()
            
            logger.debug { "Starting download: fileId=$fileId" }
            
            // Force download with higher priority
            client.send(TdApi.DownloadFile(fileId, 32, 0, 0, true)) { result ->
                when (result) {
                    is TdApi.File -> {
                        logger.debug { "Download response: completed=${result.local.isDownloadingCompleted}, size=${result.size}, local_size=${result.local.downloadedSize}" }
                        logger.debug { "Download paths: remote=${result.remote.id}, local=${result.local.path}" }
                        
                        if (result.local.isDownloadingCompleted && result.local.downloadedSize == result.size) {
                            handleCompletedDownload(result, deferred)
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
            
            // Polling for download completion
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
                                
                                logger.debug { "Poll $pollCount: Progress $progress/$total bytes (${if (total > 0) progress * 100 / total else 0}%)" }
                                
                                if (fileResult.local.isDownloadingCompleted && progress == total && total > 0) {
                                    logger.debug { "Download completed successfully: $progress bytes" }
                                    handleCompletedDownload(fileResult, deferred)
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
     * Verify original file exists and return its path
     */
    private fun handleCompletedDownload(
        file: TdApi.File,
        deferred: CompletableDeferred<String?>
    ) {
        try {
            val originalPath = file.local.path
            val expectedSize = file.size.toLong()
            val downloadedSize = file.local.downloadedSize.toLong()
            
            if (originalPath.isNullOrBlank()) {
                logger.warn { "TDLib file path is empty" }
                deferred.complete(null)
                return
            }
            
            // Verify download completed fully
            if (downloadedSize != expectedSize || expectedSize == 0L) {
                logger.warn { "Download incomplete: downloaded $downloadedSize/$expectedSize bytes" }
                deferred.complete(null)
                return
            }
            
            // Verify file exists and is readable
            val originalFile = File(originalPath)
            if (!originalFile.exists() || !originalFile.isFile() || !originalFile.canRead()) {
                logger.warn { "Original file not accessible: $originalPath" }
                deferred.complete(null)
                return
            }
            
            // Verify file size matches
            val actualSize = originalFile.length()
            if (actualSize != expectedSize) {
                logger.warn { "File size mismatch: expected $expectedSize, actual $actualSize" }
                deferred.complete(null)
                return
            }
            
            logger.debug { "Using original TDLib file: $originalPath ($actualSize bytes)" }
            deferred.complete(originalPath)
            
        } catch (e: Exception) {
            logger.error(e) { "Error handling completed download" }
            deferred.complete(null)
        }
    }
    
    /**
     * No cleanup needed since we use original files
     */
    fun cleanupMediaFiles(attachments: List<MediaAttachment>) {
        // No cleanup needed - TDLib manages its own cache
        logger.debug { "Cleanup skipped - using original TDLib files (${attachments.size} files)" }
    }
    
    /**
     * No temp files created anymore
     */
    fun cleanupOldTempFiles() {
        // No temp files created anymore
        logger.debug { "Temp file cleanup skipped - no temp files created" }
    }
}