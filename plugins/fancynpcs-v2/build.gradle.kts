import net.minecrell.pluginyml.paper.PaperPluginDescription
import org.gradle.jvm.toolchain.JavaToolchainService

plugins {
    id("java-library")
    id("maven-publish")
    id("xyz.jpenilla.run-paper")
    id("com.gradleup.shadow")
    id("de.eldoria.plugin-yml.paper")
}

runPaper.folia.registerTask()

val pluginName = "FancyNpcs"
val pluginMain = "de.oliver.fancynpcs.FancyNpcs"
val pluginApiVersion = "1.19"
val pluginFoliaSupported = true
val optionalServerDependencies = listOf("PlaceholderAPI", "MiniPlaceholders", "PlotSquared", "Citizens", "SkinsRestorer")

allprojects {
    group = "de.oliver"
    version = getFNVersion()
    description = "Simple, lightweight and fast NPC plugin using packets"

    repositories {
        maven(url = "https://repo.inventivetalent.org/repository/maven-snapshots/") // for MineSkin
        maven(url = "https://repo.extendedclip.com/releases/") // for PlaceholderAPI
        maven(url = "https://maven.enginehub.org/repo/") // for WorldEdit
        maven(url = "https://maven.citizensnpcs.co/repo") // for Citizens
        maven(url = "https://repo.codemc.org/repository/maven-public/") // for SkinsRestorer
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.1.2.build.+")

    implementation(project(":plugins:fancynpcs-v2:fn-v2-api"))
    implementation(project(":plugins:fancynpcs-v2:implementation_26_3"))
    implementation(project(":plugins:fancynpcs-v2:implementation_26_2"))
    implementation(project(":plugins:fancynpcs-v2:implementation_26_1_2"))
    implementation(project(":plugins:fancynpcs-v2:implementation_1_21_11"))
    implementation(project(":plugins:fancynpcs-v2:implementation_1_21_9"))
    implementation(project(":plugins:fancynpcs-v2:implementation_1_21_6"))
    implementation(project(":plugins:fancynpcs-v2:implementation_1_21_5"))

    rootProject.subprojects
        .filter { it.path.startsWith(":libraries:packets:implementations") }
        .forEach { implementation(project(it.path)) }
    implementation(project(":libraries:packets"))
    implementation(project(":libraries:packets:packets-api"))
    implementation(project(":libraries:common"))
    implementation(project(":libraries:jdb"))
    implementation(project(":libraries:plugin-tests"))
    implementation(project(":libraries:config"))
    compileOnly("org.lushplugins.chatcolorhandler:paper:8.1.1")
    implementation("de.oliver.FancyAnalytics:java-sdk:0.0.6")
    implementation("de.oliver.FancyAnalytics:mc-api:0.1.13")
    implementation("de.oliver.FancyAnalytics:logger:0.0.10")
    implementation("org.incendo:cloud-core:2.0.0")
    implementation("org.incendo:cloud-paper:2.0.0-beta.16")
    implementation("org.incendo:cloud-annotations:2.0.0")
    annotationProcessor("org.incendo:cloud-annotations:2.0.0")
    implementation("org.mineskin:java-client-jsoup:3.0.3-SNAPSHOT")

    compileOnly("me.clip:placeholderapi:2.12.2")
    compileOnly("com.intellectualsites.plotsquared:plotsquared-core:7.5.13")
    compileOnly("com.sk89q.worldedit:worldedit-bukkit:7.4.3")
    compileOnly("net.citizensnpcs:citizens-main:2.0.42-SNAPSHOT") {
        exclude(group = "*", module = "*")
    }
    compileOnly("net.skinsrestorer:skinsrestorer-api:15.12.4")
}

paper {
    name = pluginName
    main = pluginMain
    loader = "de.oliver.fancynpcs.loaders.FancyNpcsLoader"
    foliaSupported = pluginFoliaSupported
    version = project.version.toString()
    description = "Simple, lightweight and fast NPC plugin using packets"
    apiVersion = pluginApiVersion
    serverDependencies {
        for (dependency in optionalServerDependencies) {
            register(dependency) {
                required = false
                load = PaperPluginDescription.RelativeLoadOrder.BEFORE
            }
        }
    }
}

tasks {
    runServer {
        minecraftVersion("26.2")
        //serverJar(file("/Users/oliver/Workspace/paper/paper-server/build/libs/paper-bundler-26.2.build.1-alpha.jar"))


        downloadPlugins {
//            url("https://fancyspaces.net/api/v1/spaces/s1gGcHj5/versions/A364LHvu/files/FancyWorlds-0.0.4.jar")
//            modrinth("FancyHolograms", "2.9.1")
//            modrinth("FancyDialogs", "1.1.2")
//            modrinth("FancyEconomy", "1.0.3+6")

//            modrinth("kite", "1.4.0")
//            hangar("PlaceholderAPI", "2.11.6")
//            hangar("ViaVersion", "5.8.1")
//            hangar("ViaBackwards", "5.8.1")
//            url("https://ci.citizensnpcs.co/job/citizens2/4138/artifact/dist/target/Citizens-2.0.41-b4138.jar")
        }
    }

    shadowJar {
        val shadedPatch = rootProject.layout.projectDirectory.file("build-support/FancyNpcsShadedPatch.java")
        val gradleLibraries = gradle.gradleHomeDir!!.resolve("lib/*")
        val java25 = project.extensions.getByType<JavaToolchainService>().launcherFor {
            languageVersion.set(JavaLanguageVersion.of(25))
        }
        val artifact = archiveFile
        inputs.file(shadedPatch)
        relocate("org.incendo", "de.oliver")
        relocate("org.lushplugins.chatcolorhandler", "de.oliver.fancynpcs.libs.chatcolorhandler")
        archiveClassifier.set("")
        archiveBaseName.set(pluginName)
        dependsOn(":plugins:fancynpcs-v2:fn-v2-api:shadowJar")
        doLast {
            providers.exec {
                executable = java25.get().executablePath.asFile.absolutePath
                args("-cp", gradleLibraries.absolutePath, shadedPatch.asFile.absolutePath, artifact.get().asFile.absolutePath)
            }.result.get().assertNormalExitValue()
        }
    }

    publishing {
        repositories {
            maven {
                name = "fancyinnovationsReleases"
                url = uri("https://repo.fancyinnovations.com/releases")
                credentials(PasswordCredentials::class)
                authentication {
                    isAllowInsecureProtocol = true
                    create<BasicAuthentication>("basic")
                }
            }

            maven {
                name = "fancyinnovationsSnapshots"
                url = uri("https://repo.fancyinnovations.com/snapshots")
                credentials(PasswordCredentials::class)
                authentication {
                    isAllowInsecureProtocol = true
                    create<BasicAuthentication>("basic")
                }
            }
        }
        publications {
            create<MavenPublication>("maven") {
                groupId = project.group.toString()
                artifactId = project.name
                version = project.version.toString()
                from(project.components["java"])
            }
        }
    }

    compileJava {
        options.encoding = Charsets.UTF_8.name() // We want UTF-8 for everything
        options.release = 25
        // For cloud-annotations, see https://cloud.incendo.org/annotations/#command-components
        options.compilerArgs.add("-parameters")
    }

    javadoc {
        options.encoding = Charsets.UTF_8.name() // We want UTF-8 for everything
    }

    processResources {
        filteringCharset = Charsets.UTF_8.name() // We want UTF-8 for everything

        val props = mapOf(
            "description" to project.description,
            "version" to project.version.toString(),
            "commit_hash" to gitCommitHash.get(),
            "channel" to (System.getenv("RELEASE_CHANNEL") ?: "").ifEmpty { "undefined" },
            "platform" to (System.getenv("RELEASE_PLATFORM") ?: "").ifEmpty { "undefined" },
            "name" to pluginName,
            "main" to pluginMain,
            "apiVersion" to pluginApiVersion,
            "foliaSupported" to pluginFoliaSupported,
            "softDependencies" to optionalServerDependencies.joinToString(", ")
        )

        inputs.properties(props)

        // PlugManX's load suggestions read the Bukkit descriptor, even for Paper plugins.
        filesMatching(listOf("paper-plugin.yml", "plugin.yml", "version.yml")) {
            expand(props)
        }
    }
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

val gitCommitHash: Provider<String> = providers.exec {
    commandLine("git", "rev-parse", "HEAD")
}.standardOutput.asText.map { it.trim() }

val gitCommitMessage: Provider<String> = providers.exec {
    commandLine("git", "log", "-1", "--pretty=%B")
}.standardOutput.asText.map { it.trim() }

fun getFNVersion(): String {
    return file("VERSION").readText().trim()
}
