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
    // Disable sources and javadoc jars so only patches.rvp is produced
    withType<Jar> {
        if (name.contains("sources", ignoreCase = true) || name.contains("javadoc", ignoreCase = true)) {
            enabled = false
        }
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs = listOf("-Xcontext-receivers")
    }
}

