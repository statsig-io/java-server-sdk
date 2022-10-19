import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.gradle.language.jvm.tasks.ProcessResources
import org.gradle.api.tasks.testing.logging.TestExceptionFormat.*

plugins {
    kotlin("jvm") version "1.6.0"
    idea
    id("com.vanniktech.maven.publish") version "0.22.0"
}

group = "com.statsig"
version = project.properties["VERSION_NAME"]!!

repositories {
    mavenCentral()
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.10.0")
    testImplementation("io.mockk:mockk:1.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.6.4")
    implementation("com.google.code.gson:gson:2.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8:1.6.4")
    implementation("com.squareup.okhttp3:okhttp:4.10.0")
    implementation("com.github.ua-parser:uap-java:1.5.3")
    implementation("com.statsig:ip3country:0.1.1")
}

tasks.test {
    useJUnit()

    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = FULL
        showExceptions = true
        showCauses = true
        showStackTraces = true
    }
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
    kotlinOptions.jvmTarget = "11"
}
