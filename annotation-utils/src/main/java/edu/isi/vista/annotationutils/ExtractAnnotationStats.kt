package edu.isi.vista.annotationutils

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import edu.isi.nlp.parameters.serifstyle.SerifStyleParameterFileLoader
import kotlinx.html.*
import kotlinx.html.stream.appendHTML
import org.nield.kotlinstatistics.countBy
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * This program is designed to collect the annotation
 * statistics from the exported JSON, and they will get pushed along with
 * the updated annotation files.
 */
class ExtractAnnotationStats {
    companion object {
        fun main(argv: Array<String>) {
            if (argv.size != 1) {
                throw RuntimeException("Expected a single argument: a parameter file")
            }
            val paramsLoader = SerifStyleParameterFileLoader.Builder()
                    .interpolateEnvironmentalVariables(true).build()
            val params = paramsLoader.load(File(argv[0]))
            extractStats(params)
        }
        fun extractStats(params: edu.isi.nlp.parameters.Parameters) {
            val exportAnnotationRoot = params.getExistingDirectory("exportedAnnotationRoot")
            val statisticsDirectory = params.getExistingDirectory("statisticsDirectory")
            val currentDate = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            val formatter = SimpleDateFormat("yyyy-MM-dd")
            val thisDate = formatter.format(currentDate.getTime())

            val documentsWithEventAnnotations: MutableList<DocumentAnnotation> = collectStats(exportAnnotationRoot)

            // From documentsWithEventAnnotations, get TOTAL, TOTAL PER USER and TOTAL PER EVENT TYPE
            val totalAnnotations = documentsWithEventAnnotations.size
            val annotationsByUser = documentsWithEventAnnotations.countBy { it.user }
            val annotationsByEventType = documentsWithEventAnnotations.countBy { it.eventType }
            // For better organization
            val sortedUsers = annotationsByUser.toSortedMap()
            val sortedEventTypes = annotationsByEventType.toSortedMap()
            val newAnnotationStats = AnnotationStats(totalAnnotations, sortedUsers, sortedEventTypes)

            // Load the annotation statistics from the last run
            val previousStatsReport: StatsReport? = locatePreviousStats(statisticsDirectory)
            val previousAnnotationStats: AnnotationStats
            var annotationDiffs: AnnotationStats? = null
            if (previousStatsReport != null) {
                previousAnnotationStats = loadPreviousStats(previousStatsReport.report)
                annotationDiffs = getDiffs(newAnnotationStats, previousAnnotationStats)
            }
            else {
                logger.info {"No previous report found. Numbers of new annotations will not be printed."}
            }

            // Convert values to html
            val htmlStatsReport = StatsReport(File(statisticsDirectory, "StatsReport$thisDate.html"), thisDate)
            statsToHTML(htmlStatsReport, newAnnotationStats, previousStatsReport, annotationDiffs)
            // Convert values to json
            val jsonStatsReport = StatsReport(File(statisticsDirectory, "StatsReport$thisDate.json"), thisDate)
            statsToJSON(jsonStatsReport, newAnnotationStats)
        }

        /**
         * Go through each file in the exported JSON directory and save info on
         * each document that contains annotations.
         * Eventually we will want to count the actual number of annotations.
         */
        fun collectStats(dir: File): MutableList<DocumentAnnotation> {
            val docsList: MutableList<DocumentAnnotation> = mutableListOf<DocumentAnnotation>()
            dir.walk()
                    .filterNot {
                        it == dir ||
                                it.absolutePath.contains("copy_of_") ||
                                it.absolutePath.contains("sandbox") ||
                                it.absolutePath.contains("gigaword")
                    }
                    .forEach {

                        if (it.isFile) {
                            val folder = it.parent
                            logger.info { "Folder: $folder"}
                            // Get the username from the parent directory
                            // by finding the characters after the pattern "type.subtype-"
                            // e.g. Conflict.Attack-(gabbard)
                            // An ACE file could have two directory name formats:
                            // 1. ACE-Event.Type-username
                            // 2. ACE-Hyphenated.Event-Type-username
                            val aceHyphenatedPattern = Regex(pattern = """(ACE-\w+\.\w+-\w+)-(\w+)""")
                            val user: String?
                            val eventType: String?
                            if (folder.contains(aceHyphenatedPattern)) {
                                eventType = aceHyphenatedPattern
                                        .find(input = folder)!!.groupValues[1]
                                user = aceHyphenatedPattern
                                        .find(input = folder)!!.groupValues[2]
                            }
                            else {
                                val userPattern = Regex(pattern = """\.\w+-(\w+)""")
                                user = userPattern
                                        .find(input = folder)!!.groupValues[1]
                                // Get the event type from the parent directory
                                // by finding the characters before the pattern "-username"
                                // e.g. (Conflict.Attack)-gabbard
                                // Some directory names include an additional word or phrase (\w*?\.?)
                                // or a language indicator (w*?-?)
                                val eventTypePattern = Regex(pattern = """\w+?-?\w+\.?\w+\.\w+""")
                                eventType = eventTypePattern
                                        .find(input = folder)!!.value
                            }
                            if (user.isBlank() || eventType.isBlank()) {
                                throw RuntimeException(
                                        "Missing annotation information (user/subtype) for $it")
                            }
                            val documentName: String = it.name
                            // TODO: count events rather than annotated documents
                            // https://github.com/isi-vista/curated-training-annotator/issues/41
                            val jsonString: String = it.readText()
                            val regex = Regex(pattern = """"CTEventSpan" : \[""")
                            if (jsonString.contains(regex)) {
                                docsList.add(DocumentAnnotation(documentName, user, eventType))
                            }
                        }
                    }
            return docsList
        }

        /**
         * Find all existing JSON files in the output directory and return
         * the most recent file; else return null
         */
        fun locatePreviousStats(statsDir: File): StatsReport? {
            return statsDir.walk().asSequence()
                    .filter { it.name.endsWith(".json") }
                    .map {
                        val datePattern = Regex(pattern = """\d{4}-\d{2}-\d{2}""")
                        val fileDate = datePattern
                                .find(input = it.name)?.value
                        if (fileDate != null) {
                            StatsReport(it, fileDate)
                        } else {
                            logger.debug("No date found for JSON file ${it.name}")
                            null
                        }
                    }
                    .filterNotNull()
                    .sortedBy {it.report.lastModified()}
                    .lastOrNull()
        }

        /**
         * Load the previous annotation statistics from the
         * most recently modified JSON file (identified from `locatePreviousStats`)
         */
        fun loadPreviousStats(statsFile: File): AnnotationStats {
            val mapper = jacksonObjectMapper()
            return mapper.readValue<AnnotationStats>(statsFile)
        }

        /**
         * Use the numbers in the new statistics and previous ones to get the differences
         */
        fun getDiffs(newStats: AnnotationStats, previousStats: AnnotationStats): AnnotationStats {
            val totalDiff = newStats.total - previousStats.total
            val userDiff = newStats.byUser
                    .filterKeys { previousStats.byUser.containsKey(it) }
                    .mapValues {
                        (user, newCount) ->
                        previousStats.byUser[user]?.let { newCount?.minus(it) }
                    }
            val eventTypeDiff = newStats.byEventType
                    .filterKeys { previousStats.byEventType.containsKey(it) }
                    .mapValues {
                        (eventType, newCount) ->
                        previousStats.byEventType[eventType]?.let {newCount?.minus(it) }
                    }

            return AnnotationStats(totalDiff, userDiff, eventTypeDiff)
        }

        /**
         * Write the updated annotation stats and the differences to an HTML file
         */
        fun statsToHTML(
                newStatsReport: StatsReport,
                newAnnotationStats: AnnotationStats,
                previousStatsReport: StatsReport?,
                annotationDiffs: AnnotationStats?
        ) {
            newStatsReport.report.printWriter().use{
                out -> out.appendHTML().html {
                head {
                    style {
                        +"""table {
                            font-family: arial, sans-serif;
                            border-collapse: collapse;
                            margin: 8px;
                        }

                        td, th {
                            border: 1px solid #000000;
                            text-align: left;
                            padding: 8px;
                        }"""
                    }
                }
                body {
                    h2 { +"Annotation Statistics - ${newStatsReport.date}"}
                    if (previousStatsReport != null) {
                        h4 { +"Includes new annotations since ${previousStatsReport.date}"}
                    }
                    table {
                        tr {
                            th {+"Total annotations"}
                            th {+"New annotations"}
                        }
                        tr {
                            td {+"${newAnnotationStats.total}"}
                            if (annotationDiffs == null) {
                                td{+"n/a"}
                            }
                            else {
                                td { +"${annotationDiffs.total}" }
                            }
                        }
                    }
                    table {
                        tr {
                            th {+"User"}
                            th {+"Annotations"}
                            th {+"New annotations"}
                        }
                        for (user in newAnnotationStats.byUser.keys) {
                            tr {
                                td {+"$user"}
                                td {+"${newAnnotationStats.byUser[user]}"}
                                if (annotationDiffs == null) {
                                    td {+"n/a"}
                                }
                                else if (annotationDiffs.byUser[user] == null) {
                                    td {+"n/a"}
                                }
                                else {
                                    td {+"${annotationDiffs.byUser[user]}"}
                                }
                            }
                        }
                    }
                    table {
                        tr {
                            th {+"Event type"}
                            th {+"Annotations"}
                            th {+"New annotations"}
                        }
                        for (eventType in newAnnotationStats.byEventType.keys) {
                            tr {
                                td {+"$eventType" }
                                td {+"${newAnnotationStats.byEventType[eventType]}"}
                                if (annotationDiffs == null) {
                                    td {+"n/a"}
                                }
                                else if (annotationDiffs.byEventType[eventType] == null) {
                                    td {+"n/a"}
                                }
                                else {
                                    td {+"${annotationDiffs.byEventType[eventType]}"}
                                }
                            }
                        }
                    }
                }
            }
            }
        }

        /**
         * Write updated annotation statistics to a JSON file
         */
        fun statsToJSON(
                newStatsReport: StatsReport,
                newAnnotationStats: AnnotationStats
        ) {
            val mapper = jacksonObjectMapper()
            val prettyprinter = mapper.writerWithDefaultPrettyPrinter()
            prettyprinter.writeValue(newStatsReport.report, newAnnotationStats)
        }
    }
}

data class DocumentAnnotation(val documentName: String, val user: String, val eventType: String)
data class EventSpan(val dependent: Int, val governor: Int, val relation_type: String)
data class AnnotationStats(val total: Int, val byUser: Map<String, Int?>, val byEventType: Map<String, Int?>)
data class StatsReport(val report: File, val date: String)
