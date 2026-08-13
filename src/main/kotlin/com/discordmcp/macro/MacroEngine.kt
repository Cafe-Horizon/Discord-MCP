package com.discordmcp.macro

import com.discordmcp.discord.DiscordHttpClient
import com.discordmcp.discord.EndpointExecutor
import com.discordmcp.discord.EndpointRegistry
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

class MacroEngine(
    private val storageFile: File = File("data/macros.json"),
    private val json: Json = Json { prettyPrint = true; ignoreUnknownKeys = true },
) {
    private val mutex = Mutex()
    private val macros = mutableMapOf<String, MacroDefinition>()

    init {
        loadMacros()
    }

    private fun loadMacros() {
        if (!storageFile.exists()) return
        runCatching {
            val content = storageFile.readText()
            if (content.isNotBlank()) {
                val list = json.decodeFromString<List<MacroDefinition>>(content)
                list.forEach { macros[it.name] = it }
                System.err.println("[discord-mcp] Loaded ${macros.size} dynamic macros from ${storageFile.path}")
            }
        }.onFailure {
            System.err.println("[discord-mcp] Failed to load macros: ${it.message}")
        }
    }

    private fun saveMacros() {
        runCatching {
            storageFile.parentFile?.mkdirs()
            val content = json.encodeToString(macros.values.toList())
            storageFile.writeText(content)
        }.onFailure {
            System.err.println("[discord-mcp] Failed to save macros: ${it.message}")
        }
    }

    suspend fun registerMacro(definition: MacroDefinition): Boolean = mutex.withLock {
        macros[definition.name] = definition
        saveMacros()
        true
    }

    suspend fun unregisterMacro(name: String): Boolean = mutex.withLock {
        val removed = macros.remove(name) != null
        if (removed) {
            saveMacros()
        }
        removed
    }

    fun getMacro(name: String): MacroDefinition? = macros[name]

    fun listMacros(profile: String? = null): List<MacroDefinition> {
        val list = macros.values.toList()
        if (profile.isNullOrBlank()) return list
        return list.filter { macro ->
            macro.profiles.isNullOrEmpty() || macro.profiles.contains(profile)
        }
    }

    companion object {
        private const val MAX_STEPS = 100
        private val placeholderRegex = Regex("""\{\{\s*([a-zA-Z0-9_\-\.]+)\s*\}\}""")
    }

    suspend fun executeMacro(
        macroName: String,
        arguments: Map<String, JsonElement>,
        restClient: DiscordHttpClient,
        toolExecutor: suspend (toolName: String, args: Map<String, JsonElement>) -> JsonElement,
    ): MacroExecutionResult {
        val macro = getMacro(macroName)
            ?: return MacroExecutionResult(false, macroName, error = "Macro '$macroName' not found")

        if (macro.steps.size > MAX_STEPS) {
            return MacroExecutionResult(
                success = false,
                macroName = macroName,
                error = "Macro '$macroName' exceeds maximum allowed steps limit ($MAX_STEPS steps)",
            )
        }

        val activeProfile = (arguments["profile"] as? JsonPrimitive)?.contentOrNull ?: macro.defaultProfile

        if (!macro.profiles.isNullOrEmpty()) {
            if (activeProfile == null || !macro.profiles.contains(activeProfile)) {
                return MacroExecutionResult(
                    success = false,
                    macroName = macroName,
                    error = "Macro '$macroName' is restricted to profiles ${macro.profiles}, but active profile is '$activeProfile'",
                )
            }
        }

        val context = mutableMapOf<String, JsonElement>()

        // 1. Set parameters in context
        macro.parameters.forEach { (key, param) ->
            val value = arguments[key] ?: param.default ?: JsonPrimitive("")
            context[key] = value
        }

        val stepResults = mutableMapOf<String, JsonElement>()
        var lastResult: JsonElement? = null

        // 2. Execute steps
        for (step in macro.steps) {
            try {
                val stepResult = executeStep(step, context, restClient, activeProfile, toolExecutor)
                stepResults[step.stepId] = stepResult
                context["${step.stepId}.output"] = stepResult
                step.outputVar?.let { context[it] = stepResult }
                lastResult = stepResult
            } catch (e: Exception) {
                return MacroExecutionResult(
                    success = false,
                    macroName = macroName,
                    error = "Error in step '${step.stepId}': ${e.message}",
                    stepResults = stepResults,
                )
            }
        }

        return MacroExecutionResult(
            success = true,
            macroName = macroName,
            output = lastResult ?: JsonObject(emptyMap()),
            stepResults = stepResults,
        )
    }

    private suspend fun executeStep(
        step: MacroStep,
        context: Map<String, JsonElement>,
        restClient: DiscordHttpClient,
        activeProfile: String?,
        toolExecutor: suspend (toolName: String, args: Map<String, JsonElement>) -> JsonElement,
    ): JsonElement {
        if (step.tool != null) {
            // Evaluate placeholders in tool arguments
            val resolvedArgs = step.args.mapValues { (_, value) ->
                evaluateJsonElement(value, context)
            }.toMutableMap()

            if (!resolvedArgs.containsKey("profile") && activeProfile != null) {
                resolvedArgs["profile"] = JsonPrimitive(activeProfile)
            }

            return toolExecutor(step.tool, resolvedArgs)
        }

        if (step.action != null) {
            return when (step.action) {
                "filter_array" -> filterArray(step, context)
                "extract_field" -> extractField(step, context)
                else -> throw IllegalArgumentException("Unknown step action: ${step.action}")
            }
        }

        throw IllegalArgumentException("Step '${step.stepId}' has neither 'tool' nor 'action'")
    }

    private fun filterArray(step: MacroStep, context: Map<String, JsonElement>): JsonElement {
        val inputStr = step.input ?: throw IllegalArgumentException("filter_array requires 'input'")
        val array = parseToJsonArray(inputStr, context)

        val condition = step.condition ?: return array
        val filtered = array.filter { item ->
            evaluateCondition(condition, item)
        }
        return JsonArray(filtered)
    }

    private fun extractField(step: MacroStep, context: Map<String, JsonElement>): JsonElement {
        val inputStr = step.input ?: throw IllegalArgumentException("extract_field requires 'input'")
        val fieldName = step.condition ?: throw IllegalArgumentException("extract_field requires field name in 'condition'")
        val array = parseToJsonArray(inputStr, context)

        val extracted = array.mapNotNull { item ->
            if (item is JsonObject) {
                item[fieldName]
            } else null
        }
        return JsonArray(extracted)
    }

    private fun parseToJsonArray(inputStr: String, context: Map<String, JsonElement>): JsonArray {
        val trimmed = inputStr.trim()
        if (trimmed.startsWith("{{") && trimmed.endsWith("}}")) {
            val expr = trimmed.substring(2, trimmed.length - 2).trim()
            val resolved = resolveVariable(expr, context)
                ?: throw IllegalArgumentException("Variable '{{$expr}}' not found in context")
            if (resolved is JsonArray) return resolved
            if (resolved is JsonPrimitive && resolved.isString) {
                return runCatching { json.parseToJsonElement(resolved.content).jsonArray }
                    .getOrElse { throw IllegalArgumentException("Variable '{{$expr}}' content is not a valid JSON Array") }
            }
            throw IllegalArgumentException("Variable '{{$expr}}' resolved to non-array type: ${resolved::class.simpleName}")
        }

        val evaluatedStr = evaluateStringTemplate(inputStr, context)
        return runCatching { json.parseToJsonElement(evaluatedStr).jsonArray }
            .getOrElse { throw IllegalArgumentException("Failed to parse input as JsonArray: '$inputStr'") }
    }

    private fun evaluateCondition(condition: String, item: JsonElement): Boolean {
        val parts = condition.split("==").map { it.trim() }
        if (parts.size != 2) return true

        val leftPath = parts[0].removePrefix("item.").removePrefix("item")
        val rightVal = parts[1].removeSurrounding("'", "'").removeSurrounding("\"", "\"")

        val actualVal = getValueByPath(item, leftPath)?.jsonPrimitive?.contentOrNull
        return actualVal == rightVal
    }

    private fun resolveVariable(expression: String, context: Map<String, JsonElement>): JsonElement? {
        val trimmed = expression.trim()
        if (context.containsKey(trimmed)) {
            return context[trimmed]
        }
        var dotIndex = trimmed.indexOf('.')
        while (dotIndex != -1) {
            val rootKey = trimmed.substring(0, dotIndex)
            val subPath = trimmed.substring(dotIndex + 1)
            val rootElem = context[rootKey]
            if (rootElem != null) {
                val found = getValueByPath(rootElem, subPath)
                if (found != null) return found
            }
            dotIndex = trimmed.indexOf('.', dotIndex + 1)
        }
        return null
    }

    private fun getValueByPath(element: JsonElement, path: String): JsonElement? {
        if (path.isEmpty()) return element
        var current: JsonElement? = element
        val segments = path.split(".")
        for (segment in segments) {
            val seg = segment.trim()
            if (seg.isEmpty()) continue
            current = when (current) {
                is JsonObject -> current[seg]
                is JsonArray -> {
                    val index = seg.toIntOrNull()
                    if (index != null && index >= 0 && index < current.size) {
                        current[index]
                    } else null
                }
                else -> return null
            }
        }
        return current
    }

    private fun evaluateJsonElement(element: JsonElement, context: Map<String, JsonElement>): JsonElement {
        return when (element) {
            is JsonPrimitive -> {
                if (element.isString) {
                    val str = element.content
                    if (str.startsWith("{{") && str.endsWith("}}") && str.count { it == '{' } == 2 && str.count { it == '}' } == 2) {
                        val varExpr = str.substring(2, str.length - 2).trim()
                        resolveVariable(varExpr, context) ?: element
                    } else {
                        JsonPrimitive(evaluateStringTemplate(str, context))
                    }
                } else element
            }
            is JsonObject -> JsonObject(element.mapValues { evaluateJsonElement(it.value, context) })
            is JsonArray -> JsonArray(element.map { evaluateJsonElement(it, context) })
        }
    }

    private fun evaluateStringTemplate(template: String, context: Map<String, JsonElement>): String {
        return placeholderRegex.replace(template) { match ->
            val expr = match.groupValues[1]
            val resolved = resolveVariable(expr, context)
            if (resolved != null) {
                when (resolved) {
                    is JsonPrimitive -> resolved.contentOrNull ?: resolved.toString()
                    else -> resolved.toString()
                }
            } else {
                match.value
            }
        }
    }
}
