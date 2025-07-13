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
 * FIXED: Improved timeout handling and added polling mechanism
 */
class MediaDownloader {
    private val logger = getLogger("MediaDownloader")
    
    companion object {
        private const val MAX_FILE_SIZE = 50 * 1024 * 1024L // 50MB limit
        private const val DOWNLOAD_TIMEOUT_MS = 45000L // 45 seconds per file (increased)
        private const val DOWNLOAD_POLL_INTERVAL_MS = 500L // Check every 500ms
        private const val TMP_DIR = "/tmp/jobbot_media"
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
        
        val fileName = document.fileName.ifBlank { "document_${UUID.randomUUID()}" }
        val localPath = downloadFile(client, document.document.id, fileName)
        
        return if (localPath != null) {
            MediaAttachment(
                type = MediaType.DOCUMENT,
                filePath = localPath,
                originalFileName = fileName,
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
        
        val fileName = audio.fileName.ifBlank { "audio_${UUID.randomUUID()}.mp3" }
        val localPath = downloadFile(client, audio.audio.id, fileName)
        
        return if (localPath != null) {
            MediaAttachment(
                type = MediaType.AUDIO,
                filePath = localPath,
                originalFileName = fileName,
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
        
        val fileName = animation.fileName.ifBlank { "animation_${UUID.randomUUID()}.gif" }
        val localPath = downloadFile(client, animation.animation.id, fileName)
        
        return if (localPath != null) {
            MediaAttachment(
                type = MediaType.ANIMATION,
                filePath = localPath,
                originalFileName = fileName,
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
     * Download a file from TDLib
     * FIXED: Improved polling mechanism with proper timeout handling
     * Returns local file path on success, null on failure
     */
    private suspend fun downloadFile(
        client: Client, 
        fileId: Int, 
        fileName: String
    ): String? = withContext(Dispatchers.IO) {
        
        try {
            val localPath = File(TMP_DIR, fileName).absolutePath
            val deferred = CompletableDeferred<String?>()
            
            logger.debug { "Starting download: fileId=$fileId to $localPath" }
            
            // Start the download
            client.send(TdApi.DownloadFile(fileId, 1, 0, 0, false)) { result ->
                when (result) {
                    is TdApi.File -> {
                        if (result.local.isDownloadingCompleted) {
                            // File already downloaded or download completed immediately
                            val sourcePath = result.local.path
                            
                            try {
                                // Copy to our temp directory with desired name
                                File(sourcePath).copyTo(File(localPath), overwrite = true)
                                logger.debug { "File downloaded immediately: $localPath" }
                                deferred.complete(localPath)
                            } catch (e: Exception) {
                                logger.error(e) { "Failed to copy immediately downloaded file" }
                                deferred.complete(null)
                            }
                        } else {
                            // Download started, need to poll for completion
                            logger.debug { "Download started for fileId=$fileId, will poll for completion" }
                            // Don't complete the deferred here, let the polling handle it
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
                val pollStartTime = System.currentTimeMillis()
                var pollCount = 0
                
                while (isActive && !deferred.isCompleted) {
                    delay(DOWNLOAD_POLL_INTERVAL_MS)
                    pollCount++
                    
                    if (System.currentTimeMillis() - pollStartTime > DOWNLOAD_TIMEOUT_MS) {
                        logger.warn { "Download timeout for fileId=$fileId after ${pollCount} polls" }
                        deferred.complete(null)
                        break
                    }
                    
                    // Check file status
                    client.send(TdApi.GetFile(fileId)) { fileResult ->
                        when (fileResult) {
                            is TdApi.File -> {
                                if (fileResult.local.isDownloadingCompleted) {
                                    try {
                                        File(fileResult.local.path).copyTo(File(localPath), overwrite = true)
                                        logger.debug { "File downloaded after polling (${pollCount} polls): $localPath" }
                                        deferred.complete(localPath)
                                    } catch (e: Exception) {
                                        logger.error(e) { "Failed to copy file after polling" }
                                        deferred.complete(null)
                                    }
                                } else {
                                    // Still downloading, continue polling
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