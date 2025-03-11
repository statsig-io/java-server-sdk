
import com.google.protobuf.gradle.*
import org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
import org.gradle.language.jvm.tasks.ProcessResources
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.8.22"
    idea
    id("com.github.johnrengelman.shadow") version "7.1.2"
    id("org.jlleitschuh.gradle.ktlint") version "11.0.0"
    id("maven-publish")
    id("com.vanniktech.maven.publish") version "0.22.0"
    id("com.google.protobuf") version "0.9.4"
    id("org.gradle.test-retry") version "1.2.1"
}

group = "com.statsig"
version = project.properties["VERSION_NAME"]!!

repositories {
    mavenCentral()
    maven { setUrl("https://jitpack.io") }
}

configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
    verbose.set(true)
    disabledRules.set(setOf("no-wildcard-imports"))
    filter {
        exclude("**/generated/**")
    }
}

dependencies {
    protobuf(files("api-interface-definitions/protos/"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.10.0")
    testImplementation("io.mockk:mockk:1.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.6.4")
    testImplementation("io.grpc:grpc-testing:1.66.0")
    implementation("com.google.code.gson:gson:2.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8:1.6.4")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.github.ua-parser:uap-java:1.6.1")
    implementation("com.statsig:ip3country:0.1.5")
    implementation("com.squareup.okhttp3:logging-interceptor:4.11.0")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    // GRPC Dependencies
    implementation("io.grpc:grpc-netty-shaded:1.66.0")
    implementation("io.grpc:grpc-stub:1.66.0")
    implementation("io.grpc:grpc-protobuf:1.71.0")
    implementation("org.apache.tomcat:annotations-api:6.0.53")
}

val sourcesJar by tasks.registering(Jar::class) {
    archiveClassifier.set("sources")
    from(sourceSets.main.get().allSource)
}

publishing {
    publications {
        register("mavenJava", MavenPublication::class) {
            from(components["java"])
            artifact(sourcesJar.get())
        }
    }
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

    retry {
        maxRetries.set(5)
        maxFailures.set(20)
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
    kotlinOptions.jvmTarget = "1.8"
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:3.25.3"
    }
    plugins {
        id("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:1.66.0"
        }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.plugins {
                id("grpc")
            }
            task.doLast {
                val outputPath = task.outputs.files.getAsPath()
                copy {
                    from("$outputPath/grpc")
                    from("$outputPath/java")
                    into("$projectDir/src/main/java")
                }
                delete("$outputPath")
            }
        }
    }
}
