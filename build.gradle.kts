import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.kotlin.dsl.implementation

plugins {
  val kotlinVersion = "2.0.20"
  val shadowVersion = "8.3.3"

  kotlin("jvm") version kotlinVersion
  kotlin("plugin.serialization") version kotlinVersion

  id("com.gradleup.shadow") version shadowVersion
}

group = "io.joaoseidel"
version = "1.0-SNAPSHOT"

repositories {
  mavenCentral()
  maven("https://jitpack.io")
}

dependencies {
  val kotlinX = "1.9.0"
  val kordVersion = "0.15.0"
  val mongoDriver = "5.2.0"
  val ktorVersion = "3.0.0"
  val kotlinLogging = "7.0.0"
  val slf4jSimpleVersion = "2.0.16"

  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$kotlinX")
  implementation("org.mongodb:mongodb-driver-kotlin-coroutine:$mongoDriver")
  implementation("org.mongodb:bson-kotlinx:$mongoDriver")
  implementation("dev.kord:kord-core:$kordVersion")
  implementation("io.ktor:ktor-client-core:$ktorVersion")
  implementation("io.ktor:ktor-client-cio:$ktorVersion")
  implementation("io.github.oshai:kotlin-logging-jvm:$kotlinLogging")
  implementation("org.slf4j:slf4j-simple:$slf4jSimpleVersion")
  implementation("com.github.Pool-Of-Tears:KtScheduler:1.1.6")

  testImplementation(kotlin("test"))
}

tasks.test {
  useJUnitPlatform()
}

tasks.withType<Jar> {
  manifest {
    attributes["Main-Class"] = "io.joaoseidel.k9.MainKt"
  }
}

tasks.named<ShadowJar>("shadowJar") {
  archiveBaseName.set("app")
  archiveClassifier.set("")
  archiveVersion.set("")
}
