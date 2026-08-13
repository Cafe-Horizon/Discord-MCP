package com.discordmcp

import com.discordmcp.macro.MacroDefinition
import com.discordmcp.macro.MacroEngine
import com.discordmcp.macro.MacroStep
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
