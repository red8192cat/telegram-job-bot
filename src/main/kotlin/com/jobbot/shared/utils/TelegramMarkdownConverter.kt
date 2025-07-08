package com.jobbot.shared.utils

import com.jobbot.shared.getLogger
import java.util.regex.Pattern

/**
 * Enhanced Telegram MarkdownV2 converter for the JobBot project
 * Combines bidirectional conversion with improved TDLib formatting support
 */
object TelegramMarkdownConverter {
    private val logger = getLogger("TelegramMarkdownConverter")
    
    // Characters that need escaping in Telegram MarkdownV2 (official spec)
    private val TELEGRAM_ESCAPE_CHARS = setOf('_', '*', '[', ']', '(', ')', '~', '`', '>', '#', '+', '-', '=', '|', '{', '}', '.', '!')
    
    // Regex patterns for standard Markdown to Telegram conversion
    private val MD_BOLD_PATTERN = Pattern.compile("""\*\*(.*?)\*\*""")
    private val MD_ITALIC_PATTERN = Pattern.compile("""(?<!\*)\*([^*\n]+)\*(?!\*)""")
    private val MD_UNDERLINE_PATTERN = Pattern.compile("""__(.*?)__""")
    private val MD_STRIKETHROUGH_PATTERN = Pattern.compile("""~~(.*?)~~""")
    private val MD_SPOILER_PATTERN = Pattern.compile("""\|\|(.*?)\|\|""")
    private val MD_CODE_INLINE_PATTERN = Pattern.compile("""`([^`\n]+)`""")
    private val MD_CODE_BLOCK_PATTERN = Pattern.compile("""```(\w*)\n?(.*?)```""", Pattern.DOTALL)
    private val MD_LINK_PATTERN = Pattern.compile("""\[([^\]]+)\]\(([^)]+)\)""")
    private val MD_QUOTE_PATTERN = Pattern.compile("""^>\s*(.*)$""", Pattern.MULTILINE)
    
    /**
     * Convert standard Markdown to Telegram MarkdownV2 format
     * This is useful for processing user input or stored markdown content
     */
    fun markdownToTelegram(markdown: String): String {
        var result = markdown
        
        try {
            // Process code blocks first (to avoid processing content inside them)
            val codeBlocks = mutableListOf<String>()
            result = MD_CODE_BLOCK_PATTERN.matcher(result).replaceAll { match ->
                val language = match.group(1) ?: ""
                val code = escapeCodeContent(match.group(2))
                val placeholder = "###CODEBLOCK${codeBlocks.size}###"
                codeBlocks.add(if (language.isNotEmpty()) "```$language\n$code```" else "```\n$code```")
                placeholder
            }
            
            // Process inline code
            val inlineCodes = mutableListOf<String>()
            result = MD_CODE_INLINE_PATTERN.matcher(result).replaceAll { match ->
                val placeholder = "###INLINE${inlineCodes.size}###"
                val escapedCode = escapeCodeContent(match.group(1))
                inlineCodes.add("`$escapedCode`")
                placeholder
            }
            
            // Process quotes
            result = MD_QUOTE_PATTERN.matcher(result).replaceAll { match ->
                ">${escapeForFormatting(match.group(1))}"
            }
            
            // Process formatting (order matters!)
            result = MD_SPOILER_PATTERN.matcher(result).replaceAll { match ->
                "||${escapeForFormatting(match.group(1))}||"
            }
            result = MD_STRIKETHROUGH_PATTERN.matcher(result).replaceAll { match ->
                "~${escapeForFormatting(match.group(1))}~"
            }
            result = MD_UNDERLINE_PATTERN.matcher(result).replaceAll { match ->
                "__${escapeForFormatting(match.group(1))}__"
            }
            result = MD_BOLD_PATTERN.matcher(result).replaceAll { match ->
                "*${escapeForFormatting(match.group(1))}*"
            }
            result = MD_ITALIC_PATTERN.matcher(result).replaceAll { match ->
                "_${escapeForFormatting(match.group(1))}_"
            }
            
            // Process links
            result = MD_LINK_PATTERN.matcher(result).replaceAll { match ->
                val linkText = escapeForFormatting(match.group(1))
                val url = escapeUrlInLink(match.group(2))
                "[$linkText]($url)"
            }
            
            // Escape remaining special characters
            result = escapeMarkdownV2(result, protectedRanges = findProtectedRanges(result))
            
            // Restore code blocks and inline codes
            codeBlocks.forEachIndexed { index, code ->
                result = result.replace("###CODEBLOCK$index###", code)
            }
            inlineCodes.forEachIndexed { index, code ->
                result = result.replace("###INLINE$index###", code)
            }
            
            return result.trim()
            
        } catch (e: Exception) {
            logger.warn(e) { "Error converting markdown to Telegram format, returning escaped plain text" }
            return escapeMarkdownV2(markdown)
        }
    }
    
    /**
     * Escape text for MarkdownV2 according to official Telegram specification
     * Characters that must be escaped: '_', '*', '[', ']', '(', ')', '~', '`', '>', '#', '+', '-', '=', '|', '{', '}', '.', '!'
     */
    fun escapeMarkdownV2(text: String, protectedRanges: List<IntRange> = emptyList()): String {
        val result = StringBuilder()
        
        text.forEachIndexed { index, char ->
            val inProtectedRange = protectedRanges.any { range -> index in range }
            if (!inProtectedRange && char in TELEGRAM_ESCAPE_CHARS) {
                result.append("\\$char")
            } else {
                result.append(char)
            }
        }
        
        return result.toString()
    }
    
