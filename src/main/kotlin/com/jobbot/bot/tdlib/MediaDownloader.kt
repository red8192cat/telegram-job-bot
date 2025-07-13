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
 * ENHANCED: Robust filename handling for Docker containers with Unicode filenames
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
     * DOCKER-AWARE: Handle completed download with comprehensive permission debugging
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
            
            // DEBUG: Comprehensive permission analysis
            debugPermissions(tdlibSourcePath, expectedSize)
            
            // STRATEGY 1: Try direct access first (works when permissions are correct)
            val directFile = File(tdlibSourcePath)
            if (tryDirectFileAccess(directFile, expectedSize, targetPath, deferred)) {
                return
            }
            
            // STRATEGY 2: Search directory for alternative files
            if (tryDirectorySearch(directFile.parentFile, expectedSize, targetPath, deferred)) {
                return
            }
            
            logger.warn { "All file access strategies failed - this indicates a Docker permission or TDLib issue" }
            deferred.complete(null)
            
        } catch (e: Exception) {
            logger.error(e) { "Error handling completed download" }
            deferred.complete(null)
        }
    }
    
    /**
     * Comprehensive permission debugging to understand the root cause
     */
    private fun debugPermissions(filePath: String, expectedSize: Long) {
        logger.info { "=== PERMISSION DEBUGGING ===" }
        
        try {
            // Check what user our Java process is running as
            val currentUser = System.getProperty("user.name")
            val userHome = System.getProperty("user.home")
            val javaVersion = System.getProperty("java.version")
            
            logger.info { "Java process info:" }
            logger.info { "  user.name: $currentUser" }
            logger.info { "  user.home: $userHome" }
            logger.info { "  java.version: $javaVersion" }
            
            // Get system user info via external command
            try {
                val whoamiProcess = ProcessBuilder("whoami").start()
                val whoamiResult = whoamiProcess.inputStream.bufferedReader().readText().trim()
                logger.info { "  whoami result: $whoamiResult" }
                
                val idProcess = ProcessBuilder("id").start()
                val idResult = idProcess.inputStream.bufferedReader().readText().trim()
                logger.info { "  id result: $idResult" }
            } catch (e: Exception) {
                logger.warn { "Could not get system user info: ${e.message}" }
            }
            
            // Analyze the specific file
            val file = File(filePath)
            logger.info { "File analysis for: $filePath" }
            logger.info { "  file.exists(): ${file.exists()}" }
            logger.info { "  file.isFile(): ${file.isFile()}" }
            logger.info { "  file.canRead(): ${file.canRead()}" }
            logger.info { "  file.canWrite(): ${file.canWrite()}" }
            logger.info { "  file.length(): ${file.length()}" }
            logger.info { "  expected size: $expectedSize" }
            
            // Get detailed file info via ls command
            try {
                val lsProcess = ProcessBuilder("ls", "-la", filePath).start()
                val lsResult = lsProcess.inputStream.bufferedReader().readText().trim()
                logger.info { "  ls -la result: $lsResult" }
            } catch (e: Exception) {
                logger.warn { "Could not get ls info: ${e.message}" }
            }
            
            // Get file ownership details via stat command
            try {
                val statProcess = ProcessBuilder("stat", "-c", "%n %s %U:%G %a", filePath).start()
                val statResult = statProcess.inputStream.bufferedReader().readText().trim()
                logger.info { "  stat result: $statResult" }
            } catch (e: Exception) {
                logger.warn { "Could not get stat info: ${e.message}" }
            }
            
            // Check directory permissions
            val parentDir = file.parentFile
            if (parentDir != null) {
                logger.info { "Parent directory analysis: ${parentDir.absolutePath}" }
                logger.info { "  dir.exists(): ${parentDir.exists()}" }
                logger.info { "  dir.canRead(): ${parentDir.canRead()}" }
                logger.info { "  dir.canExecute(): ${parentDir.canExecute()}" }
                
                try {
                    val dirLsProcess = ProcessBuilder("ls", "-lad", parentDir.absolutePath).start()
                    val dirLsResult = dirLsProcess.inputStream.bufferedReader().readText().trim()
                    logger.info { "  dir ls -lad result: $dirLsResult" }
                } catch (e: Exception) {
                    logger.warn { "Could not get directory ls info: ${e.message}" }
                }
            }
            
            // Test actual file access with different methods
            logger.info { "File access testing:" }
            
            // Test 1: Basic FileInputStream
            try {
                file.inputStream().use { stream ->
                    val firstByte = stream.read()
                    logger.info { "  FileInputStream test: SUCCESS (first byte: $firstByte)" }
                }
            } catch (e: Exception) {
                logger.warn { "  FileInputStream test: FAILED - ${e.javaClass.simpleName}: ${e.message}" }
            }
            
            // Test 2: Files.readAllBytes
            try {
                val bytes = java.nio.file.Files.readAllBytes(file.toPath())
                logger.info { "  Files.readAllBytes test: SUCCESS (${bytes.size} bytes)" }
            } catch (e: Exception) {
                logger.warn { "  Files.readAllBytes test: FAILED - ${e.javaClass.simpleName}: ${e.message}" }
            }
            
            // Test 3: RandomAccessFile
            try {
                java.io.RandomAccessFile(file, "r").use { raf ->
                    val length = raf.length()
                    logger.info { "  RandomAccessFile test: SUCCESS ($length bytes)" }
                }
            } catch (e: Exception) {
                logger.warn { "  RandomAccessFile test: FAILED - ${e.javaClass.simpleName}: ${e.message}" }
            }
            
            // Test 4: Check if it's a special file type
            try {
                val path = file.toPath()
                val attrs = java.nio.file.Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java)
                logger.info { "  File attributes:" }
                logger.info { "    isRegularFile: ${attrs.isRegularFile}" }
                logger.info { "    isDirectory: ${attrs.isDirectory}" }
                logger.info { "    isSymbolicLink: ${attrs.isSymbolicLink}" }
                logger.info { "    size: ${attrs.size()}" }
                logger.info { "    lastModified: ${attrs.lastModifiedTime()}" }
            } catch (e: Exception) {
                logger.warn { "  File attributes test: FAILED - ${e.javaClass.simpleName}: ${e.message}" }
            }
            
        } catch (e: Exception) {
            logger.error(e) { "Error during permission debugging" }
        }
        
        logger.info { "=== END PERMISSION DEBUGGING ===" }
    }
    
    /**
     * Try direct file access with detailed error reporting
     */
    private fun tryDirectFileAccess(
        directFile: File, 
        expectedSize: Long, 
        targetPath: String, 
        deferred: CompletableDeferred<String?>
    ): Boolean {
        logger.info { "Attempting direct file access..." }
        
        try {
            if (!directFile.exists()) {
                logger.warn { "File does not exist: ${directFile.absolutePath}" }
                return false
            }
            
            if (!directFile.isFile()) {
                logger.warn { "Path is not a regular file: ${directFile.absolutePath}" }
                return false
            }
            
            val actualSize = directFile.length()
            logger.info { "File size check: actual=$actualSize, expected=$expectedSize" }
            
            if (actualSize != expectedSize) {
                logger.warn { "File size mismatch: expected $expectedSize, got $actualSize" }
                return false
            }
            
            if (actualSize == 0L) {
                logger.warn { "File has 0 bytes" }
                return false
            }
            
            // Test actual read access
            directFile.inputStream().use { stream ->
                val buffer = ByteArray(1024)
                val bytesRead = stream.read(buffer)
                if (bytesRead > 0) {
                    logger.info { "✅ Direct file access successful: read $bytesRead bytes" }
                    copyFileToTarget(directFile, targetPath, deferred)
                    return true
                } else {
                    logger.warn { "File read returned 0 bytes despite file size $actualSize" }
                    return false
                }
            }
        } catch (e: Exception) {
            logger.warn { "Direct file access failed: ${e.javaClass.simpleName} - ${e.message}" }
            return false
        }
    }
    
    /**
     * Search directory for files with correct size (simplified version)
     */
    private fun tryDirectorySearch(
        parentDir: File?, 
        expectedSize: Long, 
        targetPath: String, 
        deferred: CompletableDeferred<String?>
    ): Boolean {
        if (parentDir?.exists() != true) {
            logger.warn { "Parent directory does not exist: ${parentDir?.absolutePath}" }
            return false
        }
        
        try {
            logger.info { "Searching directory for file with size: $expectedSize bytes" }
            
            val allFiles = try {
                parentDir.listFiles()?.toList() ?: emptyList()
            } catch (e: Exception) {
                logger.warn { "Cannot list directory contents: ${e.message}" }
                return false
            }
            
            logger.info { "Directory contains ${allFiles.size} total files" }
            
            // Test each file
            for (candidateFile in allFiles) {
                try {
                    if (!candidateFile.isFile) continue
                    
                    val actualSize = candidateFile.length()
                    val safeFileName = getSafeFilename(candidateFile)
                    
                    logger.info { "Testing file: $safeFileName (size: $actualSize)" }
                    
                    if (actualSize == expectedSize && actualSize > 0) {
                        logger.info { "Found size match, testing access..." }
                        if (tryDirectFileAccess(candidateFile, expectedSize, targetPath, deferred)) {
                            return true
                        }
                    }
                    
                } catch (e: Exception) {
                    logger.debug { "Error testing candidate file: ${e.message}" }
                }
            }
            
        } catch (e: Exception) {
            logger.warn(e) { "Error during directory search" }
        }
        
        return false
    }
    
    /**
     * Get filename safely for logging (handles encoding errors gracefully)
     */
    private fun getSafeFilename(file: File): String {
        return try {
            // Try to get the filename normally
            val name = file.name
            
            // Check if the name contains replacement characters (indicates encoding issues)
            if (name.contains("�") || name.contains("?")) {
                // Filename has encoding issues, show a safe representation
                "<unicode-file-${file.length()}bytes>"
            } else {
                name
            }
        } catch (e: Exception) {
            // If getting the name throws an exception, show a safe representation
            "<encoding-error-${file.length()}bytes>"
        }
    }
    
    /**
     * Enhanced directory logging with actual file access testing
     */
    private fun logDirectoryContentsDetailed(allFiles: List<File>, expectedSize: Long) {
        try {
            logger.debug { "Directory detailed analysis (${allFiles.size} files):" }
            
            allFiles.forEachIndexed { index, file ->
                try {
                    val actualSize = file.length()
                    val age = (System.currentTimeMillis() - file.lastModified()) / 1000
                    val sizeMatch = if (actualSize == expectedSize) "✅" else "❌"
                    val safeFileName = getSafeFilename(file)
                    
                    // Test actual readability
                    val readabilityTest = try {
                        file.inputStream().use { stream ->
                            val firstByte = stream.read()
                            if (firstByte == -1) "EMPTY" else "READABLE"
                        }
                    } catch (e: Exception) {
                        "PERMISSION_DENIED: ${e.javaClass.simpleName}"
                    }
                    
                    logger.debug { 
                        "  [$index] $safeFileName: ${actualSize} bytes (age: ${age}s) $sizeMatch - $readabilityTest"
                    }
                    
                    // Additional permission details for files with correct size
                    if (actualSize == expectedSize) {
                        logger.debug {
                            "    Permissions: readable=${file.canRead()}, writable=${file.canWrite()}, " +
                            "exists=${file.exists()}, isFile=${file.isFile()}"
                        }
                    }
                    
                } catch (e: Exception) {
                    logger.debug { "  [$index] <error examining file>: ${e.message}" }
                }
            }
            
        } catch (e: Exception) {
            logger.error(e) { "Error in detailed directory logging" }
        }
    }
    
    /**
     * Copy file to target with enhanced verification and Docker permission handling
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
            
            // Enhanced copy with stream verification
            logger.debug { "Copying ${sourceFile.absolutePath} to $targetPath" }
            
            // Use buffered streams for better performance and verification
            sourceFile.inputStream().buffered().use { input ->
                targetFile.outputStream().buffered().use { output ->
                    val bytesCopied = input.copyTo(output)
                    logger.debug { "Copied $bytesCopied bytes to target" }
                }
            }
            
            // Verify copy with actual file content check
            if (!targetFile.exists()) {
                logger.warn { "Target file does not exist after copy" }
                deferred.complete(null)
                return
            }
            
            if (targetFile.length() != sourceFile.length()) {
                logger.warn { "Size mismatch after copy: target=${targetFile.length()}, source=${sourceFile.length()}" }
                deferred.complete(null)
                return
            }
            
            if (targetFile.length() == 0L) {
                logger.warn { "Target file is 0 bytes after copy" }
                deferred.complete(null)
                return
            }
            
            // Final readability test on target file
            try {
                targetFile.inputStream().use { stream ->
                    val testByte = stream.read()
                    if (testByte == -1) {
                        logger.warn { "Target file appears empty despite having size ${targetFile.length()}" }
                        deferred.complete(null)
                        return
                    }
                }
            } catch (e: Exception) {
                logger.warn { "Cannot read target file after copy: ${e.message}" }
                deferred.complete(null)
                return
            }
            
            logger.info { "Successfully copied and verified file: $targetPath (${targetFile.length()} bytes)" }
            deferred.complete(targetPath)
            
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