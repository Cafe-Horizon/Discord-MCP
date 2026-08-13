plugins {
    kotlin("jvm") version "2.4.10"
    kotlin("plugin.serialization") version "2.4.10"
    id("com.gradleup.shadow") version "9.6.1"
    application
}

group = "com.discordmcp"
version = "1.0.16"

repositories {
    mavenCentral()
}

val ktorVersion = "3.5.1"

dependencies {
    implementation("io.modelcontextprotocol:kotlin-sdk:0.15.0")

    implementation(platform("io.ktor:ktor-bom:$ktorVersion"))
    implementation("io.ktor:ktor-client-core")
    implementation("io.ktor:ktor-client-cio")
    implementation("io.ktor:ktor-client-content-negotiation")
    implementation("io.ktor:ktor-serialization-kotlinx-json")
    implementation("io.ktor:ktor-client-websockets")

    // HTTP / SSE server transports for the MCP server itself.
    implementation("io.ktor:ktor-server-cio")
    implementation("io.ktor:ktor-server-content-negotiation")
    implementation("io.ktor:ktor-server-sse")
    implementation("io.ktor:ktor-server-cors")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")

    implementation("org.slf4j:slf4j-simple:2.0.18")

    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(26)
}

application {
    mainClass.set("com.discordmcp.MainKt")
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    archiveClassifier.set("plain")
}

tasks.shadowJar {
    archiveBaseName.set("Discord-MCP")
    archiveClassifier.set("")
    archiveVersion.set(project.version.toString())
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    mergeServiceFiles()
    append("META-INF/LICENSE.txt")
    append("META-INF/NOTICE.txt")
    append("META-INF/LICENSE")
    manifest {
        attributes["Main-Class"] = "com.discordmcp.MainKt"
    }
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
