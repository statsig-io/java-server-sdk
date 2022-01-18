import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.gradle.language.jvm.tasks.ProcessResources

plugins {
    kotlin("jvm") version "1.5.0"
    idea
    `maven-publish`
}

group = "com.statsig"

repositories {
    mavenCentral()
    maven { setUrl("https://jitpack.io") }
}

dependencies {
    testImplementation("junit:junit:4.13")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.9.1")
    testImplementation("io.mockk:mockk:1.10.6")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.5.1")
    implementation("com.google.code.gson:gson:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.5.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8:1.5.0")
    implementation("com.squareup.okhttp3:okhttp:4.9.1")
    implementation("com.github.statsig-io:ip3country-kotlin:0.1.0")
    implementation("com.github.ua-parser:uap-java:1.5.2")
}

tasks.test {
    useJUnit()
}

configure<ProcessResources>("processResources") {
    filesMatching("statsigsdk.properties") {
        expand(project.properties)
    }
}

inline fun <reified C> Project.configure(name: String, configuration: C.() -> Unit) {
    (this.tasks.getByName(name) as C).configuration()
}

tasks.withType<KotlinCompile>() {
    kotlinOptions.jvmTarget = "1.8"
}
