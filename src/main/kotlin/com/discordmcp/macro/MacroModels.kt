package com.discordmcp.macro

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class MacroParam(
    val type: String = "string",
    val description: String = "",
    val default: JsonElement? = null,
    val required: Boolean = false,
)

@Serializable
data class MacroStep(
    val stepId: String,
    val tool: String? = null,
    val args: Map<String, JsonElement> = emptyMap(),
    val action: String? = null, // e.g. "filter_array", "extract_field"
    val input: String? = null,
    val condition: String? = null,
    val outputVar: String? = null,
)

@Serializable
data class MacroDefinition(
    val name: String,
    val description: String,
    val parameters: Map<String, MacroParam> = emptyMap(),
    val steps: List<MacroStep> = emptyList(),
    val profiles: List<String>? = null,
    val defaultProfile: String? = null,
)

@Serializable
data class MacroExecutionResult(
    val success: Boolean,
    val macroName: String,
    val output: JsonElement? = null,
    val error: String? = null,
    val stepResults: Map<String, JsonElement> = emptyMap(),
)
