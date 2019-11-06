import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

val kotlinVersion by extra("1.3.60-eap-25")
val ktorVersion by extra("1.3.0-beta-1")
val spekVersion = "2.0.8"

buildscript {
  val kotlinVersion by extra { "1.3.60-eap-25" }
  repositories {
    jcenter()
    maven(url = "https://dl.bintray.com/kotlin/kotlin-dev/")
  }
  dependencies {
    classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:${kotlinVersion}")
  }
}

plugins {
  idea
  application
  id("com.github.ben-manes.versions") version "0.27.0"
  id("com.github.johnrengelman.shadow") version "5.1.0"
  id("org.jetbrains.kotlin.plugin.serialization") version "1.3.50"
}
apply(plugin = "kotlin")

sourceSets {
  main {
    resources.srcDirs(listOf("src/main/res"))
  }
}

application {
  mainClassName = "kr.ac.kw.coms.landmarks.server.ApplicationKt"
}

tasks.jar {
  baseName = "landmarks-serverkt"
  version = "0.1"
  manifest {
    attributes["Main-Class"] = "kr.ac.kw.coms.landmarks.server.ApplicationKt"
  }
}

repositories {
  jcenter()
  maven(url = "https://dl.bintray.com/kotlin/ktor/")
  maven(url = "https://dl.bintray.com/kotlin/exposed/")
  maven(url = "https://dl.bintray.com/kotlin/kotlinx/")
  maven(url = "https://dl.bintray.com/kotlin/kotlin-dev/")
}

dependencies {
  // MultiPartContent
  implementation(project(":landmarks-clientkt"))

  // Kotlin standard library
  implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:${kotlinVersion}")

  // Ktor, the kotlin web framework
  implementation("io.ktor:ktor-server-core:$ktorVersion")
  implementation("io.ktor:ktor-server-netty:$ktorVersion")
  implementation("io.ktor:ktor-html-builder:$ktorVersion")
  implementation("io.ktor:ktor-locations:$ktorVersion")
  implementation("io.ktor:ktor-serialization:$ktorVersion")
  implementation("org.jetbrains.kotlinx:kotlinx-serialization-runtime:0.13.0")
  // Logging
  implementation("ch.qos.logback:logback-classic:1.3.0-alpha5")

  // Kotlin ORM
  implementation("org.jetbrains.exposed:exposed:0.17.7")
  implementation("joda-time:joda-time:2.10.5")
  // Fast connection pool for performance
  implementation("com.zaxxer:HikariCP:3.4.1")
  // SQLite JDBC Driver
  implementation("org.xerial:sqlite-jdbc:3.27.2.1")
  // Postgresql JDBC Driver
  implementation("org.postgresql:postgresql:42.2.5")

  // Thumbnail
  implementation("net.coobird:thumbnailator:0.4.8")
  // GPS extraction
  implementation("com.drewnoakes:metadata-extractor:2.12.0")

  // JUnit 5 test framework
  testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.6.0-M1")
  testImplementation("org.junit.platform:junit-platform-runner:1.6.0-M1")

  // Spek, the kotlin test framework, with kotlin version replacement.
  testImplementation("org.spekframework.spek2:spek-dsl-jvm:$spekVersion")
  testRuntimeOnly("org.spekframework.spek2:spek-runner-junit5:$spekVersion")
  // spek requires kotlin-reflect, can be omitted if already in the classpath
  testRuntimeOnly("org.jetbrains.kotlin:kotlin-reflect:$kotlinVersion")

  // Assertion framework
  testImplementation("org.amshove.kluent:kluent:1.56")
  // Ktor test utility
  testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")

  // Frontend assets
  implementation("org.webjars:webjars-locator:0.36")
}

task("stage") {
  dependsOn("shadowJar")
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
