package edu.isi.vista.annotationutils

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import edu.isi.nlp.parameters.serifstyle.SerifStyleParameterFileLoader
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Script for getting the annotation time information from event.log files
 *
 * This takes a parameter file with two required parameters:
 * <ul>
 *   <li> `exportAnnotationRoot` - the output of ExportAnnotations </li>
 *   <li> `timeReportRoot` - the location where time reports will be saved </li>
 * </ul>
 *
 */

fun main(argv: Array<String>) {
    if (argv.size != 1) {
        throw RuntimeException("Expected a single argument: a parameter file")
    }
    val paramsLoader = SerifStyleParameterFileLoader.Builder().build()
    val params = paramsLoader.load(File(argv[0]))

    val exportedAnnotationRoot = params.getExistingDirectory("exportedAnnotationRoot")
    val timeReportRoot = params.getCreatableDirectory("timeReportRoot").toPath()

    // Make objects for reading, parsing, and writing json:
    val objectMapper = ObjectMapper()
    val prettyPrinter = objectMapper.writerWithDefaultPrettyPrinter()

    // Mapping of users to projects to times
    // These will be printed to the output file.
    // {"gabbard": {"Conflict.Attack": {"seconds": 120, "formatted": "0h:2m:0s"}}}
    val durationMap = mutableMapOf<String, MutableMap<String, Map<String, Any>>>().withDefault {
        mutableMapOf<String, Map<String, Any>>()
    }

    exportedAnnotationRoot.walk().filter {it.isDirectory}.forEach { projectDir ->
        val projectName = projectDir.name
        logger.info { "ProjectName: $projectName"}
        if (projectName != exportedAnnotationRoot.name) {
            var eventType: String? = null
            var username: String? = null
            if (projectName.startsWith("ACE")) {
                // Example ACE project name: ACE-Business.Declare-Bankruptcy-gabbard
                val acePattern = Regex(pattern = """(ACE-[a-zA-Z]*\.[a-zA-Z]*-?[a-zA-Z]*?)-(.*)""")
                if (acePattern.containsMatchIn(projectName)) {
                    val aceMatch = acePattern.find(projectName)
                    eventType = aceMatch!!.groups[1]!!.value
                    username = aceMatch.groups[2]!!.value
                }
            }
            else {
                logger.info { "Script still under construction!" }
            }
            val eventLog = File(projectDir.toString(), "event.log")
            logger.info { "logfile = $eventLog" }
            if (eventLog.exists() && eventType != null && username != null) {
                durationMap[username] = mutableMapOf(eventType to mapOf())
                // Read event.log
                val jsonEvents = getEventsAsJson(eventLog)


                // Get times spent in each document by running
                // through each Inception event
                var currentDocument: String? = null  // some events have no document field
                var previousTime: Long = 0
                var documentTimeElapsed: Long = 0
                val documentTimeMap = mutableMapOf<String, Long>().withDefault { 0 }
                for (event in jsonEvents) {
                    val documentName = event.get("document_name")?.toString()?.removeSurrounding("\"")
                    val annotator = event.get("annotator")?.toString()?.removeSurrounding("\"")
                    // Only deal with events that have a "document_name" field
                    // and involve the current annotator
                    if (documentName != null && annotator == username) {
                        logger.info {"On $username's $documentName"}
                        val timestamp = event.get("created").toString().toLong()
                        val timeSinceLastEvent = timestamp - previousTime
                        if (previousTime == 0.toLong()) {
                            // This is the first event in the log
                            currentDocument = documentName
                        }
                        // Check if the time elapsed since the last
                        // event makes is less than 5 minutes
                        // (300,000 milliseconds) -
                        // else don't add it to the
                        // overall time spent on the document.
                        if (timeSinceLastEvent < 300000) {
                            documentTimeElapsed += timeSinceLastEvent
                        }
                        if (documentName != currentDocument) {
                            // The user has entered a new document.
                            // Record the time elapsed and restart the "timer"
                            logger.info { "Total time elapsed in $currentDocument: $documentTimeElapsed" }
                            val previousDocumentTime = documentTimeMap[currentDocument.toString()]
                            logger.info { "Previous entry for $currentDocument: $previousDocumentTime"}
                            if (previousDocumentTime != null) {
                                documentTimeMap[currentDocument.toString()] = previousDocumentTime + documentTimeElapsed
                            } else {
                                documentTimeMap[currentDocument.toString()] = documentTimeElapsed
                            }
                            logger.info { "Document times now: $documentTimeMap" }
                            documentTimeElapsed = 0
                            currentDocument = documentName
                        }
                        previousTime = timestamp
                    }
                }
                // All events in this Inception project have been processed.
                // Sum up the times from each time to get the total time
                // spent on this project.
                logger.info { "Document times now: $documentTimeMap" }
                val totalProjectTime = documentTimeMap.map { it.value }.sum()
                logger.info { "Total project time: $totalProjectTime" }
                // Convert milliseconds to a human-readable format
                val hoursMinutesSeconds = millisecondsToHMS(totalProjectTime)

                durationMap[username]!![eventType] = mapOf(
                        "seconds" to totalProjectTime/1000, "formatted" to hoursMinutesSeconds
                )

            } else {
                logger.info { "No event.log for $projectName" }
            }
        }
    }
    logger.info { "Duration map: $durationMap"}
}

private fun getProjectInfo(projectName: String): Pair<String?, String?> {
    var username: String? = null
    var eventType: String? = null
    if (projectName.startsWith("ACE")) {
        // Example ACE project name: ACE-Business.Declare-Bankruptcy-gabbard
        val acePattern = Regex(pattern = """(ACE-[a-zA-Z]*\.[a-zA-Z]*-?[a-zA-Z]*?)-(.*)""")
        if (acePattern.containsMatchIn(projectName)) {
            val aceMatch = acePattern.find(projectName)
            eventType = aceMatch!!.groups[1]!!.value
            username = aceMatch.groups[2]!!.value
        }
    }
    // TODO: add other filename patterns
    return Pair(username, eventType)
}


private fun getEventsAsJson(log: File): List<JsonNode> {
    // Each line in an event.log represents an action in
    // Inception, and these can be converted
    // to JSON objects.
    val eventObjectMapper = ObjectMapper()
    val logEvents = log.readLines()
    return logEvents.map {eventObjectMapper.readTree(it)}
}

private fun millisecondsToHMS(milliseconds: Long): String {
    val hours = TimeUnit.MILLISECONDS.toHours(milliseconds)
    val millisecondsForMinutes = milliseconds - (hours * 3600000)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(millisecondsForMinutes)
    val millisecondsForSeconds = millisecondsForMinutes - (minutes * 60000)
    val seconds = millisecondsForSeconds/1000
    return "${hours}h:${minutes}m:${seconds}s"
}