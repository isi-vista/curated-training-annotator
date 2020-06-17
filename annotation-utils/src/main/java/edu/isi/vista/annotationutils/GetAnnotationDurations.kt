package edu.isi.vista.annotationutils

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import edu.isi.nlp.parameters.serifstyle.SerifStyleParameterFileLoader
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * Script for getting the annotation time information from event.log files
 *
 * For this script to work, the event.log files should be saved in
 * their respective exported project files
 * (`.../exported/Event.Type-user_name/event.log`).
 * This should be the case if `ExportAnnotations` was run previously.
 *
 * The output is a .json file containing a list of each annotator's
 * projects and the estimated amount of time that they have spent
 * on each one, with the times written in both seconds
 * and an hours:minutes:seconds format.
 *
 * The durations are estimated based on the gaps of time between
 * user "events" on Inception documents. Relatively long gaps
 * are not counted in the total, assuming these indicate breaks.
 *
 * This takes a parameter file with two required parameters:
 * <ul>
 *   <li> `exportAnnotationRoot` - the output directory of ExportAnnotations </li>
 *   <li> `timeReportRoot` - the location where time report will be saved </li>
 * </ul>
 *
 */

class GetAnnotationDurations {
    companion object {
        fun main(argv: Array<String>) {
            if (argv.size != 1) {
                throw RuntimeException("Expected a single argument: a parameter file")
            }
            // Load parameters
            val paramsLoader = SerifStyleParameterFileLoader.Builder().build()
            val params = paramsLoader.load(File(argv[0]))
        }
        fun getDurations(params: edu.isi.nlp.parameters.Parameters) {
            val exportedAnnotationRoot = params.getExistingDirectory("exportedAnnotationRoot")
            val timeReportRoot = params.getCreatableDirectory("timeReportRoot")

            // Get current date information
            val currentDate = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            val formatter = SimpleDateFormat("yyyy-MM-dd")
            val thisDate = formatter.format(currentDate.getTime())

            // Make objects for reading, parsing, and writing json:
            val objectMapper = ObjectMapper()
            val prettyPrinter = objectMapper.writerWithDefaultPrettyPrinter()

            val allProjectInfo = mutableListOf<ProjectInfo>()

            // Go through each exported Inception project
            exportedAnnotationRoot.walk().filter {it.isDirectory}.forEach { projectDir ->
                val projectName = projectDir.name
                val projectInfo = getProjectInfo(projectName)
                // Each project should have a file named `event.log` - this
                // stores the timestamps of each "event" made in the project.
                val eventLog = File(projectDir.toString(), EVENT_LOG)
                if (eventLog.exists() && projectInfo != null) {
                    val username = projectInfo.username
                    val eventType = projectInfo.eventType
                    logger.info { "Getting time $username spent on $eventType"}
                    // Read event.log
                    val jsonEvents = convertEventsToJson(eventLog)
                    // Get the total time the annotator spent on the project
                    val totalProjectTime = getTimeOnProject(jsonEvents, username)
                    projectInfo.annotationTime = totalProjectTime
                    allProjectInfo.add(projectInfo)
                } else if (!eventLog.exists()){
                    logger.info { "Skipping $projectName - no event.log found" }
                } else if (projectInfo == null) {
                    logger.info { "Skipping $projectName - could not identify username or event type" }
                } else {
                    logger.info { "Skipping $projectName"}
                }
            }
            // Write time data to output file
            val usersToProjectTimes = mutableMapOf<String, Map<String, Map<String, Any>>>()
            // Mapping of users to projects to times
            // These will be printed to the output file.
            // For now we are only outputting the times spent on each project;
            // individual document times may be added later if there is interest.
            // {"user_name": {"Event.Type": {"seconds": 120, "formatted": "0h:2m:0s"}}}
            val projectsByUser = allProjectInfo.groupBy { it.username }.toSortedMap()
            for (userItem in projectsByUser) {
                val user = userItem.component1()
                val userProjects = userItem.component2()
                val projectMap = userProjects.map {
                    it.eventType to mapOf<String, Any>(
                            "seconds" to it.annotationTime, "formatted" to it.formattedTime
                    )
                }.toMap().toSortedMap()
                usersToProjectTimes[user] = projectMap
            }
            val outfile = File(timeReportRoot, "totalAnnotationTimes-$thisDate.json")
            outfile.writeBytes(prettyPrinter.writeValueAsBytes(usersToProjectTimes))
            logger.info { "User annotation times written to $outfile" }
        }
    }
}

