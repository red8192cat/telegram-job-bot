package com.jobbot.shared.localization

import io.github.oshai.kotlinlogging.KotlinLogging
import java.text.MessageFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap

// Enhanced localization utility that supports multiline templates AND admin language
object Localization {
    private val logger = KotlinLogging.logger("Localization")
    private val bundles = ConcurrentHashMap<String, ResourceBundle>()
    
    /**
     * Get a localized message with parameter substitution
     * Supports multiline text with \n and proper escaping
     */
    fun getMessage(language: String, key: String, vararg args: Any): String {
        val bundle = getBundle(language)
        
        return try {
            val rawMessage = bundle.getString(key)
            val processedMessage = processMultilineText(rawMessage)
            
            if (args.isNotEmpty()) {
                MessageFormat.format(processedMessage, *args)
            } else {
                processedMessage
            }
        } catch (e: MissingResourceException) {
            logger.warn { "Missing translation key: $key for language: $language" }
            "[$key]" // return key in brackets if translation not found
        } catch (e: Exception) {
            logger.error(e) { "Error processing message key: $key" }
            "[$key]"
        }
    }
    
    /**
     * Get a template (multiline text) with parameter substitution
     * This is an alias for getMessage but semantically clearer for templates
     */
    fun getTemplate(language: String, templateKey: String, vararg args: Any): String {
        return getMessage(language, templateKey, *args)
    }
    
    /**
     * NEW: Get admin message with parameter substitution
     * Always uses the admin language bundle
     */
    fun getAdminMessage(key: String, vararg args: Any): String {
        return getMessage("admin", key, *args)
    }
    
    /**
     * NEW: Get admin template (alias for getAdminMessage)
     */
    fun getAdminTemplate(templateKey: String, vararg args: Any): String {
        return getAdminMessage(templateKey, *args)
    }
    
    /**
     * Check if a key exists in the bundle
     */
    fun hasKey(language: String, key: String): Boolean {
        val bundle = getBundle(language)
        return try {
            bundle.getString(key)
            true
        } catch (e: MissingResourceException) {
            false
        }
    }
    
    /**
     * Check if an admin key exists
     */
    fun hasAdminKey(key: String): Boolean {
        return hasKey("admin", key)
    }
    
    /**
     * Get available languages (based on available resource bundles)
     */
    fun getAvailableLanguages(): List<String> {
        val availableLanguages = mutableListOf<String>()
        
        // Check for common language files
        val commonLanguages = listOf("en", "ru", "es", "fr", "de", "zh", "admin")
        
        for (lang in commonLanguages) {
            try {
                ResourceBundle.getBundle("messages_$lang")
                availableLanguages.add(lang)
            } catch (e: MissingResourceException) {
                // Language not available
            }
        }
        
        return availableLanguages
    }
    
    private fun getBundle(language: String): ResourceBundle {
        return bundles.getOrPut(language) {
            try {
                logger.debug { "Loading resource bundle for language: $language" }
                ResourceBundle.getBundle("messages_$language")
            } catch (e: MissingResourceException) {
                logger.warn { "Resource bundle not found for language: $language, falling back to English" }
                
                // Special case: if admin bundle is missing, create a fallback that throws clear errors
                if (language == "admin") {
                    logger.error { "Admin language bundle (messages_admin.properties) is missing! This is required for admin interface." }
                    throw IllegalStateException("Admin language bundle is required but not found: messages_admin.properties")
                }
                
                ResourceBundle.getBundle("messages_en") // fallback to English
            }
        }
    }
    
    /**
     * Process multiline text from properties files
     * Handles:
     * - \n for line breaks
     * - \\ for line continuation (removes the backslash and newline)
     * - Proper whitespace handling
     */
    private fun processMultilineText(rawText: String): String {
        return rawText
            // Handle explicit line breaks
            .replace("\\n", "\n")
            // Handle line continuation (backslash at end of line)
            .replace("\\\n", "")
            .replace("\\\\", "\\") // Handle escaped backslashes
            // Clean up any extra whitespace but preserve intentional formatting
            .lines()
            .joinToString("\n") { line ->
                // Only trim trailing whitespace, preserve leading spaces for formatting
                line.trimEnd()
            }
    }
    
    /**
     * Reload all cached bundles (useful for development/testing)
     */
    fun reloadBundles() {
        logger.info { "Reloading all language bundles" }
        bundles.clear()
    }
    
    /**
     * Get formatted text with safe parameter substitution
     * Prevents issues with special characters in parameters
     */
    fun getSafeMessage(language: String, key: String, vararg args: Any): String {
        return try {
            // Escape special characters in parameters to prevent MessageFormat issues
            val safeArgs = args.map { arg ->
                when (arg) {
                    is String -> arg.replace("'", "''") // Escape single quotes for MessageFormat
                    else -> arg
                }
            }.toTypedArray()
            
            getMessage(language, key, *safeArgs)
        } catch (e: Exception) {
            logger.error(e) { "Error in safe message formatting for key: $key" }
            getMessage(language, key) // Return without parameters if formatting fails
        }
    }
    
    /**
     * Get safe admin message with parameter substitution
     */
    fun getSafeAdminMessage(key: String, vararg args: Any): String {
        return getSafeMessage("admin", key, *args)
    }
}
