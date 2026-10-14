// Third-party license gate (see README "License policy" and THIRD_PARTY_NOTICES.md).
//
// Implemented with Gradle's own APIs only, so the checker itself adds no third-party
// dependency. It resolves the Maven POM of every external module on the checked
// runtime classpaths (walking parent POMs when <licenses> is inherited), classifies
// each license, writes a Markdown report, and fails the build when a module is
// forbidden (copyleft/reciprocal), dual-licensed with a forbidden option, or unknown.
//
// Report: <module>/build/reports/third-party-licenses/third-party-licenses.md

import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.maven.MavenModule
import org.gradle.maven.MavenPomArtifact
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

// Runtime classpaths that end up inside the APK. Missing names are skipped so the
// script also works in a plain JVM project.
val licenseCheckConfigurations = listOf("debugRuntimeClasspath", "releaseRuntimeClasspath", "runtimeClasspath")

// Modules whose license was verified by hand. Key: "group:name". Value: reviewed license.
// Only add an entry after recording the evidence in THIRD_PARTY_NOTICES.md.
val manuallyReviewedLicenses = mapOf<String, String>()

val forbiddenLicensePattern = Regex(
    "(?i)(\\b[AL]?GPL|General Public License|\\bGNU\\b|\\bMPL\\b|Mozilla Public|\\bEPL\\b|Eclipse Public|" +
        "\\bCDDL\\b|Common Development and Distribution|\\bCPL\\b|Common Public License|\\bOSL\\b|" +
        "Open Software License|\\bSSPL\\b|Server Side Public|ShareAlike|CC-BY-SA|Reciprocal)"
)
val allowedLicensePattern = Regex(
    "(?i)(Apache|\\bMIT\\b|\\bBSD\\b|\\bISC\\b|\\bzlib\\b|opensource\\.org/licenses/(MIT|BSD|ISC|Zlib))"
)

data class PomLicense(val name: String, val url: String)
data class PomInfo(val licenses: List<PomLicense>, val parent: Triple<String, String, String>?)

fun parsePom(file: File): PomInfo {
    val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = false
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    }
    val root = factory.newDocumentBuilder().parse(file).documentElement
    fun Element.child(tag: String): Element? {
        val nodes = childNodes
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            if (node is Element && node.tagName == tag) return node
        }
        return null
    }
    fun Element.text(tag: String): String = child(tag)?.textContent?.trim().orEmpty()

    val licenses = root.child("licenses")?.let { block ->
        val nodes = block.getElementsByTagName("license")
        (0 until nodes.length).map { nodes.item(it) as Element }
            .map { PomLicense(it.text("name"), it.text("url")) }
    }.orEmpty()
    val parent = root.child("parent")?.let { Triple(it.text("groupId"), it.text("artifactId"), it.text("version")) }
    return PomInfo(licenses, parent)
}

fun resolvePom(group: String, name: String, version: String): File? {
    val result = dependencies.createArtifactResolutionQuery()
        .forModule(group, name, version)
        .withArtifacts(MavenModule::class.java, MavenPomArtifact::class.java)
        .execute()
    return result.resolvedComponents
        .flatMap { it.getArtifacts(MavenPomArtifact::class.java) }
        .filterIsInstance<ResolvedArtifactResult>()
        .firstOrNull()?.file
}

fun licensesFor(group: String, name: String, version: String): List<PomLicense> {
    var coordinates: Triple<String, String, String>? = Triple(group, name, version)
    var depth = 0
    while (coordinates != null && depth < 8) {
        val pom = resolvePom(coordinates.first, coordinates.second, coordinates.third) ?: return emptyList()
        val info = parsePom(pom)
        if (info.licenses.isNotEmpty()) return info.licenses
        coordinates = info.parent
        depth++
    }
    return emptyList()
}

fun classify(licenses: List<PomLicense>): String {
    if (licenses.isEmpty()) return "UNKNOWN"
    val verdicts = licenses.map {
        val text = "${it.name} ${it.url}"
        when {
            forbiddenLicensePattern.containsMatchIn(text) -> "FORBIDDEN"
            allowedLicensePattern.containsMatchIn(text) -> "ALLOWED"
            else -> "UNKNOWN"
        }
    }
    return when {
        verdicts.all { it == "ALLOWED" } -> "ALLOWED"
        verdicts.all { it == "FORBIDDEN" } -> "FORBIDDEN"
        "FORBIDDEN" in verdicts -> "DUAL_WITH_FORBIDDEN"
        else -> "UNKNOWN"
    }
}

val checkThirdPartyLicenses by tasks.registering {
    group = "verification"
    description = "Fails the build when a runtime dependency has a forbidden or unknown license."
    val reportFile = layout.buildDirectory.file("reports/third-party-licenses/third-party-licenses.md")
    outputs.file(reportFile)
    outputs.upToDateWhen { false }
    notCompatibleWithConfigurationCache("Resolves dependency POMs at execution time")

    doLast {
        val checked = licenseCheckConfigurations.mapNotNull { configurations.findByName(it) }
        val components = checked
            .flatMap { config -> config.incoming.resolutionResult.allComponents }
            .mapNotNull { it.id as? ModuleComponentIdentifier }
            .distinctBy { "${it.group}:${it.module}:${it.version}" }
            .sortedBy { "${it.group}:${it.module}" }

        val rows = components.map { id ->
            val key = "${id.group}:${id.module}"
            val reviewed = manuallyReviewedLicenses[key]
            val licenses = if (reviewed != null) listOf(PomLicense(reviewed, "manual review")) else licensesFor(id.group, id.module, id.version)
            val verdict = if (reviewed != null) "ALLOWED (manual)" else classify(licenses)
            listOf(
                "$key:${id.version}",
                licenses.joinToString("; ") { it.name.ifEmpty { "(no name)" } }.ifEmpty { "(none found)" },
                licenses.joinToString("; ") { it.url.ifEmpty { "-" } }.ifEmpty { "-" },
                verdict
            )
        }

        val report = buildString {
            appendLine("# Third-party license report")
            appendLine()
            appendLine("Generated by `checkThirdPartyLicenses` from: ${checked.joinToString { it.name }.ifEmpty { "(no runtime classpath found)" }}")
            appendLine()
            appendLine("| Module | License | License URL | Verdict |")
            appendLine("| --- | --- | --- | --- |")
            rows.forEach { appendLine("| ${it.joinToString(" | ") { cell -> cell.replace("|", "\\|") }} |") }
        }
        val out = reportFile.get().asFile
        out.parentFile.mkdirs()
        out.writeText(report)
        logger.lifecycle("Third-party license report: ${out.absolutePath}")

        val failures = rows.filterNot { it[3].startsWith("ALLOWED") }
        if (failures.isNotEmpty()) {
            throw GradleException(
                "License policy violation (forbidden, dual-with-forbidden, or unknown license):\n" +
                    failures.joinToString("\n") { "  - ${it[0]}: ${it[1]} -> ${it[3]}" } +
                    "\nRemove the dependency, or review it manually and record it in THIRD_PARTY_NOTICES.md."
            )
        }
    }
}

tasks.matching { it.name == "preBuild" || it.name == "check" }.configureEach {
    dependsOn(checkThirdPartyLicenses)
}
