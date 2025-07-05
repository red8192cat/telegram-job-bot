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
                            senderUsername = message.senderUsername  // ✅ FIXED: Include sender username
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
                containsKeyword(normalizedText, keyword)
            }
            if (!hasMatch) {
                return MatchResult(isMatch = false)
            }
        }
        
        // Check AND groups (all keywords in each group must be present)
        for (andGroup in keywords.andGroups) {
            val hasAllInGroup = andGroup.all { keyword ->
                containsKeyword(normalizedText, keyword)
            }
            if (!hasAllInGroup) {
                return MatchResult(isMatch = false)
            }
        }
        
        // For optional keywords, at least one must match if there are no required keywords
        val optionalMatches = findMatches(normalizedText, ParsedKeywords(
            optional = keywords.optional,
            wildcards = keywords.wildcards,
            phrases = keywords.phrases
        ))
        
        val hasRequiredMatches = keywords.required.isNotEmpty() || keywords.requiredOr.isNotEmpty() || keywords.andGroups.isNotEmpty()
        val hasOptionalMatches = optionalMatches.isNotEmpty()
        
        val isMatch = if (hasRequiredMatches) {
            // If we have required keywords, we already passed them, so it's a match
            true
        } else {
            // If no required keywords, we need at least one optional match
            hasOptionalMatches
        }
        
        if (!isMatch && logger.isDebugEnabled) {
            logger.debug { 
                "No match found - Keywords: '$keywordsString', " +
                "Normalized text: '${TextUtils.truncateText(normalizedText, 100)}', " +
                "Required matches: ${requiredMatches.size}/${keywords.required.size}, " +
                "Optional matches: ${optionalMatches.size}"
            }
        }

        return MatchResult(
            isMatch = isMatch,
            matchedKeywords = requiredMatches + optionalMatches
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
        
        val keywords = keywordsString.split(",").map { it.trim() }.filter { it.isNotBlank() }
        
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
                
                // AND groups with +
                keyword.contains("+") -> {
                    val andKeywords = keyword.split("+").map { it.trim() }.filter { it.isNotBlank() }
                    andGroups.add(andKeywords)
                }
                
                // Phrases with spaces
                keyword.contains(" ") -> {
                    val phraseWords = keyword.split("\\s+".toRegex()).filter { it.isNotBlank() }
                    phrases.add(phraseWords)
                }
                
                // Wildcards
                keyword.endsWith("*") -> {
                    wildcards.add(keyword)
                }
                
                // Optional exact keywords
                else -> {
                    optional.add(keyword)
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
}
