group = "app.revanced"
version = "1.2.1"

patches {
    about {
        name = "Hebrew Subtitle Patch for Morphe"
        description = "Adds Hebrew auto-translation to YouTube subtitles"
        source = "https://github.com/elyashivk1165/hebrew-subtitle-patch"
        author = "elyashivk1165"
        contact = ""
        website = "https://github.com/elyashivk1165/hebrew-subtitle-patch"
        license = "GNU General Public License v3.0"
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs = listOf("-Xcontext-parameters", "-Xskip-prerelease-check")
    }
}

// Exercise Morphe's real bundle loader to catch binary/API linkage problems.
tasks.register<JavaExec>("verifyPatchBundle") {
    dependsOn("buildAndroid", "testClasses")
    classpath = sourceSets["test"].output + configurations["testRuntimeClasspath"]
    mainClass.set("PatchBundleSmokeTest")
    doFirst {
        args(layout.buildDirectory.dir("libs").get().asFile
            .listFiles()!!.single { it.extension == "mpp" }.absolutePath)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(11)
}

// Export tools for testing a user-provided APK locally without uploading it.
tasks.register<Sync>("exportVerificationTools") {
    dependsOn("testClasses")
    from(configurations["testRuntimeClasspath"]) { into("lib") }
    from(sourceSets["test"].output) { into("classes") }
    into(layout.buildDirectory.dir("verification-tools"))
}

dependencies {
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
}
