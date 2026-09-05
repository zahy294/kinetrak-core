repositories {
    flatDir {
        dirs("libs")
    }
}

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar", "*.aar"))))

    // Qualcomm SNPE Java API
    implementation(name = "snpe-release", ext = "aar")

    // Google ARCore
    implementation("com.google.ar:core:1.54.0")
}
