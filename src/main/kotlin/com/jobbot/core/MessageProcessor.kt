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

class MessageProcessor(private val database: Database) {
    private val logger = getLogger("MessageProcessor")
    
    suspend fun processChannelMessage(message: ChannelMessage): List<NotificationMessage> = withContext(Dispatchers.IO) {
        val notifications = mutableListOf<NotificationMessage>()
        
        try {
            val users = database.getAllUsers()
            SystemMonitor.incrementMessageCount()
            
            for (user in users) {
                if (user.keywords.isNullOrBlank()) continue
                
                val matchResult = matchesUserKeywords(message.text, user.keywords!!, user.ignoreKeywords)
                
                if (matchResult.isMatch && !matchResult.blockedByIgnore) {
                    notifications.add(
                        NotificationMessage(
                            userId = user.telegramId,
                            channelName = message.channelName ?: message.channelId,
                            messageText = message.text,
                            senderUsername = message.senderUsername,
                            messageLink = message.messageLink  // NEW: Pass through the message link
                        )
                    )
                    
                    logger.debug { "Match found for user ${user.telegramId} in channel ${message.channelId}" }
                }
            }
            
            logger.info { "Processed message from ${message.channelId}, found ${notifications.size} matches" }
            
        } catch (e: Exception) {
            logger.error(e) { "Error processing channel message" }
            ErrorTracker.logError("ERROR", "Failed to process channel message: ${e.message}", e)
        }
        
        notifications
    }
    
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
                // 🔧 FIXED: Handle AND groups within OR groups (e.g., [java+kotlin / python])
                if (isRealAndGroup(keyword)) {
                    // This is an AND group within an OR - all keywords in this item must be present
                    val andKeywords = smartSplitAndGroup(keyword)
                    andKeywords.all { andKeyword ->
                        containsKeyword(normalizedText, andKeyword)
                    }
                } else {
                    // Regular keyword
                    containsKeyword(normalizedText, keyword)
                }
            }
            if (!hasMatch) {
                return MatchResult(isMatch = false)
            }
        }
        
        // 🔧 FIXED: Check AND groups and collect successful matches
        val andGroupMatches = mutableListOf<String>()
        for (andGroup in keywords.andGroups) {
            // Check which keywords from this AND group exist in the text
            val existingKeywords = andGroup.filter { keyword ->
                containsKeyword(normalizedText, keyword)
            }
            
            when {
                // If NO keywords from the AND group exist, ignore this group (it's optional)
                existingKeywords.isEmpty() -> {
                    logger.debug { "AND group [${andGroup.joinToString("+")}] not present - ignoring" }
                }
                // If ALL keywords from the AND group exist, count as successful match
                existingKeywords.size == andGroup.size -> {
                    logger.debug { "AND group [${andGroup.joinToString("+")}] fully matched" }
                    andGroupMatches.addAll(existingKeywords)
                }
                // If SOME but not ALL keywords exist, this group fails BUT don't block overall match
                else -> {
                    logger.debug { 
                        "AND group [${andGroup.joinToString("+")}] partial match failed: " +
                        "found ${existingKeywords.joinToString(", ")} " +
                        "but missing ${(andGroup - existingKeywords.toSet()).joinToString(", ")}"
                    }
                    // Don't return false here - just don't count this group as a match
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
            // If we have required keywords, we already passed them, so it's a match
            true
        } else {
            // If no required keywords, we need at least one optional match (including AND groups)
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
        
        // Exact matches
        keywords.optional.forEach { keyword ->
            if (containsKeyword(text, keyword)) {
                matches.add(keyword)
            }
        }
        
        // Wildcard matches
        keywords.wildcards.forEach { keyword ->
            if (containsKeyword(text, keyword)) {
                matches.add(keyword)
            }
        }
        
        // Phrase matches
        keywords.phrases.forEach { phrase ->
            if (containsPhrase(text, phrase)) {
                matches.add(phrase.joinToString(" "))
            }
        }
        
        return matches
    }
    
    private fun containsKeyword(text: String, keyword: String): Boolean {
        return if (keyword.endsWith("*")) {
            // Wildcard matching
            val prefix = keyword.dropLast(1)
            if (prefix.isBlank()) return false
            
            // FIXED: Use Unicode letter class instead of \w for Cyrillic support
            val pattern = "(?<=^|\\s)${Pattern.quote(prefix)}[\\p{L}\\p{N}]*(?=\\s|$)"
            Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(text).find()
        } else {
            // Exact matching
            val pattern = "(?<=^|\\s)${Pattern.quote(keyword)}(?=\\s|$)"
            Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(text).find()
        }
    }
    
    private fun containsPhrase(text: String, phrase: List<String>): Boolean {
        if (phrase.isEmpty()) return false
        
        // Build regex for adjacent wildcard words
        val regexParts = phrase.map { word ->
            if (word.endsWith("*")) {
                val prefix = word.dropLast(1)
                // FIXED: Use Unicode letter class instead of \w for Cyrillic support
                if (prefix.isBlank()) "[\\p{L}\\p{N}]+" else "${Pattern.quote(prefix)}[\\p{L}\\p{N}]*"
            } else {
                Pattern.quote(word)
            }
        }
        
        // FIXED: Use Unicode-safe word boundaries
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
        
        // 🔧 FIXED: Smart splitting that handles missing commas around brackets
        val keywords = smartSplitKeywords(keywordsString)
        
        for (keyword in keywords) {
            when {
                // Required keywords in brackets [keyword]
                keyword.startsWith("[") && keyword.endsWith("]") -> {
                    val content = keyword.substring(1, keyword.length - 1)
                    
                    if (content.isBlank()) {
                        logger.warn { "Empty bracket syntax ignored: $keyword" }
                        continue
                    }
                    
                    // FIXED: Support both | and / for OR operations
                    if (content.contains("|") || content.contains("/")) {
                        // Required OR group [keyword1|keyword2] or [keyword1/keyword2]
                        val separator = if (content.contains("|")) "|" else "/"
                        val orKeywords = content.split(separator).map { it.trim() }.filter { it.isNotBlank() }
                        if (orKeywords.isNotEmpty()) {
                            requiredOr.add(orKeywords)
                        }
                    } else {
                        // Single required keyword [keyword]
                        required.add(content)
                    }
                }
                
                // 🔧 FIXED: Smart AND group detection (handles c++, remote+, etc.)
                isRealAndGroup(keyword) -> {
                    val andKeywords = smartSplitAndGroup(keyword)
                    if (andKeywords.size >= 2) {
                        andGroups.add(andKeywords)
                    } else {
                        // Not a real AND group, treat as single keyword
                        categorizeKeyword(keyword, optional, wildcards, phrases)
                    }
                }
                
                // Phrases with spaces
                keyword.contains(" ") -> {
                    val phraseWords = keyword.split("\\s+".toRegex()).filter { it.isNotBlank() }
                    phrases.add(phraseWords)
                }
                
                // Single keywords (wildcards or exact)
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
    
    /**
     * Smart keyword splitting that handles missing commas around brackets
     * Examples:
     * - "[admin*] linux" → ["[admin*]", "linux"]
     * - "[remote/onlin*], python" → ["[remote/onlin*]", "python"]
     * - "java, [senior*] python" → ["java", "[senior*]", "python"]
     */
    private fun smartSplitKeywords(keywordsString: String): List<String> {
        // First, split by commas normally
        val commaSplit = keywordsString.split(",").map { it.trim() }.filter { it.isNotBlank() }
        
        val result = mutableListOf<String>()
        
        for (item in commaSplit) {
            // Check if this item contains brackets but doesn't end with ]
            if (item.contains("[") && item.contains("]") && !item.endsWith("]")) {
                // This looks like "[admin*] linux" - try to split it
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
    
    /**
     * Split a keyword that contains brackets followed by other text
     * Example: "[admin*] linux python" → ["[admin*]", "linux", "python"]
     */
    private fun splitBracketKeyword(keyword: String): List<String> {
        val bracketPattern = Regex("""(\[[^\]]+\])(.*)""")
        val match = bracketPattern.find(keyword)
        
        return if (match != null) {
            val bracketPart = match.groupValues[1]
            val remaining = match.groupValues[2].trim()
            
            val parts = mutableListOf(bracketPart)
            if (remaining.isNotEmpty()) {
                // Split remaining by spaces (but be careful with phrases)
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
    
    /**
     * Determines if a keyword is a real AND group or contains + for other reasons
     */
    private fun isRealAndGroup(keyword: String): Boolean {
        if (!keyword.contains("+")) return false
        
        // 🔧 Handle programming languages and special cases
        val specialCases = listOf("c++", "c#", ".net", "f#")
        if (specialCases.any { keyword.equals(it, ignoreCase = true) }) {
            return false
        }
        
        // Handle cases ending with + (like remote+)
        if (keyword.endsWith("+") && !keyword.startsWith("+")) {
            val withoutTrailing = keyword.dropLast(1)
            // Only treat as AND group if there are multiple + signs
            return withoutTrailing.contains("+")
        }
        
        // Check if we get at least 2 valid parts after smart split
        val parts = smartSplitAndGroup(keyword)
        return parts.size >= 2
    }
    
    /**
     * Smart splitting that handles c++, remote+, etc.
     */
    private fun smartSplitAndGroup(keyword: String): List<String> {
        // Handle c++ specially - replace with placeholder during split
        val cppPlaceholder = "__CPP_PLACEHOLDER__"
        val processed = keyword.replace(Regex("\\bc\\+\\+\\b", RegexOption.IGNORE_CASE), cppPlaceholder)
        
        val parts = processed.split("+")
            .map { it.replace(cppPlaceholder, "c++") }
            .map { it.trim() }
            .filter { it.isNotBlank() }
        
        return parts
    }
    
    /**
     * Categorize a single keyword as wildcard or exact
     */
    private fun categorizeKeyword(keyword: String, optional: MutableList<String>, wildcards: MutableList<String>, phrases: MutableList<List<String>>) {
        when {
            keyword.endsWith("*") -> wildcards.add(keyword)
            else -> optional.add(keyword)
        }
    }
}