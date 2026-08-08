// Root build file. Plugin versions are declared here and applied per-module
// (see app/build.gradle.kts) so a future multi-module ReLite Home can share
// them without redeclaring.
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
}
