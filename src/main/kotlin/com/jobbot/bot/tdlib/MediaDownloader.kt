package com.jobbot.bot.tdlib

import com.jobbot.data.models.MediaAttachment
import com.jobbot.data.models.MediaType
import com.jobbot.infrastructure.monitoring.ErrorTracker
import com.jobbot.shared.getLogger
import kotlinx.coroutines.*
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.io.File
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.util.*

/**
 * Downloads media attachments from TDLib messages
 * ENHANCED: Proper UTF-8 filename decoding to handle Unicode filename encoding mismatch
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
     * ENHANCED: Handle completed download with proper UTF-8 filename matching
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
            
            // ENHANCED: Try to access the TDLib file directly with UTF-8 filename handling
            val tdlibFile = File(tdlibSourcePath)
            
            // Strategy 1: Direct file access (works if path is correct)
            if (tdlibFile.exists() && tdlibFile.length() == expectedSize && tdlibFile.length() > 0) {
                logger.info { "✅ TDLib file accessible directly: ${getSafeFilename(tdlibFile)} (${tdlibFile.length()} bytes)" }
                copyFileToTarget(tdlibFile, targetPath, deferred)
                return
            }
            
            // Strategy 2: Try UTF-8 filename variations in the same directory
            val parentDir = File(tdlibSourcePath).parentFile
            val expectedFilename = File(tdlibSourcePath).name
            
            if (parentDir?.exists() == true) {
                logger.debug { "Direct access failed, searching directory for UTF-8 filename variations" }
                
                // Look for files with the exact size (the cached file should be there)
                val sizeMatches = parentDir.listFiles()?.filter { file ->
                    file.isFile && 
                    file.length() == expectedSize &&
                    file.length() > 0 &&
                    file.canRead()
                }
                
                if (sizeMatches?.isNotEmpty() == true) {
                    logger.debug { "Found ${sizeMatches.size} files with correct size" }
                    
                    // Try UTF-8 filename matching strategies
                    val matchedFile = tryUtf8FilenameMatching(sizeMatches, expectedFilename)
                    
                    if (matchedFile != null) {
                        logger.info { "✅ Found cached file via UTF-8 matching: ${getSafeFilename(matchedFile)} (${matchedFile.length()} bytes)" }
                        copyFileToTarget(matchedFile, targetPath, deferred)
                        return
                    } else {
                        // Use the first size match as fallback (it's very likely the right file)
                        val fallbackFile = sizeMatches.first()
                        logger.warn { "⚠️ Using size-match fallback: ${getSafeFilename(fallbackFile)} (${fallbackFile.length()} bytes)" }
                        copyFileToTarget(fallbackFile, targetPath, deferred)
                        return
                    }
                }
            }
            
            logger.warn { "TDLib file not accessible and no size matches found - this should not happen" }
            deferred.complete(null)
            
            // Extract expected filename from TDLib path
            val expectedFilename = File(tdlibSourcePath).name
            logger.debug { "Expected filename from TDLib: '$expectedFilename'" }
            
            val parentDir = File(tdlibSourcePath).parentFile
            if (parentDir?.exists() != true) {
                logger.warn { "Parent directory doesn't exist: ${parentDir?.absolutePath}" }
                deferred.complete(null)
                return
            }
            
            logger.debug { "Searching in directory: ${parentDir.absolutePath}" }
            
            // SIMPLIFIED: Just find files with the correct size and try UTF-8 matching
            val sizeMatches = parentDir.listFiles()?.filter { file ->
                file.isFile && 
                file.length() == expectedSize &&
                file.length() > 0 &&
                file.canRead()
            }
            
            if (sizeMatches?.isNotEmpty() == true) {
                logger.debug { "Found ${sizeMatches.size} files with correct size ${expectedSize}" }
                
                val matchedFile = tryUtf8FilenameMatching(sizeMatches, expectedFilename)
                
                if (matchedFile != null) {
                    logger.info { "Found matching file: ${getSafeFilename(matchedFile)} (${matchedFile.length()} bytes)" }
                    copyFileToTarget(matchedFile, targetPath, deferred)
                } else {
                    // Use first size match as fallback
                    val fallbackFile = sizeMatches.first()
                    logger.warn { "Using size-match fallback: ${getSafeFilename(fallbackFile)} (${fallbackFile.length()} bytes)" }
                    copyFileToTarget(fallbackFile, targetPath, deferred)
                }
            } else {
                logger.warn { "No files found with correct size $expectedSize" }
                logDirectoryContents(parentDir, expectedSize)
                deferred.complete(null)
            }
            
        } catch (e: Exception) {
            logger.error(e) { "Error handling completed download" }
            deferred.complete(null)
        }
    }
    
    /**
     * Try UTF-8 filename matching strategies for cached files
     */
    private fun tryUtf8FilenameMatching(candidates: List<File>, expectedFilename: String): File? {
        logger.debug { "Trying UTF-8 filename matching for: '$expectedFilename'" }
        
        // Strategy 1: Direct filename match
        candidates.forEach { file ->
            if (file.name == expectedFilename) {
                logger.debug { "✅ Direct filename match: ${getSafeFilename(file)}" }
                return file
            }
        }
        
        // Strategy 2: UTF-8 reinterpretation (most likely to work for Cyrillic)
        candidates.forEach { file ->
            try {
                val reinterpreted = String(file.name.toByteArray(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8)
                if (reinterpreted == expectedFilename) {
                    logger.debug { "✅ UTF-8 reinterpretation match: ${getSafeFilename(file)}" }
                    return file
                }
            } catch (e: Exception) {
                logger.debug { "Error in UTF-8 reinterpretation for: ${getSafeFilename(file)}" }
            }
        }
        
        // Strategy 3: Reverse reinterpretation
        candidates.forEach { file ->
            try {
                val reverseReinterpreted = String(expectedFilename.toByteArray(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1)
                if (file.name == reverseReinterpreted) {
                    logger.debug { "✅ Reverse UTF-8 reinterpretation match: ${getSafeFilename(file)}" }
                    return file
                }
            } catch (e: Exception) {
                logger.debug { "Error in reverse UTF-8 reinterpretation" }
            }
        }
        
        // Strategy 4: Normalized comparison
        val normalizedExpected = normalizeFilename(expectedFilename)
        candidates.forEach { file ->
            val normalizedActual = normalizeFilename(file.name)
            if (normalizedActual == normalizedExpected) {
                logger.debug { "✅ Normalized filename match: ${getSafeFilename(file)}" }
                return file
            }
        }
        
        // Strategy 5: Extension matching (last resort)
        val expectedExtension = getFileExtension(expectedFilename)
        if (expectedExtension != null) {
            val extensionMatches = candidates.filter { file ->
                getFileExtension(file.name) == expectedExtension
            }
            
            if (extensionMatches.size == 1) {
                logger.debug { "✅ Single extension match: ${getSafeFilename(extensionMatches.first())}" }
                return extensionMatches.first()
            }
        }
        
        logger.debug { "❌ No UTF-8 filename matches found" }
        return null
    }
    private fun findFileWithProperUtf8Matching(
        directory: File,
        expectedFilename: String,
        expectedSize: Long
    ): File? {
        val allFiles = directory.listFiles() ?: return null
        val now = System.currentTimeMillis()
        
        // Filter to recent files with correct size first (basic validation)
        val candidates = allFiles.filter { file ->
            file.isFile && 
            file.length() == expectedSize &&
            file.length() > 0 &&
            file.canRead() &&
            (now - file.lastModified()) < 300000  // Within last 5 minutes
        }
        
        // ENHANCED: Also try to find the file regardless of timestamp if no recent candidates
        val allSizeMatches = if (candidates.isEmpty()) {
            logger.debug { "No recent candidates, expanding search to all files with correct size" }
            allFiles.filter { file ->
                file.isFile && 
                file.length() > 0 &&  // Must have content
                file.canRead()
            }.also { allCandidates ->
                // Check each file individually for size (filesystem sync issue)
                allCandidates.forEach { file ->
                    val fileSize = file.length()
                    logger.debug { "Checking file: ${getSafeFilename(file)}, reported size: $fileSize, expected: $expectedSize" }
                    
                    // Try to refresh file size multiple times
                    if (fileSize != expectedSize) {
                        Thread.sleep(100)
                        val refreshedSize = file.length()
                        logger.debug { "Refreshed size for ${getSafeFilename(file)}: $refreshedSize" }
                    }
                }
            }.filter { file ->
                file.length() == expectedSize
            }
        } else {
            candidates
        }
        
        if (allSizeMatches.isEmpty()) {
            logger.debug { "No candidates found with size $expectedSize" }
            
            // ENHANCED: Try to find the actual TDLib file directly
            val tdlibFile = File(directory, expectedFilename)
            if (tdlibFile.exists() && tdlibFile.length() == expectedSize && tdlibFile.length() > 0) {
                logger.info { "Found TDLib file directly: ${getSafeFilename(tdlibFile)} (${tdlibFile.length()} bytes)" }
                return tdlibFile
            }
            
            return null
        }
        
        logger.debug { "Found ${allSizeMatches.size} size-matching files, trying filename matching..." }
        
        // Strategy 1: Direct filename match (handles ASCII and properly encoded UTF-8)
        allSizeMatches.forEach { file ->
            if (file.name == expectedFilename) {
                logger.debug { "✅ Direct filename match: ${getSafeFilename(file)}" }
                return file
            }
        }
        
        // Strategy 2: Normalized filename comparison (handles different Unicode normalizations)
        val normalizedExpected = normalizeFilename(expectedFilename)
        allSizeMatches.forEach { file ->
            val normalizedActual = normalizeFilename(file.name)
            if (normalizedActual == normalizedExpected) {
                logger.debug { "✅ Normalized filename match: ${getSafeFilename(file)}" }
                return file
            }
        }
        
        // Strategy 3: UTF-8 byte-level comparison
        val expectedBytes = expectedFilename.toByteArray(StandardCharsets.UTF_8)
        allSizeMatches.forEach { file ->
            try {
                val actualBytes = file.name.toByteArray(StandardCharsets.UTF_8)
                if (actualBytes.contentEquals(expectedBytes)) {
                    logger.debug { "✅ UTF-8 byte-level match: ${getSafeFilename(file)}" }
                    return file
                }
            } catch (e: Exception) {
                logger.debug { "Error in UTF-8 comparison for file: ${getSafeFilename(file)}" }
            }
        }
        
        // Strategy 4: Try different charset interpretations
        allSizeMatches.forEach { file ->
            if (compareWithCharsetVariants(file.name, expectedFilename)) {
                logger.debug { "✅ Charset variant match: ${getSafeFilename(file)}" }
                return file
            }
        }
        
        // Strategy 5: Partial filename matching (for cases where encoding really mangles the name)
        // Extract file extension and try to match by size + extension + recency
        val expectedExtension = getFileExtension(expectedFilename)
        if (expectedExtension != null) {
            val extensionMatches = allSizeMatches.filter { file ->
                getFileExtension(file.name) == expectedExtension
            }.sortedByDescending { it.lastModified() }
            
            if (extensionMatches.size == 1) {
                logger.debug { "✅ Single extension + size match: ${getSafeFilename(extensionMatches.first())}" }
                return extensionMatches.first()
            }
        }
        
        // Strategy 6: Last resort - most recent file with correct size
        val mostRecent = allSizeMatches.maxByOrNull { it.lastModified() }
        if (mostRecent != null) {
            logger.warn { "⚠️ Using most recent size match as fallback: ${getSafeFilename(mostRecent)}" }
            return mostRecent
        }
        
        return null
    }
    
    /**
     * Normalize filename for comparison (handles Unicode normalization forms)
     */
    private fun normalizeFilename(filename: String): String {
        return try {
            Normalizer.normalize(filename, Normalizer.Form.NFC)
                .lowercase()
                .trim()
        } catch (e: Exception) {
            logger.debug { "Error normalizing filename: $filename" }
            filename.lowercase().trim()
        }
    }
    
    /**
     * Compare filenames using different charset interpretations
     */
    private fun compareWithCharsetVariants(actualFilename: String, expectedFilename: String): Boolean {
        val charsets = listOf(
            StandardCharsets.UTF_8,
            StandardCharsets.ISO_8859_1,
            Charset.forName("Windows-1251"), // Common for Cyrillic
            Charset.forName("UTF-16"),
            Charset.forName("UTF-16LE"),
            Charset.forName("UTF-16BE")
        )
        
        return charsets.any { charset ->
            try {
                // Try interpreting actual filename as bytes in this charset, then decode as UTF-8
                val reinterpreted = String(actualFilename.toByteArray(StandardCharsets.ISO_8859_1), charset)
                reinterpreted == expectedFilename
            } catch (e: Exception) {
                false
            }
        }
    }
    
    /**
     * Get file extension safely
     */
    private fun getFileExtension(filename: String): String? {
        return try {
            val lastDot = filename.lastIndexOf('.')
            if (lastDot > 0 && lastDot < filename.length - 1) {
                filename.substring(lastDot + 1).lowercase()
            } else null
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Get filename safely for logging (handles encoding errors)
     */
    private fun getSafeFilename(file: File): String {
        return try {
            file.name
        } catch (e: Exception) {
            try {
                // Try to get a safe representation
                val bytes = file.name.toByteArray(StandardCharsets.ISO_8859_1)
                String(bytes, StandardCharsets.UTF_8)
            } catch (e2: Exception) {
                "<encoding-error-${file.length()}bytes>"
            }
        }
    }
    
    /**
     * Log directory contents with proper UTF-8 handling
     */
    private fun logDirectoryContents(directory: File, expectedSize: Long) {
        try {
            val allFiles = directory.listFiles()
            logger.debug { "Directory contains ${allFiles?.size ?: 0} files:" }
            
            allFiles?.forEachIndexed { index, file ->
                val age = (System.currentTimeMillis() - file.lastModified()) / 1000
                val sizeMatch = if (file.length() == expectedSize) "✅" else "❌"
                val safeFileName = getSafeFilename(file)
                
                // Also show raw bytes for debugging
                val rawBytes = try {
                    file.name.toByteArray(StandardCharsets.ISO_8859_1)
                        .joinToString("") { "%02X".format(it) }
                        .take(40) // Limit length
                } catch (e: Exception) {
                    "encoding-error"
                }
                
                logger.debug { 
                    "  [$index] $safeFileName: ${file.length()} bytes (age: ${age}s) $sizeMatch" +
                    if (logger.isDebugEnabled) " [bytes: $rawBytes...]" else ""
                }
            }
            
        } catch (e: Exception) {
            logger.error(e) { "Error logging directory contents" }
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
            
            // Ensure target directory exists
            targetFile.parentFile?.mkdirs()
            
            // Copy the file
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