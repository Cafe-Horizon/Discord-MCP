package com.discordmcp.discord

import com.discordmcp.config.AppConfig
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

object InteractionTools {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    fun registerAll(server: Server, client: DiscordHttpClient, config: AppConfig) {
        // 1. Tool to send interaction reply (callback)
        server.addTool(
            name = "discord_interaction_reply",
            description = "Respond to a Discord Interaction (button click, slash command, modal submission). Sends callback to Discord API.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("interaction_id") {
                        put("type", "string")
                        put("description", "ID of the interaction.")
                    }
                    putJsonObject("interaction_token") {
                        put("type", "string")
                        put("description", "Interaction token for authorization.")
                    }
                    putJsonObject("type") {
                        put("type", "integer")
                        put("description", "Callback type: 4=ChannelMessageWithSource (default), 5=DeferredChannelMessageWithSource, 7=UpdateMessage, 9=Modal.")
                    }
                    putJsonObject("content") {
                        put("type", "string")
                        put("description", "Message text content.")
                    }
                    putJsonObject("ephemeral") {
                        put("type", "boolean")
                        put("description", "If true, message is visible only to the user who triggered the interaction.")
                    }
                    putJsonObject("components") {
                        put("type", "array")
                        put("description", "Optional list of ActionRow message components.")
                    }
                },
                required = listOf("interaction_id", "interaction_token"),
            ),
            toolAnnotations = ToolAnnotations(readOnlyHint = false, destructiveHint = false),
        ) { request ->
            val args = request.arguments ?: emptyMap()
            val interactionId = args["interaction_id"]?.jsonPrimitive?.contentOrNull
                ?: return@addTool CallToolResult(listOf(TextContent("Missing 'interaction_id'")), isError = true)
            val interactionToken = args["interaction_token"]?.jsonPrimitive?.contentOrNull
                ?: return@addTool CallToolResult(listOf(TextContent("Missing 'interaction_token'")), isError = true)

            val callbackType = (args["type"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 4
            val content = args["content"]?.jsonPrimitive?.contentOrNull
            val ephemeral = (args["ephemeral"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() ?: false
            val components = args["components"] as? JsonArray

            val flags = if (ephemeral) 64 else 0

            val dataObject = buildJsonObject {
                if (content != null) put("content", content)
                if (flags != 0) put("flags", flags)
                if (components != null) put("components", components)
            }

            val body = buildJsonObject {
                put("type", callbackType)
                put("data", dataObject)
            }

            // Find endpoint spec for create_interaction_response at invocation time
            val spec = EndpointRegistry.endpoints.find { it.operationId == "create_interaction_response" }
                ?: return@addTool CallToolResult(listOf(TextContent("create_interaction_response endpoint not found")), isError = true)

            val pathArgs = buildJsonObject {
                put("interaction_id", interactionId)
                put("interaction_token", interactionToken)
            }

            EndpointExecutor.call(
                spec = spec,
                client = client,
                config = config,
                pathArgs = pathArgs,
                queryArgs = buildJsonObject {},
                bodyObject = body,
                files = null,
                auditLogReason = null,
                authOverride = null,
            )
        }

        // 2. Helper tool to build a Button Component JSON
        server.addTool(
            name = "discord_build_component_button",
            description = "Build a JSON object for a Discord Message Button Component (type=2).",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("label") {
                        put("type", "string")
                        put("description", "Text label displayed on the button.")
                    }
                    putJsonObject("custom_id") {
                        put("type", "string")
                        put("description", "Developer-defined identifier (max 100 chars).")
                    }
                    putJsonObject("style") {
                        put("type", "integer")
                        put("description", "Button style: 1=Primary(Blue), 2=Secondary(Grey), 3=Success(Green), 4=Danger(Red), 5=Link.")
                    }
                    putJsonObject("url") {
                        put("type", "string")
                        put("description", "URL for Link buttons (style 5).")
                    }
                    putJsonObject("disabled") {
                        put("type", "boolean")
                        put("description", "If true, button is disabled.")
                    }
                },
                required = listOf("label"),
            ),
            toolAnnotations = ToolAnnotations(readOnlyHint = true),
        ) { request ->
            val args = request.arguments ?: emptyMap()
            val label = args["label"]?.jsonPrimitive?.contentOrNull ?: ""
            val customId = args["custom_id"]?.jsonPrimitive?.contentOrNull ?: "btn_${System.currentTimeMillis()}"
            val style = (args["style"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 1
            val url = args["url"]?.jsonPrimitive?.contentOrNull
            val disabled = (args["disabled"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() ?: false

            val buttonObj = buildJsonObject {
                put("type", 2)
                put("label", label)
                put("style", style)
                if (style == 5 && url != null) {
                    put("url", url)
                } else {
                    put("custom_id", customId)
                }
                if (disabled) put("disabled", true)
            }

            CallToolResult(listOf(TextContent(json.encodeToString(buttonObj))), isError = false)
        }
    }
}
