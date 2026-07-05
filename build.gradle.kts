plugins {
    id("com.android.application") version "9.2.0" apply false
    id("com.android.library") version "9.2.0" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
}

// Without this, a transitive dependency pulls in kotlin-stdlib 2.4.0, whose metadata
// version the 2.2.10 Kotlin compiler can't read (verified: removing this breaks the build).
// Applied to every module so :core and :automotive don't hit the same conflict.
allprojects {
    configurations.all {
        resolutionStrategy {
            force("org.jetbrains.kotlin:kotlin-stdlib:2.2.10")
            force("org.jetbrains.kotlin:kotlin-stdlib-jdk7:2.2.10")
            force("org.jetbrains.kotlin:kotlin-stdlib-jdk8:2.2.10")
            force("org.jetbrains.kotlin:kotlin-reflect:2.2.10")
        }
    }
}
