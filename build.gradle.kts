import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.springframework.boot.gradle.tasks.bundling.BootBuildImage

plugins {
	id("org.springframework.boot") version "3.2.3"
	id("io.spring.dependency-management") version "1.1.4"
	kotlin("jvm") version "1.9.22"
	kotlin("plugin.spring") version "1.9.22"
}

group = "com.github"
version = "0.0.1-SNAPSHOT"

java {
	sourceCompatibility = JavaVersion.VERSION_17
}

repositories {
	mavenCentral()
	// For kotlin-telegram-bot
	maven("https://jitpack.io")
}

dependencies {
	// Spring
	implementation("org.springframework.boot:spring-boot-starter")
	implementation("org.springframework.boot:spring-boot-starter-webflux")
	// kotlin reflection is needed for spring to run
	implementation("org.jetbrains.kotlin:kotlin-reflect")

	// To enable webflux to coroutines communication
	implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")

	// Add telegram bot
	implementation("io.github.kotlin-telegram-bot.kotlin-telegram-bot:telegram:6.1.0")

	// HTML page parser
	implementation("org.jsoup:jsoup:1.17.1")

	// Tests
	testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
	testImplementation("org.springframework.boot:spring-boot-starter-test")
}

tasks.withType<KotlinCompile> {
	kotlinOptions {
		freeCompilerArgs += "-Xjsr305=strict"
		jvmTarget = "17"
	}
}

tasks.withType<Test> {
	useJUnitPlatform()
}

tasks.named<BootBuildImage>("bootBuildImage") {
	environment.set(mapOf(
		// To print UTF-8 logs to the docker logs
		// BPE_APPEND_* will append to the existing env property
		// https://paketo.io/docs/howto/configuration/
		"BPE_APPEND_JAVA_TOOL_OPTIONS" to " -Dfile.encoding=UTF8"
	))
}