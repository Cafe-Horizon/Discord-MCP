package com.discordmcp

import com.discordmcp.macro.MacroDefinition
import com.discordmcp.macro.MacroEngine
import com.discordmcp.macro.MacroParam
import com.discordmcp.macro.MacroStep
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MacroEngineTest {


    @Test
    fun testMacroRegistrationAndExecution() = runBlocking {
        val testFile = File("build/test_macros.json")
        testFile.delete()

        val engine = MacroEngine(storageFile = testFile)

        val macro = MacroDefinition(
            name = "test_macro",
            description = "Test macro description",
            steps = listOf(
                MacroStep(
                    stepId = "step1",
                    action = "filter_array",
                    input = "[{\"id\": \"1\"}, {\"id\": \"2\"}]",
                    condition = "item.id == '1'",
                ),
            ),
        )

        val registered = engine.registerMacro(macro)
        assertTrue(registered)
        assertEquals(1, engine.listMacros().size)

        val retrieved = engine.getMacro("test_macro")
        assertEquals("Test macro description", retrieved?.description)

        val unregistered = engine.unregisterMacro("test_macro")
        assertTrue(unregistered)
        assertEquals(0, engine.listMacros().size)

        testFile.delete()
    }

    @Test
    fun testProfileScopingAndDefaultProfile() = runBlocking {
        val testFile = File("build/test_profile_macros.json")
        testFile.delete()

        val engine = MacroEngine(storageFile = testFile)

        val globalMacro = MacroDefinition(name = "global_macro", description = "Global")
        val adminMacro = MacroDefinition(
            name = "admin_macro",
            description = "Admin only",
            profiles = listOf("admin"),
            defaultProfile = "admin",
            steps = listOf(
                MacroStep(
                    stepId = "s1",
                    tool = "test_tool",
                    args = emptyMap(),
                ),
            ),
        )

        engine.registerMacro(globalMacro)
        engine.registerMacro(adminMacro)

        assertEquals(2, engine.listMacros().size)
        assertEquals(2, engine.listMacros("admin").size)
        assertEquals(1, engine.listMacros("user").size)
        assertEquals("global_macro", engine.listMacros("user").first().name)

        // Execution profile restriction check
        var executedToolProfile: String? = null
        val mockClient = com.discordmcp.discord.DiscordHttpClient(com.discordmcp.config.AppConfig())

        // Execution with invalid profile
        val failResult = engine.executeMacro(
            macroName = "admin_macro",
            arguments = mapOf("profile" to kotlinx.serialization.json.JsonPrimitive("user")),
            restClient = mockClient,
            toolExecutor = { _, _ -> kotlinx.serialization.json.JsonObject(emptyMap()) },
        )
        kotlin.test.assertFalse(failResult.success)

        // Execution with valid profile / fallback to defaultProfile
        val passResult = engine.executeMacro(
            macroName = "admin_macro",
            arguments = emptyMap(),
            restClient = mockClient,
            toolExecutor = { _, args ->
                executedToolProfile = (args["profile"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                kotlinx.serialization.json.JsonObject(emptyMap())
            },
        )
        assertTrue(passResult.success)
        assertEquals("admin", executedToolProfile)

        testFile.delete()
    }

    @Test
    fun testDotPathVariableResolution() = runBlocking {
        val testFile = File("build/test_dotpath_macros.json")
        testFile.delete()
        val engine = MacroEngine(storageFile = testFile)

        val macro = MacroDefinition(
            name = "dotpath_macro",
            description = "Test dotpath resolution",
            steps = listOf(
                MacroStep(
                    stepId = "s1",
                    action = "filter_array",
                    input = "[{\"user\": {\"id\": \"100\", \"name\": \"Alice\"}}]",
                ),
                MacroStep(
                    stepId = "s2",
                    tool = "echo_tool",
                    args = mapOf(
                        "userId" to kotlinx.serialization.json.JsonPrimitive("{{s1.output.0.user.id}}"),
                        "userName" to kotlinx.serialization.json.JsonPrimitive("{{s1.output.0.user.name}}"),
                    ),
                ),
            ),
        )
        engine.registerMacro(macro)

        var passedArgs: Map<String, kotlinx.serialization.json.JsonElement>? = null
        val mockClient = com.discordmcp.discord.DiscordHttpClient(com.discordmcp.config.AppConfig())

        val result = engine.executeMacro(
            macroName = "dotpath_macro",
            arguments = emptyMap(),
            restClient = mockClient,
            toolExecutor = { _, args ->
                passedArgs = args
                kotlinx.serialization.json.JsonObject(args)
            },
        )

        assertTrue(result.success)
        assertEquals("100", (passedArgs?.get("userId") as? kotlinx.serialization.json.JsonPrimitive)?.content)
        assertEquals("Alice", (passedArgs?.get("userName") as? kotlinx.serialization.json.JsonPrimitive)?.content)

        testFile.delete()
    }

    @Test
    fun testStrictErrorHandling() = runBlocking {
        val testFile = File("build/test_error_macros.json")
        testFile.delete()
        val engine = MacroEngine(storageFile = testFile)

        val macro = MacroDefinition(
            name = "error_macro",
            description = "Test error handling",
            steps = listOf(
                MacroStep(
                    stepId = "s1",
                    action = "filter_array",
                    input = "invalid json array content",
                ),
            ),
        )
        engine.registerMacro(macro)

        val mockClient = com.discordmcp.discord.DiscordHttpClient(com.discordmcp.config.AppConfig())
        val result = engine.executeMacro(
            macroName = "error_macro",
            arguments = emptyMap(),
            restClient = mockClient,
            toolExecutor = { _, _ -> kotlinx.serialization.json.JsonObject(emptyMap()) },
        )

        kotlin.test.assertFalse(result.success)
        assertTrue(result.error?.contains("Failed to parse input as JsonArray") == true)

        testFile.delete()
    }

    @Test
    fun testMaxStepsExceeded() = runBlocking {
        val testFile = File("build/test_maxsteps_macros.json")
        testFile.delete()
        val engine = MacroEngine(storageFile = testFile)

        val steps = (1..101).map { i ->
            MacroStep(stepId = "step_$i", action = "filter_array", input = "[]")
        }
        val macro = MacroDefinition(
            name = "too_many_steps",
            description = "101 steps",
            steps = steps,
        )
        engine.registerMacro(macro)

        val mockClient = com.discordmcp.discord.DiscordHttpClient(com.discordmcp.config.AppConfig())
        val result = engine.executeMacro(
            macroName = "too_many_steps",
            arguments = emptyMap(),
            restClient = mockClient,
            toolExecutor = { _, _ -> kotlinx.serialization.json.JsonObject(emptyMap()) },
        )

        kotlin.test.assertFalse(result.success)
        assertTrue(result.error?.contains("exceeds maximum allowed steps limit") == true)

        testFile.delete()
    }

    @Test
    fun testMacroFileSerializationStructure() = runBlocking {
        val testFile = File("build/test_serialization_structure.json")
        testFile.delete()

        val engine = MacroEngine(storageFile = testFile)

        val macro = MacroDefinition(
            name = "complex_macro",
            description = "Tests JSON structure on disk",
            parameters = mapOf(
                "strParam" to com.discordmcp.macro.MacroParam(type = "string", default = kotlinx.serialization.json.JsonPrimitive("hello")),
                "intParam" to com.discordmcp.macro.MacroParam(type = "number", default = kotlinx.serialization.json.JsonPrimitive(42)),
                "boolParam" to com.discordmcp.macro.MacroParam(type = "boolean", default = kotlinx.serialization.json.JsonPrimitive(true)),
            ),
            steps = listOf(
                MacroStep(
                    stepId = "step1",
                    tool = "discord_create_message",
                    args = mapOf(
                        "content" to JsonPrimitive("text"),
                        "num" to JsonPrimitive(123),
                        "nestedObj" to buildJsonObject {
                            put("key", "value")
                        },
                        "nestedArr" to buildJsonArray {
                            add(JsonPrimitive(1))
                            add(JsonPrimitive(2))
                        },
                    ),
                ),
            ),
            profiles = listOf("profileA", "profileB"),
            defaultProfile = "profileA",
        )

        engine.registerMacro(macro)

        // 1. Verify file exists and read raw content
        assertTrue(testFile.exists(), "File should be created on disk")
        val rawContent = testFile.readText()

        // 2. Parse raw content as JSON Element to verify AST structure
        val jsonParser = Json { ignoreUnknownKeys = true }
        val rootElement = jsonParser.parseToJsonElement(rawContent)

        // Root MUST be a JsonArray, NOT a JsonPrimitive (string)
        assertTrue(rootElement is JsonArray, "Root element in macros.json must be a JsonArray, but was ${rootElement::class.simpleName}")

        val firstItem = rootElement[0]
        assertTrue(firstItem is JsonObject, "Macro item must be a JsonObject, not a string")

        val macroObj = firstItem
        assertEquals("complex_macro", macroObj["name"]?.jsonPrimitive?.content)

        // Check steps is an array of objects
        val stepsArray = macroObj["steps"] as? JsonArray
        assertTrue(stepsArray != null, "steps must be an array")
        val firstStepObj = stepsArray[0] as? JsonObject
        assertTrue(firstStepObj != null, "step must be an object")

        // Check args is a JsonObject with preserved types
        val argsObj = firstStepObj["args"] as? JsonObject
        assertTrue(argsObj != null, "args must be an object")
        assertTrue(argsObj["nestedObj"] is JsonObject, "nestedObj must remain JsonObject, not string")
        assertTrue(argsObj["nestedArr"] is JsonArray, "nestedArr must remain JsonArray, not string")

        // 3. Verify reloading in a fresh engine instance
        val reloadedEngine = MacroEngine(storageFile = testFile)
        val loadedMacro = reloadedEngine.getMacro("complex_macro")
        assertTrue(loadedMacro != null, "Macro should be successfully loaded from disk")
        assertEquals(1, loadedMacro.steps.size)
        assertEquals("discord_create_message", loadedMacro.steps[0].tool)
        assertTrue(loadedMacro.steps[0].args["nestedObj"] is kotlinx.serialization.json.JsonObject)

        testFile.delete()
    }

    @Test
    fun testMacroRegistrationFromSerializedJsonString() = runBlocking {
        val testFile = File("build/test_string_deser_macros.json")
        testFile.delete()

        val engine = MacroEngine(storageFile = testFile)
        val jsonParser = Json { ignoreUnknownKeys = true }

        val jsonString = """
            {
                "name": "string_deser_macro",
                "description": "Macro deserialized from JSON string",
                "parameters": {
                    "channelId": { "type": "string", "description": "Channel ID", "required": true }
                },
                "steps": [
                    {
                        "stepId": "s1",
                        "tool": "discord_create_message",
                        "args": {
                            "channel_id": "{{channelId}}",
                            "content": "Hello from deserialized macro"
                        }
                    }
                ]
            }
        """.trimIndent()

        // Test decoding from string directly (as MacroToolRegistrar does)
        val definition = jsonParser.decodeFromString<MacroDefinition>(jsonString)
        assertEquals("string_deser_macro", definition.name)
        assertEquals(1, definition.steps.size)

        // Register in engine
        engine.registerMacro(definition)

        // Verify disk content is valid structured JSON, not raw string
        val rawSaved = testFile.readText()
        val parsedJson = jsonParser.parseToJsonElement(rawSaved)
        assertTrue(parsedJson is JsonArray, "Saved content must be a JSON array")

        val loaded = engine.getMacro("string_deser_macro")
        assertTrue(loaded != null)
        assertEquals("Macro deserialized from JSON string", loaded.description)

        testFile.delete()
    }
}

