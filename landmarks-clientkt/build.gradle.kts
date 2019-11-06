import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

val kotlinVersion = rootProject.extra.properties["kotlinVersion"] ?: "1.3.60-eap-25"
val ktorVersion = rootProject.extra.properties["ktorVersion"] ?: "1.3.0-beta-1"

buildscript {
  repositories {
    jcenter()
    maven(url = "https://dl.bintray.com/kotlin/kotlin-dev/")
  }
}

plugins {
  id("idea")
  id("org.jetbrains.kotlin.jvm")
  id("com.github.ben-manes.versions")
  id("com.github.johnrengelman.shadow")
  id("org.jetbrains.kotlin.plugin.serialization") version "1.3.50"
}

repositories {
  jcenter()
  maven(url = "https://dl.bintray.com/kotlin/ktor/")
  maven(url = "https://dl.bintray.com/kotlin/kotlinx/")
  maven(url = "https://dl.bintray.com/kotlin/kotlin-dev/")
}

dependencies {
  // Kotlin standard library
  implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:$kotlinVersion")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.3.2")
  implementation("org.jetbrains.kotlinx:kotlinx-serialization-runtime:0.13.0")
  implementation("io.ktor:ktor-client-core:$ktorVersion")
  implementation("io.ktor:ktor-client-okhttp:$ktorVersion")
  implementation("io.ktor:ktor-client-json-jvm:$ktorVersion")
  implementation("io.ktor:ktor-client-serialization-jvm:$ktorVersion")
  implementation("io.ktor:ktor-client-gson:$ktorVersion")
  implementation("com.squareup.okhttp3:okhttp:4.2.2")

  // JUnit 5 test framework
  testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.5.2")
  testCompileOnly("org.junit.platform:junit-platform-runner:1.5.2")

  // Spek, the kotlin test framework, with kotlin version replacement.
  testCompileOnly("org.jetbrains.kotlin:kotlin-reflect:$kotlinVersion")
  testCompileOnly("org.jetbrains.spek:spek-api:1.2.1") {
    exclude(group = "org.jetbrains.kotlin")
  }
  testRuntimeOnly("org.jetbrains.spek:spek-junit-platform-engine:1.2.1") {
    exclude(group = "org.junit.platform")
    exclude(group = "org.jetbrains.kotlin")
  }

  // Assertion framework
  testImplementation("org.amshove.kluent:kluent:1.56")

  // Proxy test
  testImplementation("io.ktor:ktor-client-apache:$ktorVersion")
}

tasks.test {
  useJUnitPlatform {
    includeEngines("spek2")
  }
}

tasks.withType<KotlinCompile> {
  kotlinOptions.jvmTarget = "1.8"
}

val compileTestKotlin by tasks.getting(KotlinCompile::class) {
  kotlinOptions.jvmTarget = "1.8"
}
