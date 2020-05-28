package edu.isi.vista.annotationutils

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.github.kittinunf.fuel.core.Request
import com.github.kittinunf.fuel.core.extensions.authentication
import com.github.kittinunf.fuel.httpDelete
import com.github.kittinunf.fuel.httpGet
import com.github.kittinunf.fuel.httpPost
import edu.isi.nlp.parameters.serifstyle.SerifStyleParameterFileLoader
import net.java.truevfs.comp.zip.ZipFile
import java.io.File
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Files

/*
Takes existing Inception projects and adds new argument labels.

This is useful if there are ontology changes to adopt.

This will add tags only to AIDA event type projects by default.
To add tags to event types of other projects, add the project
prefix to the event types list. (e.g. "ACE-Conflict.Attack")

A new tag can be added to all projects of a specific type by simply
making the event type the project prefix (e.g. "ACE": ["NewArgument"])
*/

fun main(argv: Array<String>)  {
    if (argv.size != 1) {
        throw RuntimeException("Expected a single argument, a parameter file")
    }
    val paramsLoader = SerifStyleParameterFileLoader.Builder()
            .interpolateEnvironmentalVariables(true).build()
    val params = paramsLoader.load(File(argv[0]))

    // load parameters
    val inceptionUrl = params.getString("inceptionUrl")
    val inceptionUserName = params.getString("inceptionUsername")
    val inceptionPassword = params.getString("inceptionPassword")

    val projectDir = params.getExistingDirectory("project_dir")
    val usersList = params.getOptionalExistingFile("users_list")
    val modifiedOutputDir = params.getCreatableDirectory("modified_output_dir")
    // One may want add arguments for specific users
    if (usersList != null) {
        val users = usersList.orNull()?.readLines()?.toSet()
    }

//    val outputDirectory = params.getCreatableDirectory("output_dir")!!
//    outputDirectory.mkdirs()

    // handles JSON (de)serialization
    val objectMapper = ObjectMapper()
    val mapper = ObjectMapper().registerKotlinModule()
    val prettyPrinter = objectMapper.writerWithDefaultPrettyPrinter()

    // Load the relevant event types and their new tags
    val eventTypesToNewTags = objectMapper.readTree(params.getExistingFile("new_tag_list")) as ObjectNode
    val eventTypesToModify: MutableSet<String> = eventTypesToNewTags.fieldNames().asSequence().toMutableSet()
    val languagePrefixes = arrayListOf("russian", "spanish")

    // For each project listed in the new tags list:
    for (eventType in eventTypesToModify) {
        val arguments = eventTypesToNewTags[eventType]
        // For each project in project_dir:
        projectDir.walk().filter { it.isFile }.forEach { zipFile ->
            val filename = zipFile.name
            var abridgedFilename = zipFile.name
            // Ignore any language prefixes
            for (language in languagePrefixes) {
                if (filename.startsWith(language)) {
                    abridgedFilename = abridgedFilename.removePrefix("$language-")
                }
            }
            // If the project's name minus language prefix begins with the project name to edit:
            if (abridgedFilename.startsWith(eventType)) {
                logger.info { "$filename is about to be modified." }

                ZipFile(zipFile.toPath()).use {
                    val jsonBytes = it.getInputStream("exportedproject123.json")?.readBytes()
                    if (jsonBytes != null) {
                        val jsonTree = objectMapper.readTree(jsonBytes)

                        // tag sets - these will be added to below for each argument
                        val tagSet = (jsonTree["tag_sets"] as ArrayNode)[0] as ObjectNode
                        val tags = tagSet["tags"] as ArrayNode
                        logger.info { "Tags: $tags" }
                        // first layer is for spans, second for argument relation type
                        val argumentTypeLayer = (jsonTree["layers"] as ArrayNode)[1]
                        val argumentTypeTags = (argumentTypeLayer["features"] as ArrayNode)[0]["tag_set"]["tags"] as ArrayNode

                        // Add arguments
                        for (argument in arguments) {
                            val tagJson = objectMapper.createObjectNode()
                            tagJson.put("tag_name", argument)
                            tagJson["tag_description"] = null
                            if (tags.contains(argument))
                                logger.info { "$filename already has tag $argument!" }
                            tags.add(tagJson)
                            argumentTypeTags.add(tagJson)
                        }

                        val zipOutFile = modifiedOutputDir.resolve(filename)
                        // we need to delete any existing file or else the zip file system will just add
                        // to its content
                        zipOutFile.delete()
                        val env = mapOf("create" to "true")
                        val uri = URI.create("jar:file:$zipOutFile")

                        FileSystems.newFileSystem(uri, env).use { zipfs ->
                            val pathInZipfile = zipfs.getPath("exportedproject123.json")
                            Files.newBufferedWriter(pathInZipfile).use { out ->
                                out.write(prettyPrinter.writeValueAsString(jsonTree))
                            }
                        }
                    } else {
                        throw RuntimeException("Corrupt zip file returned")
                    }
                }
            }
        }
    }
    // Now that we have our new projects, we import them to Inception.
    // Since we don't want severl "copy_of" projects, we delete the
    // original project files as long as the projects had no
    // extension function to avoid authentication boilerplate
    fun Request.authenticateToInception() =
            this.authentication().basic(inceptionUserName, inceptionPassword)

    val projects = retryOnFuelError {
        "$inceptionUrl/api/aero/v1/projects".httpGet()
                .authenticateToInception()
                .resultObjectThrowingExceptionOnFailure<AeroResult<Project>>(mapper)
                .body
    }
            ?: throw java.lang.RuntimeException("Could not fetch projects from $inceptionUrl. Aborting")
    logger.info { "Projects on server ${projects.map { it.name }}" }

    val canBeDeleted = arrayListOf<String>()
    val projectsToDelete = arrayListOf<String>()
    val projectsToImport = arrayListOf<File>()
    for (project in projects) {
        logger.info { "Processing project $project" }
        val projectName = project.name
        var abridgedProjectName = project.name
        for (language in languagePrefixes) {
            if (project.name.startsWith(language)) {
                abridgedProjectName = abridgedProjectName.removePrefix("$language-")
            }
        }
        val getDocsUrl = "$inceptionUrl/api/aero/v1/projects/${project.id}/documents"
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
        // Projects without annotated documents can be
        if (documents.isEmpty() and eventTypesToModify.any {abridgedProjectName.startsWith(it)}) {
            // logger.info { "$project has no documents"}
            canBeDeleted.add(projectName)
        }
    }
    logger.info { "Projects that can safely be deleted: $canBeDeleted" }  // testing
    modifiedOutputDir.walk().filter { it.isFile }.forEach { zipFile ->
        val projectName = zipFile.nameWithoutExtension
        if (canBeDeleted.contains(projectName)) {
            projectsToDelete.add(projectName)
            projectsToImport.add(zipFile)
        }
    }
    logger.info { "Projects to replace: $projectsToImport" }
    // Delete projects with replacements
//    for (project in canBeDeleted) {
//        logger.info { "Deleting project $project" }
//        retryOnFuelError {
//            "$inceptionUrl/api/aero/v1/projects/$project".httpDelete()
//                    .authenticateToInception()
//                    .resultObjectThrowingExceptionOnFailure<AeroResult<Project>>(mapper)
//                    .body
//        }
//    }
//    // Import updated projects to replace deleted ones
//    for (newProject in projectsToImport) {
//        logger.info { "Importing project $newProject" }
//        retryOnFuelError {
//            "$inceptionUrl/api/aero/v1/projects/import/$newProject".httpPost()
//                    .authenticateToInception()
//                    .resultObjectThrowingExceptionOnFailure<AeroResult<Project>>(mapper)
//                    .body
//        }
//    }
}
