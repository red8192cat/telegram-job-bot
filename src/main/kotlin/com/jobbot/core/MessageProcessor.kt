// File: src/main/kotlin/com/jobbot/core/MessageProcessor.kt

package com.jobbot.core

import com.jobbot.data.Database
import com.jobbot.data.models.*
import com.jobbot.infrastructure.monitoring.SystemMonitor
import com.jobbot.infrastructure.monitoring.ErrorTracker
import com.jobbot.shared.getLogger
import com.jobbot.shared.utils.TextUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.regex.Pattern

/**
 * UPDATED: MessageProcessor with simple media passthrough
 * Text matching logic unchanged - just passes media through to notifications
 */
class MessageProcessor(private val database: Database) {
    private val logger = getLogger("MessageProcessor")
    
    suspend fun processChannelMessage(message: ChannelMessage): List<NotificationMessage> = withContext(Dispatchers.IO) {
        val notifications = mutableListOf<NotificationMessage>()
        
        try {
            val users = database.getAllUsers()
            SystemMonitor.incrementMessageCount()
            
            for (user in users) {
                if (user.keywords.isNullOrBlank()) continue
                
                // Simple keyword matching logic (unchanged)
                val matchResult = if (message.text.isNotBlank()) {
                    // Text-based matching on caption/text content
                    matchesUserKeywords(message.text, user.keywords!!, user.ignoreKeywords)
                } else if (message.mediaGroup.isNotEmpty()) {
                    // Media-only message - only match if user has very general job keywords
                    val hasGeneralJobKeywords = user.keywords!!.lowercase().let { keywords ->
                        keywords.contains("job") || keywords.contains("work") || 
                        keywords.contains("position") || keywords.contains("vacancy") ||
                        keywords.contains("remote") || keywords.contains("hiring") ||
                        keywords.contains("developer") || keywords.contains("engineer") ||
                        keywords.contains("manager") || keywords.contains("employment")
                    }
                    
                    if (hasGeneralJobKeywords) {
                        MatchResult(isMatch = true, matchedKeywords = listOf("media-general-job-keywords"))
                    } else {
                        MatchResult(isMatch = false)
                    }
                } else {
                    MatchResult(isMatch = false)
                }
                
                if (matchResult.isMatch && !matchResult.blockedByIgnore) {
                    // Create notification with original media
                    notifications.add(
                        NotificationMessage(
                            userId = user.telegramId,
                            channelName = message.channelName ?: message.channelId,
                            messageText = message.text, // Plain text for fallback
                            formattedMessageText = message.formattedText, // Formatted text for display
                            senderUsername = message.senderUsername,
                            messageLink = message.messageLink,
                            mediaGroup = message.mediaGroup // NEW: Pass through original media
                        )
                    )
                    
                    logger.debug { "Match found for user ${user.telegramId} in channel ${message.channelId} (${message.mediaGroup.size} media items)" }
                }
            }
            
            logger.info { "Processed message from ${message.channelId}, found ${notifications.size} matches (${message.mediaGroup.size} media items)" }
            
        } catch (e: Exception) {
            logger.error(e) { "Error processing channel message" }
            ErrorTracker.logError("ERROR", "Failed to process channel message: ${e.message}", e)
        }
        
        notifications
    }
    
