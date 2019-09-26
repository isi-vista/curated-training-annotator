package edu.isi.vista.annotationutils

import edu.isi.nlp.parameters.serifstyle.SerifStyleParameterFileLoader
import kotlinx.html.*
import kotlinx.html.stream.appendHTML
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
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

            val annotationSheet: MutableList<Annotation> = arrayListOf()

            // Go through each file in each user's folder and collect the stats
            var user: String? = null
            var subtype: String? = null
            File(exportAnnotationRoot.toString()).walk().forEach {
                // Skip irrelevant dirs
                if (it == File(exportAnnotationRoot.toString()) ||
                        it.name.contains("""sandbox""") ||
                        it.name.contains("""gigaword""")) {
                }
                else if (it.isDirectory) {
                    val folder = it.name
                    // Collect username, subtype, and subSubtype
                    user = Regex(pattern = """\.\w*-(\w*)""")
                            .find(input = folder.toString())!!.groupValues[1]
                    subtype = Regex(pattern = """(\w*\.\w*)-""")
                            .find(input = folder.toString())!!.groupValues[1]
                }
                else if (it.isFile) {
                    if (user.isNullOrBlank() || subtype.isNullOrBlank()) {
                        throw RuntimeException("Missing annotation information (user/subtype)")
                    }
                    val documentName: String = it.toString()
                    // TODO: count events rather than annotated documents
                    // This is sort of a placeholder for a better method
                    val jsonString: String = File(it.toString()).readText(Charsets.UTF_8)
                    if (jsonString.contains(Regex(""""CTEventSpan" : \["""))) {
                        annotationSheet.add(Annotation(documentName, user!!, subtype!!))
                    }
                }
            }
            // From annotationSheet, get TOTAL, TOTAL PER USER/SUBTYPE/SUBSUBTYPE
            val totalAnnotations = annotationSheet.size
            val annotationsPerUser = annotationSheet.countBy { it.user }
            val annotationsPerSubtype = annotationSheet.countBy { it.subtype }
            // For better organization
            val sortedUsers = annotationsPerUser.toSortedMap()
            val sortedSubtypes = annotationsPerSubtype.toSortedMap()

            val lastFilename = locatePreviousStats(currentDate, statisticsDirectory)
            val previousAnnotationsPerUser: MutableMap<String, Int> = mutableMapOf()
            val previousAnnotationsPerSubtype: MutableMap<String, Int> = mutableMapOf()
            var previousAnnotations = 0
            if (lastFilename.isEmpty()) {
                logger.info {"No previous stats report in over a week"}
            }
            else {
                previousAnnotations = getPreviousAnnotations(File(lastFilename), previousAnnotationsPerUser, previousAnnotationsPerSubtype)
            }

            // Convert values to html
            File("$statisticsDirectory/StatsReport$thisDate.html").printWriter().use{
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
                    h2 { +"Annotation Statistics - $thisDate"}
                    table {
                        tr {
                            th {+"Total annotations"}
                            th {+"New annotations"}
                        }
                        tr {
                            td {+"$totalAnnotations"}
                            if (previousAnnotations == 0) {
                                td{+"n/a"}
                            }
                            else {
                                td { +"${totalAnnotations - previousAnnotations}" }
                            }
                        }
                    }
                    table {
                        tr {
                            th {+"User"}
                            th {+"Annotations"}
                            th {+"New annotations"}
                        }
                        for (user in sortedUsers.keys) {
                            tr {
                                td {+"$user"}
                                td {+"${annotationsPerUser[user]}"}
                                if (previousAnnotationsPerUser[user] == null) {
                                    td {+"n/a"}
                                }
                                else {
                                    td {+"${annotationsPerUser[user]?.minus(previousAnnotationsPerUser[user]!!)}"}
                                }
                            }
                        }
                    }
                    table {
                        tr {
                            th {+"Subtype"}
                            th {+"Annotations"}
                            th {+"New annotations"}
                        }
                        for (subtype in sortedSubtypes.keys) {
                            tr {
                                td {+"$subtype" }
                                td {+"${annotationsPerSubtype[subtype]}"}
                                if (previousAnnotationsPerSubtype[subtype] == null) {
                                    td {+"n/a"}
                                }
                                else {
                                    td {+"${annotationsPerSubtype[subtype]?.minus(previousAnnotationsPerSubtype[subtype]!!)}"}
                                }
                            }
                        }
                    }
                }
            }
            }
        }
        /**
         * Search as far back as a week for a previous stats report
         * The length of time can be changed
         */
        fun locatePreviousStats(currentDate: Calendar, statsDir: File): String {
            val calendar = currentDate
            val formatter = SimpleDateFormat("yyyy-MM-dd")
            for (day in 1..7) {
                calendar.add(Calendar.DAY_OF_YEAR, -1)
                val lastDate = formatter.format(calendar.getTime())
                val lastFilename = "$statsDir/StatsReport$lastDate.html"
                if (File(lastFilename).exists()) {
                    return lastFilename
                }
            }
            return ""
        }
        fun getPreviousAnnotations(
                statsFile: File,
                userMap: MutableMap<String, Int>,
                subtypeMap: MutableMap<String, Int>
        ): Int {
            val html: Document = Jsoup.parse(statsFile, "utf-8")
            val firstTable: Element = html.selectFirst("table")
            var rows: Elements = firstTable.select("tr")
            var cols: Elements = Elements()
            for (row in rows) {
                cols = row.select("td")
            }
            val previousAnnotations: Int = cols.get(0).text().toInt()

            // Annotations by user
            val userTable: Element = html.select("table").get(1)
            rows = userTable.select("tr")
            for (row in rows) {
                cols = row.select("td")
                if (cols.isNotEmpty()) {
                    userMap[cols[0].text()] = cols[1].text().toInt()
                }
            }

            // Annotations by subtype
            val subtypeTable: Element = html.select("table").get(2)
            rows = subtypeTable.select("tr")
            for (row in rows) {
                cols = row.select("td")
                if (cols.isNotEmpty()) {
                    subtypeMap[cols[0].text()] = cols[1].text().toInt()
                }
            }
            return previousAnnotations
        }
    }
}

data class Annotation(val documentName: String, val user: String, val subtype: String)
data class EventSpan(val dependent: Int, val governor: Int, val relation_type: String)
