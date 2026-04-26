plugins {
    kotlin("jvm") version "1.9.25"
    kotlin("plugin.serialization") version "1.9.25"
    id("io.ktor.plugin") version "2.3.12"
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "com.aos.chatbot"
version = "0.1.0"

application {
    mainClass.set("io.ktor.server.netty.EngineMain")
}

repositories {
    mavenCentral()
}

val ktorVersion = "2.3.12"
val kotlinxSerializationVersion = "1.6.3"

dependencies {
    // Ktor server
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")

    // Ktor HTTP client (Ollama integration)
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")

    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$kotlinxSerializationVersion")

    // Document processing
    implementation("org.apache.poi:poi-ooxml:5.2.5")
    implementation("org.apache.pdfbox:pdfbox:3.0.1")

    // SQLite
    implementation("org.xerial:sqlite-jdbc:3.45.3.0")

    // Message queue (Artemis / Jakarta Messaging 3.1)
    implementation("jakarta.jms:jakarta.jms-api:3.1.0")
    implementation("org.apache.activemq:artemis-jakarta-client:2.32.0")

    // Logging
    implementation("ch.qos.logback:logback-classic:1.4.14")

    // Testing
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("io.mockk:mockk:1.13.10")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("com.github.tomakehurst:wiremock-jre8-standalone:3.0.1")

    // Embedded Artemis broker for queue tests (vm:// transport, Jakarta JMS 3.1)
    testImplementation("org.apache.activemq:artemis-jakarta-server:2.32.0")
}

tasks.test {
    useJUnitPlatform {
        excludeTags("integration")
    }
}

tasks.register<Test>("integrationTest") {
    description = "Runs tests tagged as @Tag(\"integration\") against local services (e.g., real Ollama)."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform {
        includeTags("integration")
    }
}

tasks.shadowJar {
    archiveClassifier.set("all")
    mergeServiceFiles()
}

kotlin {
    jvmToolchain(17)
}
