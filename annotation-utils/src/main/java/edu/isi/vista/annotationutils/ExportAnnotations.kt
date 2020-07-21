package edu.isi.vista.annotationutils

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.github.kittinunf.fuel.core.FuelError
import com.github.kittinunf.fuel.core.Request
import com.github.kittinunf.fuel.core.extensions.authentication
import com.github.kittinunf.fuel.httpGet
import com.github.kittinunf.fuel.jackson.responseObject
import com.github.kittinunf.result.Result
import com.google.common.jimfs.Configuration
import com.google.common.jimfs.Jimfs
import edu.isi.nlp.parameters.serifstyle.SerifStyleParameterFileLoader
import mu.KLogging
import net.java.truevfs.comp.zip.ZipFile
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

val logger = KLogging().logger
const val EVENT_LOG = "event.log"

/**
 * Downloads all the annotations from an Inception annotation server.
 *
 * This uses the Inception remote API (https://inception-project.github.io//releases/0.8.4/docs/admin-guide.html#sect_remote_api),
 * which is an implementation of the AERO protocol (https://openminted.github.io/releases/aero-spec/1.0.0/omtd-aero/) .
 * Note that you need to enable the remote API on your service.
 *
 * The program takes one argument, a parameter file in the format described at
 * https://github.com/isi-vista/nlp-util/blob/master/common-core-open/src/main/java/edu/isi/nlp/parameters/serifstyle/SerifStyleParameterFileLoader.java
 *
 * The required parameter values are:
 * <ul>
 *     <li> {@code inceptionUrl} is the base URL of your Inception server.</li>
 *     <li> {@code inceptionUserName} is the user name of an Inception user with access to the
 *          remote API. This privilege must be granted in the Inception administration interface.
 *          </li>
 *     <li> {@code inceptionPassword} is the password for the user above. Note that parameter files can
 *          interpolate from environmental variables</li>
 *     <li> {@code exportedAnnotationRoot} is the directory to download annotation to.  The
 *     hierarchy beneath it will look like "projectName/documentName/documentName-userName.json"</li>
 *  </ul>
 */

