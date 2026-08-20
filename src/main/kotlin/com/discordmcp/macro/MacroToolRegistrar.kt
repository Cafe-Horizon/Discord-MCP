package com.discordmcp.macro

import com.discordmcp.config.AppConfig
import com.discordmcp.discord.DiscordHttpClient
import com.discordmcp.discord.EndpointExecutor
import com.discordmcp.discord.EndpointRegistry
import com.discordmcp.discord.EndpointSpec
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

object MacroToolRegistrar {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    fun registerAll(
        server: Server,
        macroEngine: MacroEngine,
        client: DiscordHttpClient,
        config: AppConfig,
        filteredEndpoints: List<EndpointSpec> = EndpointRegistry.endpoints,
        onToolsChanged: suspend () -> Unit = {},
    ) {
        // 1. Tool to register new macros
        server.addTool(
            name = "discord_register_macro",
            description = "Register a new dynamic AI macro workflow. Takes a JSON macro definition.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("macro") {
                        put("type", "object")
                        put("description", "Macro definition JSON containing name, description, parameters, and steps.")
                    }
                },
                required = listOf("macro"),
            ),
            toolAnnotations = ToolAnnotations(readOnlyHint = false, destructiveHint = false),
        ) { request ->
            val args = request.arguments ?: emptyMap()
            val macroObj = args["macro"] as? JsonObject
                ?: return@addTool CallToolResult(
                    content = listOf(TextContent("Missing required 'macro' JSON object.")),
                    isError = true,
                )

            try {
                val definition = json.decodeFromJsonElement<MacroDefinition>(macroObj)
                macroEngine.registerMacro(definition)
                onToolsChanged()
                CallToolResult(
                    content = listOf(TextContent("Successfully registered macro '${definition.name}'. Tools list updated.")),
                    isError = false,
                )
            } catch (e: Exception) {
                CallToolResult(
                    content = listOf(TextContent("Failed to register macro: ${e.message}")),
                    isError = true,
                )
            }
        }

        // 2. Tool to unregister macros
        server.addTool(
            name = "discord_unregister_macro",
            description = "Delete/Unregister a dynamic AI macro by name.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("name") {
                        put("type", "string")
                        put("description", "Name of the macro to unregister.")
                    }
                },
                required = listOf("name"),
            ),
            toolAnnotations = ToolAnnotations(readOnlyHint = false, destructiveHint = true),
        ) { request ->
            val name = request.arguments?.get("name")?.jsonPrimitive?.contentOrNull
                ?: return@addTool CallToolResult(
                    content = listOf(TextContent("Missing 'name' argument.")),
                    isError = true,
                )

            val removed = macroEngine.unregisterMacro(name)
            if (removed) {
                onToolsChanged()
                CallToolResult(
                    content = listOf(TextContent("Successfully unregistered macro '$name'.")),
                    isError = false,
                )
            } else {
                CallToolResult(
                    content = listOf(TextContent("Macro '$name' not found.")),
                    isError = true,
                )
            }
        }

        // 3. Tool to list all registered macros
        server.addTool(
            name = "discord_list_macros",
            description = "List registered dynamic AI macros and their definitions (optionally filtered by profile).",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("profile") {
                        put("type", "string")
                        put("description", "Optional bot profile name to filter accessible macros.")
                    }
                },
                required = emptyList(),
            ),
            toolAnnotations = ToolAnnotations(readOnlyHint = true),
        ) { request ->
            val profile = request.arguments?.get("profile")?.jsonPrimitive?.contentOrNull
            val list = macroEngine.listMacros(profile)
            val jsonText = json.encodeToString(list)
            CallToolResult(
                content = listOf(TextContent(jsonText)),
                isError = false,
            )
        }

        // 4. Dynamically attach macro tools (discord_macro_<name>)
        val availableEndpointsMap = filteredEndpoints.associateBy { it.toolName }

        for (macro in macroEngine.listMacros()) {
            val toolName = "discord_macro_${macro.name}"
            val schema = buildJsonObject {
                for ((paramName, param) in macro.parameters) {
                    putJsonObject(paramName) {
                        put("type", param.type)
                        put("description", param.description)
                    }
                }
                if (!macro.parameters.containsKey("profile")) {
                    putJsonObject("profile") {
                        put("type", "string")
                        put("description", "Optional bot profile override for executing this macro.")
                    }
                }
            }
            val requiredParams = macro.parameters.filter { it.value.required }.keys.toList()

            server.addTool(
                name = toolName,
                description = "[AI Macro] ${macro.description}",
                inputSchema = ToolSchema(properties = schema, required = requiredParams),
                toolAnnotations = ToolAnnotations(openWorldHint = true),
            ) { request ->
                val args = request.arguments ?: emptyMap()
                val result = macroEngine.executeMacro(
                    macroName = macro.name,
                    arguments = args,
                    restClient = client,
                    toolExecutor = { subToolName, subArgs ->
                        val spec = availableEndpointsMap[subToolName]
                        if (spec != null) {
                            val argsObj = JsonObject(subArgs)
                            val callResult = EndpointExecutor.call(
                                spec = spec,
                                client = client,
                                config = config,
                                pathArgs = argsObj,
                                queryArgs = argsObj,
                                bodyObject = subArgs["body"] as? JsonObject,
                                files = subArgs["files"] as? JsonArray,
                                auditLogReason = subArgs["auditLogReason"]?.jsonPrimitive?.contentOrNull,
                                authOverride = subArgs["authOverride"]?.jsonPrimitive?.contentOrNull,
                            )
                            val text = callResult.content.filterIsInstance<TextContent>().firstOrNull()?.text ?: ""
                            runCatching { json.parseToJsonElement(text) }.getOrElse { json.parseToJsonElement(json.encodeToString(text)) }
                        } else {
                            json.parseToJsonElement("""{"error": "Unknown or disabled tool '$subToolName'"}""")
                        }
                    },
                )

                if (result.success) {
                    CallToolResult(
                        content = listOf(TextContent(json.encodeToString(result.output))),
                        isError = false,
                    )
                } else {
                    CallToolResult(
                        content = listOf(TextContent("Macro execution error: ${result.error}")),
                        isError = true,
                    )
                }
            }
        }
    }
}
