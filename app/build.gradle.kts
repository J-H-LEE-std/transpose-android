plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.pockettranspose"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.pockettranspose"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("androidx.webkit:webkit:1.12.1")
}

// Inspect the exact resolved runtime artifacts, including transitive dependencies.
// Unknown/missing licenses fail closed. No additional Gradle plugin is required.
val verifyRuntimeLicenses by tasks.registering {
    group = "verification"
    doLast {
        val allowed = setOf("Apache-2.0", "MIT", "BSD-2-Clause", "BSD-3-Clause", "ISC", "Zlib")
        val rows = mutableListOf<String>()
        val modules = configurations.getByName("releaseRuntimeClasspath")
            .resolvedConfiguration.resolvedArtifacts.map { it.moduleVersion.id }.distinctBy { it.toString() }
        modules.sortedBy { it.toString() }.forEach { module ->
            val pomConfig = configurations.detachedConfiguration(
                dependencies.create("${module.group}:${module.name}:${module.version}@pom")
            ).apply { isTransitive = false }
            val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            }
            val doc = factory.newDocumentBuilder().parse(pomConfig.singleFile)
            val licenses = doc.getElementsByTagName("license")
            val found = (0 until licenses.length).map { i ->
                val element = licenses.item(i) as org.w3c.dom.Element
                val name = element.getElementsByTagName("name").item(0)?.textContent.orEmpty()
                val url = element.getElementsByTagName("url").item(0)?.textContent.orEmpty()
                val normalized = when (name.lowercase().trim()) {
                    "apache license, version 2.0", "the apache software license, version 2.0",
                    "the apache license, version 2.0",
                    "apache 2.0", "apache license 2.0", "apache-2.0" -> "Apache-2.0"
                    "mit", "mit license", "the mit license" -> "MIT"
                    else -> name
                }
                check(normalized in allowed) { "Unapproved license for $module: $name ($url)" }
                "$normalized ($url)"
            }
            check(found.isNotEmpty()) { "Missing license evidence for $module; review its POM before adding it." }
            rows += "| $module | ${found.joinToString("; ")} |"
        }
        val report = layout.buildDirectory.file("reports/runtime-licenses.md").get().asFile
        report.parentFile.mkdirs()
        report.writeText("# Resolved runtime license inventory\n\n| Dependency | POM license evidence |\n| --- | --- |\n" + rows.joinToString("\n") + "\n")
        println("Verified ${rows.size} runtime dependencies: $report")
    }
}
tasks.named("preBuild") { dependsOn(verifyRuntimeLicenses) }
