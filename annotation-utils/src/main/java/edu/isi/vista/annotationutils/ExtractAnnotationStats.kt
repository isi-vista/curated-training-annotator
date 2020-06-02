package edu.isi.vista.annotationutils

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
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

            val sentencesWithEventAnnotations: MutableList<SentenceAnnotation> = collectStats(exportAnnotationRoot)

            // From sentencesWithEventAnnotations, got TOTAL, TOTAL PER USER, EVENT TYPE, AND CORPUS
            val positiveSentences = sentencesWithEventAnnotations.filter { !it.negativeExample }
            val negativeSentences = sentencesWithEventAnnotations.filter { it.negativeExample }
            val totalAnnotations = sentencesWithEventAnnotations.size
            val annotationsByUser = sentencesWithEventAnnotations.countBy { it.user }
            val annotationsByEventType = sentencesWithEventAnnotations.countBy { it.eventType }
            val positiveAnnotationsByCorpus = positiveSentences.countBy { it.corpus }
            val negativeAnnotationsByCorpus = negativeSentences.countBy { it.corpus }

            // For better organization
            val sortedUsers = annotationsByUser.toSortedMap()
            val sortedEventTypes = annotationsByEventType.toSortedMap()
            val sortedPositiveCorpora = positiveAnnotationsByCorpus.toSortedMap()
            val sortedNegativeCorpora = negativeAnnotationsByCorpus.toSortedMap()
            val newAnnotationStats = AnnotationStats(
                    totalAnnotations, sortedUsers, sortedEventTypes, sortedPositiveCorpora, sortedNegativeCorpora
            )

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
        fun collectStats(dir: File): MutableList<SentenceAnnotation> {
            // val docsList: MutableList<DocumentAnnotation> = mutableListOf<DocumentAnnotation>()
            val sentenceList: MutableList<SentenceAnnotation> = mutableListOf<SentenceAnnotation>()
            dir.walk()
                    .filterNot {
                        it == dir ||
                                it.absolutePath.contains("copy_of_") ||
                                it.absolutePath.contains("sandbox") ||
                                it.absolutePath.contains("gigaword") ||
                                it.absolutePath.contains("DS_Store")
                    }
                    .forEach {

                        if (it.isFile) {
                            logger.info { "Now processing $it" }
                            val folder = it.parent
                            // Get the username from the parent directory
                            // by finding the characters after the pattern "type.subtype-"
                            // e.g. Conflict.Attack-(gabbard)
                            // An ACE file could have two directory name formats:
                            // 1. ACE-Event.Type-username
                            // 2. ACE-Hyphenated.Event-Type-username
                            val aceHyphenatedPattern = Regex(pattern = """(ACE-\w+\.\w+-\w+)-(\w+)""")
                            val user: String?
                            val eventType: String?
                            // Our current corpora are
                            // English, Russian, Spanish, ACE, and CORD-19
                            val corpus: String?
                            if (folder.contains(aceHyphenatedPattern)) {
                                eventType = aceHyphenatedPattern
                                        .find(input = folder)!!.groupValues[1]
                                user = aceHyphenatedPattern
                                        .find(input = folder)!!.groupValues[2]
                                corpus = "ACE"
                            } else if (folder.contains("CORD19")) {
                                val cord19Pattern = Regex(pattern = """(CORD19-\w+)-(\w+)""")
                                eventType = cord19Pattern.find(folder)!!.groupValues[1]
                                user = cord19Pattern.find(folder)!!.groupValues[2]
                                corpus = "CORD-19"
                            } else {
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
                                corpus = if (folder.contains("russian")) {
                                    "Russian"
                                } else if (folder.contains("spanish")) {
                                    "Spanish"
                                } else {
                                    "English"
                                }
                            }
                            if (user.isBlank() || eventType.isBlank()) {
                                throw RuntimeException(
                                        "Missing annotation information (user/subtype) for $it")
                            }
                            val documentName: String = it.name
                            // TODO: count events rather than annotated documents
                            // https://github.com/isi-vista/curated-training-annotator/issues/41
                            val jsonTree = ObjectMapper().readTree(it) as ObjectNode
                            val documentSpans = jsonTree["_referenced_fss"]
                            val documentSentences = jsonTree["_views"]["_InitialView"]["Sentence"]
                            val documentRelations = jsonTree["_views"]["_InitialView"]["CTEventSpanType"]
                            // We determine the triggers because these indicate if the
                            // sentence is negative, and it's faster than
                            // running through each span.
                            var documentTriggers = listOf<JsonNode>()
                            if (documentRelations == null) {
                                // Try CTEventSpan
                                val ctEventSpan = jsonTree["_views"]["_InitialView"]["CTEventSpan"]
                                if (ctEventSpan != null){
                                    documentTriggers = ctEventSpan.toList()
                                }
                            } else {
                                documentTriggers = getPrimaryTriggers(documentRelations, documentSpans)
                            }
                            // Keep track of sentences that have already been counted.
                            // Multiple triggers may appear in the same sentence, so we
                            // want to avoid duplicate instances.
                            // This will be populated with sentence IDs for this document.
                            val documentAnnotatedSentences = mutableSetOf<String>()
                            for (trigger in documentTriggers) {
                                val triggerBegin = if (trigger["begin"] == null) {
                                    // Some trigger objects may not have a "begin" field.
                                    // This is because in some corpus documents,
                                    // the first token is markable, so the "sofa"
                                    // serves as the starting index.
                                    trigger["sofa"].toString().toInt()
                                } else {
                                    trigger["begin"].toString().toInt()
                                }
                                for (sentence in documentSentences) {
                                    val sentenceBegin = if (sentence["begin"] == null) {
                                        sentence["sofa"].toString().toInt()
                                    } else {
                                        sentence["begin"].toString().toInt()
                                    }
                                    val sentenceEnd = sentence["end"].toString().toInt()
                                    val sentenceID = "$documentName-$sentenceBegin"
                                    // If the given trigger is in a sentence, add the sentence
                                    // to the sentence list, including whether it's a negative example
                                    if (
                                            (triggerBegin >= sentenceBegin)
                                            and (trigger["end"].toString().toInt() <= sentenceEnd)
                                    ) {
                                        if (documentAnnotatedSentences.contains(sentenceID)) {
                                            break
                                        } else if (trigger["negative_example"].toString() == "true") {
                                            sentenceList.add(
                                                    SentenceAnnotation(
                                                            sentenceID, user, eventType, corpus, true
                                                    )
                                            )
                                        } else {
                                            sentenceList.add(
                                                    SentenceAnnotation(
                                                            sentenceID, user, eventType, corpus, false
                                                    )
                                            )
                                        }
                                        documentAnnotatedSentences.add(sentenceID)
                                        // We've found the sentence of our trigger
                                        break
                                    }
                                }
                            }
                        }
                    }
            return sentenceList
        }

        /**
         * Determine which spans are trigger spans
         */
        private fun getPrimaryTriggers(relations: JsonNode, spans: JsonNode): List<JsonNode> {
            val dependents: MutableSet<String> = mutableSetOf()
            val governors: MutableSet<String> = mutableSetOf()
            val primaryTriggers: MutableSet<JsonNode> = mutableSetOf()
            for (relation in relations) {
                val relationDependent = relation["Dependent"].toString()
                val relationGovernor = relation["Governor"].toString()
                // The only time a governor is also a primary trigger
                // is when it serves as an argument of itself.
                // It may actually be a secondary trigger, but in that case
                // it should share the same sentence as the
                // primary one, so filtering these out is low-priority.
                // We want to ensure that such primary triggers aren't excluded.
                if (relationDependent == relationGovernor) {
                    primaryTriggers.add(spans[relationDependent])
                } else {
                    dependents.add(relationDependent)
                    governors.add(relationGovernor)
                }
            }
            for (dependent in dependents) {
                // A trigger is a primary trigger if it is
                // never a governor (or case mentioned above)
                if (!governors.contains(dependent)) {
                    primaryTriggers.add(spans[dependent])
                }
            }
            return primaryTriggers.toList()
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
            val posCorpusDiff = if (previousStats.byCorpusPositive.isNullOrEmpty()) {
                newStats.byCorpusPositive!!
            } else {
                newStats.byCorpusPositive!!
                        .filterKeys { previousStats.byCorpusPositive.containsKey(it) }
                        .mapValues { (corpus, newCount) ->
                            previousStats.byCorpusPositive[corpus]?.let { newCount?.minus(it) }
                        }
            }
            val negCorpusDiff = if (previousStats.byCorpusNegative.isNullOrEmpty()) {
                newStats.byCorpusNegative!!
            } else {
                newStats.byCorpusNegative!!
                        .filterKeys { previousStats.byCorpusNegative.containsKey(it) }
                        .mapValues { (corpus, newCount) ->
                            previousStats.byCorpusNegative[corpus]?.let { newCount?.minus(it) }
                        }
            }

                return AnnotationStats(totalDiff, userDiff, eventTypeDiff, posCorpusDiff, negCorpusDiff)
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
                        h4 { +"Includes new annotated sentences since ${previousStatsReport.date}"}
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
                    if (newAnnotationStats.byCorpusPositive != null) {
                        table {
                            tr {
                                th { +"Project" }
                                th { +"Total sentences (+)" }
                                th { +"New sentences (+)" }
                            }
                            for (corpus in newAnnotationStats.byCorpusPositive.keys) {
                                tr {
                                    td { +"$corpus" }
                                    td { +"${newAnnotationStats.byCorpusPositive[corpus]}" }
                                    if (annotationDiffs == null) {
                                        td { +"n/a" }
                                    } else if (annotationDiffs.byCorpusPositive.isNullOrEmpty()) {
                                        td { +"${newAnnotationStats.byCorpusPositive[corpus]}" }
                                    } else {
                                        td { +"${annotationDiffs.byCorpusPositive[corpus]}" }
                                    }
                                }
                            }
                        }
                    }
                    if (newAnnotationStats.byCorpusNegative != null) {
                        table {
                            tr {
                                th { +"Project" }
                                th { +"Total sentences (-)" }
                                th { +"New sentences (-)" }
                            }
                            for (corpus in newAnnotationStats.byCorpusNegative.keys) {
                                tr {
                                    td { +"$corpus" }
                                    td { +"${newAnnotationStats.byCorpusNegative[corpus]}" }
                                    if (annotationDiffs == null) {
                                        td { +"n/a" }
                                    } else if (annotationDiffs.byCorpusNegative.isNullOrEmpty()) {
                                        td { +"${newAnnotationStats.byCorpusNegative[corpus]}" }
                                    } else {
                                        td { +"${annotationDiffs.byCorpusNegative[corpus]}" }
                                    }
                                }
                            }
                        }
                    }
                    table {
                        tr {
                            th {+"User"}
                            th {+"Sentences"}
                            th {+"New sentences"}
                        }
                        for (user in newAnnotationStats.byUser.keys) {
                            tr {
                                td {+"$user"}
                                td {+"${newAnnotationStats.byUser[user]}"}
                                if (annotationDiffs == null) {
                                    td {+"n/a"}
                                }
                                else if (annotationDiffs.byUser[user] == null) {
                                    td {+"${newAnnotationStats.byUser[user]}"}
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
                            th {+"Sentences"}
                            th {+"New sentences"}
                        }
                        for (eventType in newAnnotationStats.byEventType.keys) {
                            tr {
                                td {+"$eventType" }
                                td {+"${newAnnotationStats.byEventType[eventType]}"}
                                if (annotationDiffs == null) {
                                    td {+"n/a"}
                                }
                                else if (annotationDiffs.byEventType[eventType] == null) {
                                    td {+"${newAnnotationStats.byEventType[eventType]}"}
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

data class SentenceAnnotation(
        val sentence_id: String,
        val user: String,
        val eventType: String,
        val corpus: String,
        val negativeExample: Boolean
)
data class AnnotationStats(
        val total: Int,
        val byUser: Map<String, Int?>,
        val byEventType: Map<String, Int?>,
        val byCorpusPositive: Map<String, Int?>?,
        val byCorpusNegative: Map<String, Int?>?
)
data class StatsReport(val report: File, val date: String)
