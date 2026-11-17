plugins { alias(libs.plugins.kotlin.jvm) }
kotlin { jvmToolchain(17) }
dependencies { testImplementation(libs.junit) }
tasks.named<ProcessResources>("processResources") {
    from(rootProject.file("docs/debloat-packages.txt"))
}
