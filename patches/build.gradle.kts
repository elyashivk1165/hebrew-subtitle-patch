group = "app.revanced"

patches {
    about {
        name = "Hebrew Subtitle Patch"
        description = "Adds Hebrew auto-translation to YouTube subtitles"
        source = "https://github.com/elyashivk1165/hebrew-subtitle-patch"
        author = "elyashivk1165"
        contact = ""
        website = "https://github.com/elyashivk1165/hebrew-subtitle-patch"
        license = "GNU General Public License v3.0"
    }
}

tasks {
    register<JavaExec>("generatePatchesFiles") {
        description = "Generate patches files"
        dependsOn(build)
        classpath = sourceSets["main"].runtimeClasspath
        mainClass.set("app.revanced.generator.MainKt")
    }
}