    /**
     * Escape text that will be inside formatting entities
     * Only escape characters that could break the current formatting
     */
    fun escapeForFormatting(text: String): String {
        return text
            .replace("\\", "\\\\")  // Always escape backslashes first
            .replace("[", "\\[")    // Could start a link
            .replace("]", "\\]")    // Could end a link
            .replace("(", "\\(")    // Could start URL
            .replace(")", "\\)")    // Could end URL
    }
    
    /**
     * Escape URL in link context - only escape ) and \ inside URL part
     */
    fun escapeUrlInLink(url: String): String {
        return url
            .replace("\\", "\\\\")  // Escape backslashes
            .replace(")", "\\)")    // Escape closing parenthesis
    }
    
    /**
     * Escape content inside code blocks/inline code
     * Only escape backticks and backslashes
     */
    private fun escapeCodeContent(code: String): String {
        return code
            .replace("\\", "\\\\")  // Escape backslashes
            .replace("`", "\\`")    // Escape backticks
    }
    
    /**
     * Find ranges that should be protected from escaping (existing formatting)
     */
    private fun findProtectedRanges(text: String): List<IntRange> {
        val protectedRanges = mutableListOf<IntRange>()
        
        // Protect existing formatting patterns
        listOf(
            """\*[^*\n]+\*""",          // bold
            """_[^_\n]+_""",            // italic  
            """__[^_\n]+__""",          // underline
            """~[^~\n]+~""",            // strikethrough
            """\|\|[^|]+\|\|""",        // spoiler
            """`[^`\n]+`""",            // inline code
            """```[\s\S]*?```""",       // code blocks
            """\[[^\]]+\]\([^)]+\)""",  // links
            """^>\s*.*$"""              // quotes
        ).forEach { pattern ->
            val matcher = Pattern.compile(pattern, Pattern.MULTILINE).matcher(text)
            while (matcher.find()) {
                protectedRanges.add(matcher.start()..matcher.end() - 1)
            }
        }
        
        return protectedRanges
    }
    
    /**
     * Check if text contains potentially problematic MarkdownV2 patterns
     */
    fun hasUnbalancedMarkup(markdown: String): Boolean {
        return try {
            // Check for unbalanced brackets (critical for links)
            val openBrackets = markdown.count { it == '[' }
            val closeBrackets = markdown.count { it == ']' }
            val openParens = markdown.count { it == '(' }
            val closeParens = markdown.count { it == ')' }
            
            if (openBrackets != closeBrackets || openParens != closeParens) {
                return true
            }
            
            // Check for unclosed code blocks
            val tripleBackticks = markdown.split("```").size - 1
            if (tripleBackticks % 2 != 0) {
                return true
            }
            
            // Check for reasonable length
            if (markdown.length > 4096) {
                return true
            }
            
            false
        } catch (e: Exception) {
            true
        }
    }
    
    /**
     * Validate if text is properly formatted Telegram MarkdownV2
     */
    fun isValidTelegramMarkdown(text: String): Boolean {
        return try {
            // Check if it has MarkdownV2 patterns
            val hasMarkdownPatterns = listOf(
                """\*[^*\n]+\*""",          // bold
                """_[^_\n]+_""",            // italic
                """__[^_\n]+__""",          // underline
                """~[^~\n]+~""",            // strikethrough
                """\|\|[^|]+\|\|""",        // spoiler
                """`[^`\n]+`""",            // inline code
                """```[\s\S]*?```""",       // code block
                """\[[^\]]+\]\([^)]+\)""",  // links
                """^>\s*.*$"""              // quotes
            ).any { pattern ->
                Pattern.compile(pattern, Pattern.MULTILINE).matcher(text).find()
            }
            
            // Check that it doesn't have unbalanced markup
            hasMarkdownPatterns && !hasUnbalancedMarkup(text)
            
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Convert a plain text message to safe MarkdownV2
     * Useful for ensuring any text can be sent with MarkdownV2 parse mode
     */
    fun textToSafeMarkdown(text: String): String {
        return escapeMarkdownV2(text)
    }
    
    /**
     * Create a formatted message with safe escaping
     * Useful for creating notifications with mixed formatted and plain content
     */
    fun createFormattedMessage(
        header: String? = null,
        content: String,
        footer: String? = null,
        headerFormat: MessageFormat = MessageFormat.BOLD,
        escapeContent: Boolean = true
    ): String {
        val parts = mutableListOf<String>()
        
        // Add header with formatting
        header?.let { h ->
            val formattedHeader = when (headerFormat) {
                MessageFormat.BOLD -> "*${escapeForFormatting(h)}*"
                MessageFormat.ITALIC -> "_${escapeForFormatting(h)}_"
                MessageFormat.UNDERLINE -> "__${escapeForFormatting(h)}__"
                MessageFormat.CODE -> "`${escapeCodeContent(h)}`"
                MessageFormat.NONE -> if (escapeContent) escapeMarkdownV2(h) else h
            }
            parts.add(formattedHeader)
        }
        
        // Add content
        val processedContent = if (escapeContent) escapeMarkdownV2(content) else content
        parts.add(processedContent)
        
        // Add footer
        footer?.let { f ->
            val processedFooter = if (escapeContent) escapeMarkdownV2(f) else f
            parts.add(processedFooter)
        }
        
        return parts.joinToString("\n\n")
    }
    
    enum class MessageFormat {
        NONE, BOLD, ITALIC, UNDERLINE, CODE
    }
}

// Extension functions for easier use
fun String.toTelegramMarkdown(): String = TelegramMarkdownConverter.markdownToTelegram(this)
fun String.escapeMarkdownV2(): String = TelegramMarkdownConverter.escapeMarkdownV2(this)
fun String.isValidTelegramMarkdown(): Boolean = TelegramMarkdownConverter.isValidTelegramMarkdown(this)