data class Project(val id: Long, val name: String)
class ExportAnnotations {
    companion object {
        fun main(argv: Array<String>) {
            if (argv.size != 1) {
                throw RuntimeException("Expected a single argument, a parameter file")
            }
            val paramsLoader = SerifStyleParameterFileLoader.Builder()
                    .interpolateEnvironmentalVariables(true).build()
            val params = paramsLoader.load(File(argv[0]))
            export(params)
        }

        fun export(params: edu.isi.nlp.parameters.Parameters) {
            val inceptionUrl = params.getString("inceptionUrl")
            val inceptionUserName = params.getString("inceptionUsername")
            val inceptionPassword = params.getString("inceptionPassword")

            val exportedAnnotationRoot = params.getCreatableDirectory("exportedAnnotationRoot").toPath()

            if (!inceptionUrl.startsWith("http://")) {
                throw RuntimeException("Inception URL must start with http:// but got $inceptionUrl")
            }
            logger.info { "Connecting to Inception at $inceptionUrl" }

            val mapper = ObjectMapper().registerKotlinModule()
            val writer = ObjectMapper().writerWithDefaultPrettyPrinter()

            // extension function to avoid authentication boilerplate
            fun Request.authenticateToInception() =
                    this.authentication().basic(inceptionUserName, inceptionPassword)

            val projects = retryOnFuelError {
                "$inceptionUrl/api/aero/v1/projects".httpGet()
                        .authenticateToInception()
                        .resultObjectThrowingExceptionOnFailure<AeroResult<Project>>(mapper)
                        .body
            }
            if (projects == null) {
                throw java.lang.RuntimeException("Could not fetch projects from $inceptionUrl. Aborting")
            }
            logger.info { "Projects on server ${projects.map { it.name }}" }

            for (project in projects) {
                logger.info { "Processing project $project" }
                val getDocsUrl = "$inceptionUrl/api/aero/v1/projects/${project.id}/documents"
                val getExportUrl = "$inceptionUrl/api/aero/v1/projects/${project.id}/export.zip"
                val documents = retryOnFuelError {
                    getDocsUrl.httpGet(
                            parameters = listOf("projectId" to project.id))
                            .authenticateToInception()
                            .resultObjectThrowingExceptionOnFailure<DocumentResult>(mapper)
                            .body
                }
                if (documents == null) {
                    logger.warn { "Skipping $project due to web errors" }
                    continue
                }

                // to reduce clutter, we only make a directory for a project if it in fact has any
                // annotation
                val projectOutputDir = exportedAnnotationRoot.resolve(project.name)
                if (documents.isNotEmpty()) {
                    Files.createDirectories(projectOutputDir)
                }
                for (document in documents) {
                    // each annotator's annotation for a document are stored separately
                    val getAnnotatingUsersUrl = "$inceptionUrl/api/aero/v1/projects/" +
                            "${project.id}/documents/${document.id}/annotations"
                    val annotationRecords = retryOnFuelError {
                        getAnnotatingUsersUrl
                                .httpGet(
                                        parameters = listOf(
                                                "projectId" to project.id,
                                                "documentId" to document.id
                                        )
                                )
                                .authenticateToInception()
                                .resultObjectThrowingExceptionOnFailure<AeroResult<AnnotatorRecord>>(mapper)
                                .body
                    }
                    if (annotationRecords == null) {
                        logger.warn { "Skipping $document due to network errors" }
                        continue
                    }

                    // an annotation record records user's annotate state for the document
                    for (annotationRecord in annotationRecords) {
                        if (annotationRecord.state == "NEW") {
                            // no actual annotation
                            logger.warn { "Skipping $annotationRecord because it is NEW" }
                            continue
                        }
                        val getAnnotationsUrl = "$inceptionUrl/api/aero/v1/projects/${project.id}" +
                                "/documents/${document.id}/annotations/${annotationRecord.user}"

                        // the return from this will be the bytes of a a zip file which contains the
                        // JSON representation of the annotation
                        val annotationFileBytes =
                                getAnnotationsUrl
                                        .httpGet(
                                                parameters = listOf(
                                                        "projectId" to project.id,
                                                        "documentId" to document.id,
                                                        "userId" to annotationRecord.user,
                                                        // without this, it just downloads the original source
                                                        // text
                                                        "format" to "json"
                                                )
                                        )
                                        .authenticateToInception()
                                        .retryOnResponseFailure()
                        if (annotationFileBytes == null) {
                            logger.warn { "Skipping $annotationRecord due to network errors" }
                            continue
                        }

                        // Java's ZipFile class, for unknown reasons, can only work from Files and not
                        // in-memory bytes, so we make an in-memory file system to hold the zip file
                        val inMemoryFileSystem = Jimfs.newFileSystem(Configuration.unix())
                        val annotationsInMemoryZip = inMemoryFileSystem.getPath("/annotations.zip")
                        Files.write(annotationsInMemoryZip, annotationFileBytes)

                        ZipFile(annotationsInMemoryZip).use {
                            // filename manipulation is to work around
                            // https://github.com/inception-project/inception/issues/1174
                            val lastDotInDocumentNameIndex = document.name.lastIndexOf('.')
                            // note documents cannot have empty names
                            val zipEntryName = if (lastDotInDocumentNameIndex >= 0) {
                                document.name.substring(0, lastDotInDocumentNameIndex)
                            } else {
                                document.name
                            }

                            val jsonBytes = it.getInputStream("$zipEntryName.json")?.readBytes()
                            if (jsonBytes != null) {
                                var jsonTree = ObjectMapper().readTree(jsonBytes) as ObjectNode
                                // Our LDC license does not permit us to distribute the full document text.
                                // Users may retrieve the text from the original LDC source document releases.
                                jsonTree.replaceFieldEverywhere("sofaString", "__DOCUMENT_TEXT_REDACTED_FOR_IP_REASONS__")
                                // Note that output file paths are unique because they include the project name, the document
                                // id, and the annotator name. Each annotator can only annotate a document once in a project.
                                val outFileName = projectOutputDir.resolve("${document.name}-${annotationRecord.user}.json")
                                val redactedJsonString = writer.writeValueAsString(jsonTree)
                                Files.write(outFileName, redactedJsonString.toByteArray())
                            } else {
                                throw RuntimeException("Corrupt zip file returned")
                            }
                        }
                    }
                }
                // To save time and reduce clutter, only extract the log file if
                // the project directory contains .json output.
                val projectOutputFiles = projectOutputDir.toFile().listFiles()?.size ?: 0
                if (projectOutputFiles > 0) {
                    // Get export.zip, which contains the project's log file
                    val exportZip = retryOnFuelError {
                        getExportUrl.httpGet()
                                .authenticateToInception()
                                .retryOnResponseFailure()
                    }
                    // Get `event.log` from the resulting .zip export
                    // and save it to the project output directory
                    if (exportZip != null) {
                        val inMemoryFileSystem = Jimfs.newFileSystem(Configuration.unix())
                        val exportInMemoryZip = inMemoryFileSystem.getPath("/export.zip")
                        Files.write(exportInMemoryZip, exportZip)
                        val zipOutputFile = File(projectOutputDir.toString(), EVENT_LOG)
                        ZipFile(exportInMemoryZip).use {
                            val eventLog = it.getInputStream(EVENT_LOG)?.readBytes()
                            if (eventLog != null) {
                                Files.write(zipOutputFile.toPath(), eventLog)
                            }
                        }
                    }
                }
            }
            logger.info { "all done!" }
        }
    }
}

