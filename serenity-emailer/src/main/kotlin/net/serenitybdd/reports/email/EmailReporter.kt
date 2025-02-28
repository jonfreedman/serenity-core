package net.serenitybdd.reports.email

import net.serenitybdd.reports.model.*
import net.serenitybdd.reports.email.templates.ThymeleafTemplateEngine
import net.serenitybdd.reports.io.testOutcomesIn
import net.serenitybdd.reports.model.averageDurationOf
import net.serenitybdd.reports.model.formattedDuration
import net.serenitybdd.reports.model.maxDurationOf
import net.thucydides.core.guice.Injectors
import net.thucydides.core.model.TestResult.*
import net.thucydides.core.reports.ExtendedReport
import net.thucydides.core.reports.TestOutcomes
import net.thucydides.core.util.EnvironmentVariables
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Path
import java.time.Duration
import java.time.ZonedDateTime

/**
 * Generate an HTML summary report from a set of Serenity test outcomes in a given directory.
 */
class EmailReporter(val environmentVariables: EnvironmentVariables) : ExtendedReport {
    override fun getName(): String = "email"

    private val LOGGER = LoggerFactory.getLogger(SerenityEmailReport::class.java)

    constructor() : this(Injectors.getInjector().getProvider<EnvironmentVariables>(EnvironmentVariables::class.java).get())

    companion object {
        val DISPLAYED_TEST_RESULTS = listOf(SUCCESS, PENDING, IGNORED, FAILURE, ERROR, COMPROMISED)
    }

    /**
     * Generate an HTMLsummary report using the json files in the specified directory
     */
    override fun generateReportFrom(sourceDirectory: Path): File {

        LOGGER.info("GENERATING EMAIL REPORT")

        // Fetch the test outcomes
        val testOutcomes = testOutcomesIn(sourceDirectory).filteredByEnvironmentTags()

        // Prepare the parameters
        val outputDirectory = SerenityEmailReport.outputDirectory().configuredIn(environmentVariables)
        val fields = templateFields(environmentVariables, testOutcomes)

        // Write the report
        return writeHtmlReportTo(outputDirectory, fields)
    }

    private fun writeHtmlReportTo(outputDirectory: Path, fields: Map<String, Any>): File {
        val outputFile = newOutputFileIn(outputDirectory)
        val writer = outputFile.bufferedWriter()

        writer.use {
            val template = SerenityEmailReport.template().configuredIn(environmentVariables)
            ThymeleafTemplateEngine().merge(template, fields, writer)
        }

        return outputFile
    }

    private fun templateFields(environmentVariables: EnvironmentVariables,
                               testOutcomes: TestOutcomes): Map<String, Any> {
        val reportTitle = SerenityEmailReport.reportTitle().configuredIn(environmentVariables)
        val reportLink = SerenityEmailReport.reportLink().configuredIn(environmentVariables)
        val scoreboardSize = SerenityEmailReport.scoreboardSize().configuredIn(environmentVariables)
        val customReportFields = CustomReportFields(environmentVariables)
        val tagTypes = SerenityEmailReport.tagTypes().configuredIn(environmentVariables)
        val tagCategoryTitle = SerenityEmailReport.tagCategoryTitle().configuredIn(environmentVariables)
        val showFullTestResults = SerenityEmailReport.showFullTestResults().configuredIn(environmentVariables)

        val fields = hashMapOf(
                "testOutcomes" to testOutcomes,
                "showFullTestResults" to showFullTestResults,
                "report" to ReportInfo(
                        title = reportTitle,
                        link = reportLink,
                        tagCategoryTitle = tagCategoryTitle,
                        version = environmentVariables.getProperty("project.version", ""),
                        date = testOutcomes.startTime.orElse(ZonedDateTime.now()).toLocalDateTime()
                ),
                "results" to TestResultSummary(
                        totalCount = testOutcomes.total,
                        countByResult = countByResultLabelFrom(testOutcomes),
                        percentageByResult = percentageByResultLabelFrom(testOutcomes),
                        totalTestDuration = formattedDuration(Duration.ofMillis(testOutcomes.duration)),
                        clockTestDuration = formattedDuration(clockDurationOf(testOutcomes.outcomes)),
                        averageTestDuration = formattedDuration(averageDurationOf(testOutcomes.outcomes)),
                        maxTestDuration = formattedDuration(maxDurationOf(testOutcomes.outcomes)),
                        minTestDuration = formattedDuration(minDurationOf(testOutcomes.outcomes))
                ),
                "failuresByFeature" to FailuresByFeature.from(testOutcomes),
                "resultsByFeature" to TestResultsByFeature.from(testOutcomes),
                "frequentFailures" to FrequentFailures.from(testOutcomes).withMaxOf(scoreboardSize),
                "unstableFeatures" to UnstableFeatures.from(testOutcomes).withMaxOf(scoreboardSize),
                "coverage" to TagCoverage.from(testOutcomes).forTagTypes(tagTypes),
                "customFields" to customReportFields.fieldNames,
                "customFieldValues" to customReportFields.values,
                "formatted" to Formatted(),
                "css" to CSSFormatter()
        )
        return fields
    }

    fun outputFileIn(outputDirectory: Path): File {
        return outputDirectory.resolve("serenity-summary.html").toFile()
    }

    fun newOutputFileIn(outputDirectory: Path): File {
        outputDirectory.toFile().mkdirs()

        val outputFile = outputFileIn(outputDirectory)
        outputFile.createNewFile()

        return outputFile
    }
}