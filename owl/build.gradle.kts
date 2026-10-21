plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

// The widget language: plain Kotlin, shared by the app and the `owl` checker that marketplace CI runs.
kotlin { jvmToolchain(17) }

/** The `owl` command line (check, review, index): its own source set, so the app's copy of the library doesn't carry it. */
val cli: SourceSet by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}
configurations[cli.implementationConfigurationName].extendsFrom(configurations.implementation.get())

application {
    mainClass = "dev.omalauncher.widgets.cli.MainKt"
    applicationName = "owl"
}

tasks.named<JavaExec>("run") { classpath = cli.runtimeClasspath }

dependencies {
    testImplementation(libs.junit)
}

// ShareTest round-trips the shipped OWL files; changing an example must rerun it.
tasks.withType<Test>().configureEach { inputs.dir(rootProject.file("app/src/main/assets/widgets")) }

/** A single runnable jar (`java -jar owl.jar check ...`) for CI. */
tasks.register<Jar>("owlJar") {
    archiveFileName = "owl.jar"
    destinationDirectory = layout.buildDirectory.dir("cli")
    manifest { attributes["Main-Class"] = "dev.omalauncher.widgets.cli.MainKt" }
    from(sourceSets.main.get().output, cli.output)
    from(cli.runtimeClasspath.filter { it.name.endsWith(".jar") }.map { zipTree(it) })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    exclude("META-INF/*.kotlin_module", "META-INF/versions/**")
}