data class ProjectInfo(
        val username: String,
        val eventType: String,
        var annotationTime: Long
) {
    val formattedTime get() = secondsToHMS(annotationTime)
}

fun secondsToHMS(seconds: Long): String {
    val hours = seconds/3600
    val minutes = (seconds/60)%60
    val remainingSeconds = seconds%60
    return "${hours}h:${minutes}m:${remainingSeconds}s"
}

private fun getProjectInfo(projectName: String): ProjectInfo? {
    var username: String? = null
    var eventType: String? = null
    var patternMatch: MatchResult? = null
    val projectNamePattern = if (projectName.startsWith("ACE")) {
        // ACE project name format: ACE-Hyphenated.Event-Type-user_name
        Regex(pattern = """(ACE-[a-zA-Z]*\.[a-zA-Z]*-?[a-zA-Z]*?)-(.*)""")
    } else if (projectName.startsWith("CORD19")) {
        // CORD-19 project name format:
        // CORD19-RelationType-user_name
        Regex(pattern = """(CORD19-[a-zA-Z]*)-(.*)""")
    } else {
        // gigaword project name format:
        // optionallanguage-Standard.EventType-user_name
        Regex(pattern = """([a-z]*?-?.*)-(.*)""")
    }
    if (projectNamePattern.containsMatchIn(projectName)) {
        patternMatch = projectNamePattern.find(projectName)
    }
    if (patternMatch != null) {
        eventType = patternMatch.groups[1]!!.value
        username = patternMatch.groups[2]!!.value
    }
    return if (username != null && eventType != null) {
        ProjectInfo(username, eventType, 0)
    } else {
        null
    }
}

private fun convertEventsToJson(log: File): List<JsonNode> {
    // Each line in an event.log represents an action in
    // Inception, and these can be converted
    // to JSON objects.
    val eventObjectMapper = ObjectMapper()
    val logEvents = log.readLines()
    return logEvents.map {eventObjectMapper.readTree(it)}
}

private fun getTimeOnProject(logEvents: List<JsonNode>, username: String): Long {
    // Get times (in seconds) spent in each document by running
    // through each Inception event recorded in event.log
    var currentDocument: String? = null  // some events have no document field
    var previousTime: Long = 0
    var documentTimeElapsed: Long = 0
    val documentTimeMap = mutableMapOf<String, Long>().withDefault { 0 }
    for (event in logEvents) {
        val documentName = event.get("document_name")?.toString()?.removeSurrounding("\"")
        val user = event.get("user")?.toString()?.removeSurrounding("\"")
        // Only deal with events that have a "document_name" field
        // and were completed by the user
        // (admin monitoring activity also gets recorded)
        if (documentName != null && user == username) {
            // Timestamps are in Unix time (milliseconds)
            val timestamp = event.get("created").toString().toLong()
            val timeSinceLastEvent = timestamp - previousTime
            if (previousTime == 0.toLong()) {
                // This is the first event in the log
                currentDocument = documentName
            }
            // If there is a relatively long gap between events,
            // it may suggest that the annotator took a break.
            // Check if the time elapsed since the last event
            // is less than 2 minutes (120,000 milliseconds) -
            // else, don't add it to the overall time spent
            // on the document.
            if (timeSinceLastEvent < 120000) {
                documentTimeElapsed += timeSinceLastEvent
            }
            if (documentName != currentDocument) {
                // The user has entered a new document.
                // Record the time elapsed and "restart the timer."
                val previousDocumentTime = documentTimeMap[currentDocument.toString()]
                if (previousDocumentTime != null) {
                    documentTimeMap[currentDocument.toString()] = previousDocumentTime + documentTimeElapsed
                } else {
                    documentTimeMap[currentDocument.toString()] = documentTimeElapsed
                }
                documentTimeElapsed = 0
                currentDocument = documentName
            }
            // Finished with this event;
            // prepare for the next one.
            previousTime = timestamp
        }
    }
    // All events in this Inception project have been processed.
    // Sum up the times from each document to get the total time
    // spent on this project.
    return documentTimeMap.map { it.value }.sum()/1000
}
