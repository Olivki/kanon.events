
import com.jfrog.bintray.gradle.BintrayExtension
import org.jetbrains.dokka.gradle.DokkaTask
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm").version("1.3.21")

    id("com.adarshr.test-logger").version("1.6.0") // For pretty-printing for tests.
    id("com.jfrog.bintray").version("1.8.4") // For publishing to BinTray.
    id("org.jetbrains.dokka").version("0.9.17") // The KDoc engine.
    id("com.github.ben-manes.versions").version("0.20.0") // For checking for new dependency versions.

    `maven-publish`
}

// Project Specific Variables
project.group = "moe.kanon.events"
project.description = "Event handling with annotations for Kotlin."
project.version = "1.1.0"
val artifactName = "kanon.events"
val gitUrl = "https://gitlab.com/Olivki/kanon-events"

// General Tasks
repositories {
    mavenCentral()
    jcenter()
}

dependencies {
    // Normal Dependencies
    implementation(kotlin("stdlib-jdk8"))
    implementation(kotlin("reflect"))
    implementation(group = "org.ow2.asm", name = "asm", version = "7.0")
    implementation(group = "io.github.microutils", name = "kotlin-logging", version = "1.6.24")
    
    // Test Dependencies
    testImplementation(group = "io.kotlintest", name = "kotlintest-runner-junit5", version = "3.1.11")
    testImplementation(group = "org.slf4j", name = "slf4j-simple", version = "1.8.0-beta2")
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "1.8"
}

tasks.withType<Wrapper> {
    gradleVersion = "4.10"
    distributionType = Wrapper.DistributionType.BIN
}

// Dokka Tasks
val dokkaJavaDoc by tasks.creating(DokkaTask::class) {
    outputFormat = "javadoc"
    outputDirectory = "$buildDir/javadoc"
    inputs.dir("src/main/kotlin")
    includeNonPublic = false
    skipEmptyPackages = true
    jdkVersion = 8
}

// Test Tasks
testlogger {
    setTheme("mocha")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// Artifact Tasks
val sourcesJar by tasks.creating(Jar::class) {
    dependsOn(JavaPlugin.CLASSES_TASK_NAME)
    description = "Assembles the sources of this project into a *-sources.jar file."
    classifier = "sources"
    
    from(project.sourceSets["main"].allSource)
}

val javaDocJar by tasks.creating(Jar::class) {
    description = "Creates a *-javadoc.jar from the generated dokka output."
    classifier = "javadoc"
    
    from(dokkaJavaDoc)
}

artifacts {
    add("archives", sourcesJar)
    add("archives", javaDocJar)
}

// Publishing Tasks
// BinTray
bintray {
    // Credentials.
    user = getVariable("BINTRAY_USER")
    key = getVariable("BINTRAY_KEY")
    
    // Whether or not the "package" should automatically be published.
    publish = true
    
    // Sets the publication to our created maven publication instance.
    setPublications("mavenPublication")
    
    // Details for the actual package that's going up on BinTray.
    pkg(delegateClosureOf<BintrayExtension.PackageConfig> {
        repo = "kanon"
        desc = project.description
        name = artifactName
        websiteUrl = gitUrl
        vcsUrl = "$gitUrl.git"
        publicDownloadNumbers = true
        setLicenses("Apache-2.0")
        setLabels("kotlin")
        
        version(delegateClosureOf<BintrayExtension.VersionConfig> {
            name = project.version.toString()
            desc = project.version.toString()
            released = `java.util`.Date().toString()
        })
    })
}

// Maven
// Creates the *.pom file which lists all the dependencies of this project.
// Not really needed (?)
/*fun org.gradle.api.publish.maven.MavenPom.addDependencies() = withXml {
    asNode().appendNode("dependencies").let { depNode ->
        configurations.compile.allDependencies.forEach {
            depNode.appendNode("dependency").apply {
                appendNode("groupId", it.group)
                appendNode("artifactId", it.name)
                appendNode("version", it.version)
            }
        }
    }
}*/

// Maven Tasks
publishing {
    publications.invoke {
        register("mavenPublication", MavenPublication::class.java) {
            // Adds all the dependencies this project uses to the pom.
            from(components["java"])

            afterEvaluate {
                // General project information.
                groupId = project.group.toString()
                version = project.version.toString()
                artifactId = artifactName

                // Any extra artifacts that need to be added, ie: sources & javadoc jars.
                artifact(sourcesJar)
                artifact(javaDocJar)
            }
        }
    }
}

// Misc Functions & Properties
fun getVariable(name: String) = System.getenv(name)!!