    // UNCHANGED: Existing keyword matching logic
    private fun matchesUserKeywords(messageText: String, keywordsString: String, ignoreKeywordsString: String?): MatchResult {
        val normalizedText = TextUtils.normalizeText(messageText)
        val keywords = parseKeywords(keywordsString)
        val ignoreKeywords = if (!ignoreKeywordsString.isNullOrBlank()) {
            parseKeywords(ignoreKeywordsString)
        } else {
            ParsedKeywords()
        }
        
        // Check ignore keywords first
        val ignoredMatches = findMatches(normalizedText, ignoreKeywords)
        if (ignoredMatches.isNotEmpty()) {
            return MatchResult(
                isMatch = false,
                blockedByIgnore = true,
                ignoredKeywords = ignoredMatches
            )
        }
        
        // Check required keywords (must ALL be present)
        val requiredMatches = keywords.required.filter { keyword ->
            containsKeyword(normalizedText, keyword)
        }
        
        if (requiredMatches.size != keywords.required.size) {
            return MatchResult(isMatch = false)
        }
        
        // Check required OR groups (at least one from each group must be present)
        for (orGroup in keywords.requiredOr) {
            val hasMatch = orGroup.any { keyword ->
                if (isRealAndGroup(keyword)) {
                    val andKeywords = smartSplitAndGroup(keyword)
                    andKeywords.all { andKeyword ->
                        containsKeyword(normalizedText, andKeyword)
                    }
                } else {
                    containsKeyword(normalizedText, keyword)
                }
            }
            if (!hasMatch) {
                return MatchResult(isMatch = false)
            }
        }
        
        // Check AND groups and collect successful matches
        val andGroupMatches = mutableListOf<String>()
        for (andGroup in keywords.andGroups) {
            val existingKeywords = andGroup.filter { keyword ->
                containsKeyword(normalizedText, keyword)
            }
            
            when {
                existingKeywords.isEmpty() -> {
                    logger.debug { "AND group [${andGroup.joinToString("+")}] not present - ignoring" }
                }
                existingKeywords.size == andGroup.size -> {
                    logger.debug { "AND group [${andGroup.joinToString("+")}] fully matched" }
                    andGroupMatches.addAll(existingKeywords)
                }
                else -> {
                    logger.debug { 
                        "AND group [${andGroup.joinToString("+")}] partial match failed: " +
                        "found ${existingKeywords.joinToString(", ")} " +
                        "but missing ${(andGroup - existingKeywords.toSet()).joinToString(", ")}"
                    }
                }
            }
        }
        
        // For optional keywords, at least one must match if there are no required keywords
        val optionalMatches = findMatches(normalizedText, ParsedKeywords(
            optional = keywords.optional,
            wildcards = keywords.wildcards,
            phrases = keywords.phrases
        ))
        
        val hasRequiredMatches = keywords.required.isNotEmpty() || keywords.requiredOr.isNotEmpty()
        val hasOptionalMatches = optionalMatches.isNotEmpty() || andGroupMatches.isNotEmpty()
        
        val isMatch = if (hasRequiredMatches) {
            true
        } else {
            hasOptionalMatches
        }
        
        if (!isMatch && logger.isDebugEnabled) {
            logger.debug { 
                "No match found - Keywords: '$keywordsString', " +
                "Normalized text: '${TextUtils.truncateText(normalizedText, 100)}', " +
                "Required matches: ${requiredMatches.size}/${keywords.required.size}, " +
                "Optional matches: ${optionalMatches.size}, " +
                "AND group matches: ${andGroupMatches.size}"
            }
        }

        return MatchResult(
            isMatch = isMatch,
            matchedKeywords = requiredMatches + optionalMatches + andGroupMatches
        )
    }
    
    private fun findMatches(text: String, keywords: ParsedKeywords): List<String> {
        val matches = mutableListOf<String>()
        
        keywords.optional.forEach { keyword ->
            if (containsKeyword(text, keyword)) {
                matches.add(keyword)
            }
        }
        
        keywords.wildcards.forEach { keyword ->
            if (containsKeyword(text, keyword)) {
                matches.add(keyword)
            }
        }
        
        keywords.phrases.forEach { phrase ->
            if (containsPhrase(text, phrase)) {
                matches.add(phrase.joinToString(" "))
            }
        }
        
        return matches
    }
    
    private fun containsKeyword(text: String, keyword: String): Boolean {
        return if (keyword.endsWith("*")) {
            val prefix = keyword.dropLast(1)
            if (prefix.isBlank()) return false
            
            val pattern = "(?<=^|\\s)${Pattern.quote(prefix)}[\\p{L}\\p{N}]*(?=\\s|$)"
            Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(text).find()
        } else {
            val pattern = "(?<=^|\\s)${Pattern.quote(keyword)}(?=\\s|$)"
            Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(text).find()
        }
    }
    
    private fun containsPhrase(text: String, phrase: List<String>): Boolean {
        if (phrase.isEmpty()) return false
        
        val regexParts = phrase.map { word ->
            if (word.endsWith("*")) {
                val prefix = word.dropLast(1)
                if (prefix.isBlank()) "[\\p{L}\\p{N}]+" else "${Pattern.quote(prefix)}[\\p{L}\\p{N}]*"
            } else {
                Pattern.quote(word)
            }
        }
        
        val pattern = "(?<=^|\\s)${regexParts.joinToString("\\s+")}(?=\\s|$)"
        return Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(text).find()
    }
    
