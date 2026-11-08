import java.util.jar.JarFile

plugins {
    id("com.android.library")
}

android {
    namespace = "dev.kifranei.ampp.monet"
    compileSdk = 37
    defaultConfig { minSdk = 28 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin { compilerOptions {
    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    freeCompilerArgs.add("-Xlambdas=class")
} }

// plugin.json is the single source of truth for the version; CI and the ZIP name derive from it.
val pluginManifest = groovy.json.JsonSlurper().parse(file("plugin.json")) as Map<*, *>
val pluginVersion = pluginManifest["versionName"] as String
val hostSdk = files(rootProject.file("lib/ampp-plugin-api-v1.jar"))

dependencies {
    // The SDK classes are supplied by AM++; never bundle them in code.jar.
    compileOnly(hostSdk)
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.4.10")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation(hostSdk)
}

val dexDirectory = layout.buildDirectory.dir("plugin/dex")
val dex = tasks.register<JavaExec>("pluginDex") {
    dependsOn("bundleReleaseAar")
    mainClass = "com.android.tools.r8.D8"
    doFirst {
        val sdk = androidComponents.sdkComponents.sdkDirectory.get().asFile
        val androidJar = listOf("android-37", "android-37.0").map { sdk.resolve("platforms/$it/android.jar") }
            .first { it.isFile }
        classpath = files(sdk.resolve("build-tools/37.0.0/lib/d8.jar"))
        val destination = dexDirectory.get().asFile
        destination.mkdirs()
        // Avoid retaining old secondary DEX files between incremental packages.
        destination.listFiles()?.filter { it.name.matches(Regex("classes[0-9]*\\.dex")) }?.forEach { it.delete() }
        val inputs = mutableListOf<File>()
        fun classes(file: File): File {
            if (file.extension != "aar") return file
            val output = layout.buildDirectory.file("plugin/jars/${file.nameWithoutExtension}.jar").get().asFile
            output.parentFile.mkdirs()
            JarFile(file).use { jar -> jar.getInputStream(jar.getJarEntry("classes.jar")).use { input ->
                output.outputStream().use(input::copyTo)
            } }
            return output
        }
        inputs += classes(layout.buildDirectory.file("outputs/aar/monet-plugin-release.aar").get().asFile)
        fun jars(configuration: String) = configurations.getByName(configuration).incoming.artifactView {
            attributes.attribute(org.gradle.api.artifacts.type.ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "android-classes-jar")
        }.files.files
        inputs += jars("releaseRuntimeClasspath")
        val references = jars("releaseCompileClasspath")
        setArgs(listOf("--release", "--min-api", "28", "--lib", androidJar.path,
            "--output", destination.path) + references.flatMap { listOf("--classpath", it.path) } + inputs.map { it.path })
    }
}
val codeJar = tasks.register<Zip>("pluginCodeJar") {
    dependsOn(dex)
    from(dexDirectory) { include("classes*.dex") }
    archiveFileName = "code.jar"
    destinationDirectory = layout.buildDirectory.dir("plugin/code")
}
tasks.register<Zip>("pluginZip") {
    dependsOn(codeJar)
    from(codeJar.flatMap { it.archiveFile })
    from("plugin.json")
    from("src/main/assets") { into("assets") }
    from(rootProject.file("LICENSE")) { into("assets/licenses"); rename { "AMpp-GPL-3.0.txt" } }
    archiveFileName = "ampp-monet-cover-color-${pluginVersion}.zip"
    destinationDirectory = layout.buildDirectory.dir("dist")
}
tasks.register("printPluginVersion") {
    val version = pluginVersion
    doLast { println(version) }
}
