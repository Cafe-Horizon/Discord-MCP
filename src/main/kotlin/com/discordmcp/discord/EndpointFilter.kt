package com.discordmcp.discord

import com.discordmcp.config.AppConfig

/**
 * Narrows the full Discord REST operation set down to what should actually be exposed as MCP
 * tools, based on [AppConfig]'s tool-surface controls (DISCORD_MCP_TOOL_CATEGORIES,
 * DISCORD_MCP_INCLUDE_TOOLS, DISCORD_MCP_EXCLUDE_TOOLS, DISCORD_MCP_READONLY). Applies to both
 * the direct per-endpoint registration mode ([RestToolRegistrar]) and the lazy proxy mode
 * ([LazyToolRegistrar]) — the latter only searches/calls within the filtered subset too.
 */
object EndpointFilter {

    fun apply(endpoints: List<EndpointSpec>, config: AppConfig): List<EndpointSpec> {
        val categories = config.toolCategories
        val include = config.includeToolsPattern
        val exclude = config.excludeToolsPattern
        val readOnly = config.readOnly

        return endpoints.filter { spec ->
            (categories == null || spec.category.lowercase() in categories) &&
                (include == null || include.containsMatchIn(spec.toolName)) &&
                (exclude == null || !exclude.containsMatchIn(spec.toolName)) &&
                (!readOnly || spec.method == "GET")
        }
    }
}