    fun parseKeywords(keywordsString: String): ParsedKeywords {
        val required = mutableListOf<String>()
        val requiredOr = mutableListOf<List<String>>()
        val optional = mutableListOf<String>()
        val wildcards = mutableListOf<String>()
        val phrases = mutableListOf<List<String>>()
        val andGroups = mutableListOf<List<String>>()
        
        val keywords = smartSplitKeywords(keywordsString)
        
        for (keyword in keywords) {
            when {
                keyword.startsWith("[") && keyword.endsWith("]") -> {
                    val content = keyword.substring(1, keyword.length - 1)
                    
                    if (content.isBlank()) {
                        logger.warn { "Empty bracket syntax ignored: $keyword" }
                        continue
                    }
                    
                    if (content.contains("|") || content.contains("/")) {
                        val separator = if (content.contains("|")) "|" else "/"
                        val orKeywords = content.split(separator).map { it.trim() }.filter { it.isNotBlank() }
                        if (orKeywords.isNotEmpty()) {
                            requiredOr.add(orKeywords)
                        }
                    } else {
                        required.add(content)
                    }
                }
                
                isRealAndGroup(keyword) -> {
                    val andKeywords = smartSplitAndGroup(keyword)
                    if (andKeywords.size >= 2) {
                        andGroups.add(andKeywords)
                    } else {
                        categorizeKeyword(keyword, optional, wildcards, phrases)
                    }
                }
                
                keyword.contains(" ") -> {
                    val phraseWords = keyword.split("\\s+".toRegex()).filter { it.isNotBlank() }
                    phrases.add(phraseWords)
                }
                
                else -> {
                    categorizeKeyword(keyword, optional, wildcards, phrases)
                }
            }
        }
        
        return ParsedKeywords(
            required = required,
            requiredOr = requiredOr,
            optional = optional,
            wildcards = wildcards,
            phrases = phrases,
            andGroups = andGroups
        )
    }
    
    private fun smartSplitKeywords(keywordsString: String): List<String> {
        val commaSplit = keywordsString.split(",").map { it.trim() }.filter { it.isNotBlank() }
        val result = mutableListOf<String>()
        
        for (item in commaSplit) {
            if (item.contains("[") && item.contains("]") && !item.endsWith("]")) {
                val splitResult = splitBracketKeyword(item)
                if (splitResult.size > 1) {
                    logger.debug { "Auto-split malformed keyword '$item' into: ${splitResult.joinToString(", ")}" }
                    result.addAll(splitResult)
                } else {
                    result.add(item)
                }
            } else {
                result.add(item)
            }
        }
        
        return result
    }
    
    private fun splitBracketKeyword(keyword: String): List<String> {
        val bracketPattern = Regex("""(\[[^\]]+\])(.*)""")
        val match = bracketPattern.find(keyword)
        
        return if (match != null) {
            val bracketPart = match.groupValues[1]
            val remaining = match.groupValues[2].trim()
            
            val parts = mutableListOf(bracketPart)
            if (remaining.isNotEmpty()) {
                val remainingParts = remaining.split("\\s+".toRegex())
                    .filter { it.isNotBlank() }
                parts.addAll(remainingParts)
            }
            
            logger.warn { 
                "Found malformed syntax: '$keyword'. " +
                "Consider using commas: '${parts.joinToString(", ")}' for clarity."
            }
            
            parts
        } else {
            listOf(keyword)
        }
    }
    
    private fun isRealAndGroup(keyword: String): Boolean {
        if (!keyword.contains("+")) return false
        
        val specialCases = listOf("c++", "c#", ".net", "f#")
        if (specialCases.any { keyword.equals(it, ignoreCase = true) }) {
            return false
        }
        
        if (keyword.endsWith("+") && !keyword.startsWith("+")) {
            val withoutTrailing = keyword.dropLast(1)
            return withoutTrailing.contains("+")
        }
        
        val parts = smartSplitAndGroup(keyword)
        return parts.size >= 2
    }
    
    private fun smartSplitAndGroup(keyword: String): List<String> {
        val cppPlaceholder = "__CPP_PLACEHOLDER__"
        val processed = keyword.replace(Regex("\\bc\\+\\+\\b", RegexOption.IGNORE_CASE), cppPlaceholder)
        
        val parts = processed.split("+")
            .map { it.replace(cppPlaceholder, "c++") }
            .map { it.trim() }
            .filter { it.isNotBlank() }
        
        return parts
    }
    
    private fun categorizeKeyword(keyword: String, optional: MutableList<String>, wildcards: MutableList<String>, phrases: MutableList<List<String>>) {
        when {
            keyword.endsWith("*") -> wildcards.add(keyword)
            else -> optional.add(keyword)
        }
    }
}