data class Document(val id: Long, val name: String, val state: String) {
    init {
        if (name.isEmpty()) {
            throw IllegalArgumentException("Documents cannot have empty names")
        }
    }
}

data class DocumentResult(val messages: List<String>, val body: List<Document>)
private data class AnnotatorRecord(val user: String, val state: String, val timestamp: String?)
data class AeroResult<T>(val messages: List<String>, val body: List<T>)

inline fun <reified T : Any> Request.resultObjectThrowingExceptionOnFailure(
        mapper: ObjectMapper
): T {
    val (_, _, result) = this.responseObject<T>(mapper)

    return when (result) {
        is Result.Failure<*> -> {
            throw result.getException()
        }
        is Result.Success<*> -> {
            result.get()
        }
    }
}

fun <T> retryOnFuelError(maxTries: Int = 3, timeoutInSeconds: Long = 5, function: () -> T): T? {
    for (i in 1..maxTries) {
        try {
            return function()
        } catch (e: FuelError) {
            logger.warn { e }
            if (i < maxTries) {
                logger.warn { "Request $i/$maxTries had FuelError. Waiting $timeoutInSeconds seconds and trying again." }
                TimeUnit.SECONDS.sleep(timeoutInSeconds)
            }
        }
    }
    logger.warn { "HTTP request has failed $maxTries times. Aborting." }
    return null
}


fun Request.retryOnResponseFailure(maxTries: Int = 3, timeoutInSeconds: Long = 5): ByteArray? {
    for (i in 1..maxTries) {
        val (_, _, result) = this.response()
        when (result) {
            is Result.Success<*> -> return result.get()
            is Result.Failure<*> -> {
                if (i < maxTries) {
                    logger.warn {
                        "Request $i/$maxTries response failed. Waiting $timeoutInSeconds seconds and trying again."
                    }
                    TimeUnit.SECONDS.sleep(timeoutInSeconds)
                }
            }
        }
    }
    logger.warn { "HTTP request has failed $maxTries times. Aborting." }
    return null
}