package com.discordmcp.discord

/**
 * Encapsulates the outcome of a Discord REST API request.
 */
sealed interface DiscordResult {
    data class Success(
        val status: Int,
        val statusText: String,
        val body: String,
        val rateLimitedRetries: Int = 0,
    ) : DiscordResult

    data class Error(
        val status: Int,
        val statusText: String,
        val message: String,
    ) : DiscordResult
}
