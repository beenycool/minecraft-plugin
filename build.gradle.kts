import net.fabricmc.loom.task.RemapJarTask
import org.gradle.api.tasks.bundling.Jar

plugins {
    id("fabric-loom") version "1.6-SNAPSHOT"
    id("maven-publish")
    java
}

val minecraftVersion = project.property("minecraft_version") as String
val yarnMappings = project.property("yarn_mappings") as String
val loaderVersion = project.property("loader_version") as String
val fabricVersion = project.property("fabric_version") as String

version = project.property("mod_version") as String
group = project.property("maven_group") as String

base.archivesName.set(project.property("archives_base_name") as String)

repositories {
    mavenCentral()
    maven(url = "https://maven.fabricmc.net/") {
        name = "Fabric"
    }
}

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    mappings("net.fabricmc:yarn:$yarnMappings:v2")
    modImplementation("net.fabricmc:fabric-loader:$loaderVersion")
    modImplementation("net.fabricmc.fabric-api:fabric-api:$fabricVersion")

    implementation("com.google.code.gson:gson:2.11.0")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}

loom {
    splitEnvironmentSourceSets()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}

tasks.processResources {
    inputs.property("version", version)
    inputs.property("minecraftVersion", minecraftVersion)

    filesMatching("fabric.mod.json") {
        expand(
            mapOf(
                "version" to version,
                "modid" to "name_spoofer",
                "minecraft_version" to minecraftVersion
            )
        )
    }
}

sourceSets.named("test") {
    java.srcDir("src/test/java")
}

tasks.test {
    useJUnitPlatform()
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            artifact(tasks.named<RemapJarTask>("remapJar"))
            artifact(tasks.named<Jar>("sourcesJar"))
        }
    }
    repositories {
        mavenLocal()
    }
}
