import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import name.remal.gradle_plugins.plugins.publish.bintray.RepositoryHandlerBintrayExtension
import name.remal.gradle_plugins.dsl.extensions.*

buildscript {
    repositories {
        jcenter()
    }

    dependencies {
        classpath("name.remal:gradle-plugins:1.0.129")
    }
}

plugins {
    kotlin("jvm").version("1.3.50")

    `maven-publish`
}

apply(plugin = "name.remal.maven-publish-bintray")

project.group = "moe.kanon.events"
project.description = "EventBus based event handler for Kotlin"
project.version = "2.1.0"
val gitUrl = "https://gitlab.com/Olivki/kanon-events"

repositories {
    mavenCentral()
    jcenter()
}

dependencies {
    implementation(kotlin("stdlib-jdk8"))
    implementation(kotlin("reflect"))
    implementation(group = "org.apache.logging.log4j", name = "log4j-api-kotlin", version = "1.0.0")
    implementation(group = "net.bytebuddy", name = "byte-buddy", version = "1.9.13")

    // -- TESTS -- \\
    testImplementation(group = "io.kotlintest", name = "kotlintest-runner-junit5", version = "3.1.11")
    testImplementation(group = "org.slf4j", name = "slf4j-simple", version = "1.8.0-beta2")
}

tasks {
    withType<KotlinCompile> {
        kotlinOptions.jvmTarget = "1.8"
        with(kotlinOptions) {
            freeCompilerArgs = listOf("-Xuse-experimental=kotlin.Experimental")
        }
    }
}

project.afterEvaluate {
    publishing.publications.withType<MavenPublication> {
        pom {
            name.set(project.name)
            description.set(project.description)
            url.set(gitUrl)

            licenses {
                license {
                    name.set("The Apache Software License, Version 2.0")
                    url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                    distribution.set("repo")
                }
            }

            developers {
                developer {
                    email.set("oliver@berg.moe")
                    id.set("Olivki")
                    name.set("Oliver Berg")
                }
            }

            scm {
                url.set(gitUrl)
            }
        }
    }

    publishing.repositories.convention[RepositoryHandlerBintrayExtension::class.java].bintray {
        owner = "olivki"
        repositoryName = "kanon"
        packageName = "kanon.events"
    }
}