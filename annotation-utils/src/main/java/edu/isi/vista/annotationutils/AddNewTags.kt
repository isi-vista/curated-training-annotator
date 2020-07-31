package edu.isi.vista.annotationutils

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.github.kittinunf.fuel.core.FileDataPart
import com.github.kittinunf.fuel.core.Request
import com.github.kittinunf.fuel.core.extensions.authentication
import com.github.kittinunf.fuel.httpDelete
import com.github.kittinunf.fuel.httpGet
import com.github.kittinunf.fuel.httpUpload
import com.google.common.jimfs.Configuration
import com.google.common.jimfs.Jimfs
import edu.isi.nlp.parameters.serifstyle.SerifStyleParameterFileLoader
import net.java.truevfs.comp.zip.ZipEntry
import net.java.truevfs.comp.zip.ZipFile
import net.java.truevfs.comp.zip.ZipOutputStream
import java.io.BufferedOutputStream
import java.io.File
import java.nio.file.Files

/**
 * Takes existing Inception projects and adds new argument labels.
 *
 * This is useful if there are ontology changes to adopt.
 *
 * This will add tags only to AIDA event type projects (i.e. those not
 * prefixed by something other than a language) by default.
 * To add tags to event types of other projects, add the project
 * prefix to the event types list. (e.g. "ACE-Conflict.Attack")
 * Note that a language prefix is not needed to add tags to
 * non-English projects.
 *
 * A new tag can be added to all projects of a specific type by simply
 * making the event type the project prefix (e.g. "ACE": ["NewArgument"])
 *
 * It may be a good idea to run this with the parameters
 * ```
 * modifyLocalOnly: true
 * originalExportDir: <path_to_separate_backup_directory>
 * ```
 * first to be extra-sure that you have backups to (re-)import manually
 * in case something goes awry.
 *
 * Required parameters:
 * <ul>
 *     <li> `inceptionUrl` is the base URL of your Inception server.</li>
 *     <li> `inceptionUserName` is the user name of an Inception user with access to the
 *          remote API. This privilege must be granted in the Inception administration interface.
 *          </li>
 *     <li> `inceptionPassword` is the password for the user above. Note that parameter files can
 *          interpolate from environmental variables</li>
 *     <li> `originalExportDir` is the directory where the original project ZIP files
 *     will be saved. These are handy in case any projects need to be re-imported.</li>
 *     <li> `modifiedOutputDir` is the directory where the projects with added tags
 *     will be saved.</li>
 *     <li> `userList` is the text file containing the list of users whose projects
 *     will be modified.</li>
 *     <li> `newTagList` is the JSON file containing the event types and their new tags.
 *     (An example of how this should look is in `docs/aida_changes_0514.json`.)</li>
 * </ul>
 *
 * Optional parameters:
 * <ul>
 *     <li> `modifyLocalOnly`: if true, none of the modified projects will be imported
 *     to Inception, and their original projects will not be deleted from the server.
 *     If false (default), the program will run its normal course.</li>
 * </ul>
 */

fun main(argv: Array<String>) {
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

    val originalExportDir = params.getCreatableDirectory("originalExportDir")
    val userSet = params.getExistingFile("userList").readLines().toSet()
    val modifiedOutputDir = params.getCreatableDirectory("modifiedOutputDir")
    val modifyLocalOnly = params.getOptionalBoolean("modifyLocalOnly").or(false)

    // handles JSON (de)serialization
    val objectMapper = ObjectMapper()
    val mapper = ObjectMapper().registerKotlinModule()
    val prettyPrinter = objectMapper.writerWithDefaultPrettyPrinter()

    // Part I: Create new project files with new tags

    // Load the relevant event types and their new tags
    val newTagListFile = params.getExistingFile("newTagList")
    val eventTypesToNewTags = objectMapper.readTree(newTagListFile) as ObjectNode
    val eventTypesToModify: MutableSet<String> = eventTypesToNewTags.fieldNames().asSequence().toMutableSet()
    val languagePrefixes = arrayListOf("russian", "spanish")

    // extension function to avoid authentication boilerplate
    fun Request.authenticateToInception() =
            this.authentication().basic(inceptionUserName, inceptionPassword)

    // Get the list of projects on the server
    val serverProjects = retryOnFuelError {
        "$inceptionUrl/api/aero/v1/projects".httpGet()
                .authenticateToInception()
                .resultObjectThrowingExceptionOnFailure<AeroResult<Project>>(mapper)
                .body
    }
            ?: throw java.lang.RuntimeException("Could not fetch projects from $inceptionUrl. Aborting")
    logger.info { "Projects on server ${serverProjects.map { it.name }}" }
    val importUrl = "$inceptionUrl/api/aero/v1/projects/import"
    val failureSet = mutableSetOf<String>()  // will hold names of projects that were not modified

    // Determine which projects need to be processed
    val projectsToEventTypes = mutableMapOf<Project, String>()
    for (serverProject in serverProjects) {
        val projectName = serverProject.name
        // Ignore any language prefixes so that it knows
        // to modify *all* projects of this event type
        val abridgedProjectName = if (languagePrefixes.any {projectName.startsWith(it)}) {
            val firstHyphen = projectName.indexOf('-')
            val language = projectName.substring(0, firstHyphen)
            projectName.removePrefix("$language-")
        } else {
            projectName
        }
        for (eventType in eventTypesToModify) {
            // If the project's name minus language prefix begins with the project name to edit:
            if (abridgedProjectName.startsWith(eventType) and userSet.any { abridgedProjectName.contains(it) }) {
                logger.info { "$projectName is about to be modified." }
                projectsToEventTypes[serverProject] = eventType
            }
        }
    }

    for (project in projectsToEventTypes) {
        // Step 1: export relevant projects from Inception.
        logger.info { "Now exporting ${project.key.name}" }
        val getExportUrl = "$inceptionUrl/api/aero/v1/projects/${project.key.id}/export.zip"
        val exportZip = retryOnFuelError {
            getExportUrl.httpGet()
                    .authenticateToInception()
                    .retryOnResponseFailure()
        }
        if (exportZip != null) {
            // Step 2: add new tags by creating modified copies
            // of the original projects.
            val inMemoryFileSystem = Jimfs.newFileSystem(Configuration.unix())
            val exportInMemoryZip = inMemoryFileSystem.getPath("/export.zip")
            Files.write(exportInMemoryZip, exportZip)

            // Save the zip file so that it can be re-imported later if needed
            val zipOutputFile = File(originalExportDir, "${project.key.name}.zip")
            val zipOutputPath = zipOutputFile.toPath()
            Files.write(zipOutputPath, exportZip)
            val modifiedZipOutputFile = modifiedOutputDir.resolve("${project.key.name}.zip")
            val modifiedZipOutputPath = modifiedZipOutputFile.toPath()
            val modifiedZipInMemory = inMemoryFileSystem.getPath("/modified_export.zip")
            var replaceProject = false

            ZipFile(exportInMemoryZip).use {
                val zipOutputStream = ZipOutputStream(
                        BufferedOutputStream(
                                Files.newOutputStream(modifiedZipOutputPath)
                        )
                )
                for (projectFile in it) {
                    val inMemoryProjectPath = inMemoryFileSystem.getPath(projectFile.name)
                    val projectBytes = it.getInputStream(projectFile.name)?.readBytes()
                    if (projectBytes != null) {
                        if (inMemoryProjectPath.count() > 1) {
                            // Create any parent directories if they don't already exist
                            if (!Files.exists(inMemoryProjectPath.parent)) {
                                Files.createDirectories(inMemoryProjectPath.parent)
                            }
                        }
                        Files.write(inMemoryProjectPath, projectBytes)
                    }
                    if (projectFile.name.contains("exportedproject")) {
                        val jsonBytes = it.getInputStream(projectFile.name)?.readBytes()
                        if (jsonBytes != null) {
                            Files.delete(inMemoryProjectPath)
                            val inMemoryJson = inMemoryFileSystem.getPath(projectFile.name)
                            val jsonTree = objectMapper.readTree(jsonBytes)
                            val arguments = eventTypesToNewTags[project.value]
                            replaceProject = addArgumentsToJson(jsonTree, project.key.name, arguments)
                            val jsonToWrite = prettyPrinter.writeValueAsString(jsonTree)
                            Files.write(inMemoryJson, jsonToWrite.toByteArray())
                            zipOutputStream.putNextEntry(ZipEntry(inMemoryJson.toString()))
                            Files.copy(inMemoryJson, zipOutputStream)
                        } else {
                            throw RuntimeException("Corrupt zip file returned")
                        }
                    } else {
                        zipOutputStream.putNextEntry(ZipEntry(inMemoryProjectPath.toString()))
                        Files.copy(inMemoryProjectPath, zipOutputStream)
                    }
                    zipOutputStream.closeEntry()
                }
                zipOutputStream.close()
                Files.copy(modifiedZipOutputPath, modifiedZipInMemory)
            }

            if (modifyLocalOnly == false && replaceProject) {
                // Part II: Upload the modified projects to Inception

                // Step 3: delete the relevant original files.
                // Since we don't want several "copy_of" projects (which happens
                // when there is already a project of the same name on Inception),
                // we delete the original projects before importing the modified ones.
                logger.info { "Deleting project ${project.key.name}" }
                retryOnFuelError {
                    "$inceptionUrl/api/aero/v1/projects/${project.key.id}".httpDelete()
                            .authenticateToInception()
                            .retryOnResponseFailure()
                }

                // Step 4: import the new projects.
                logger.info { "Importing project $modifiedZipOutputFile" }
                retryOnFuelError {
                    importUrl.httpUpload()
                            .add(
                                    FileDataPart(
                                            modifiedZipOutputFile,
                                            name = "file",
                                            filename = "$modifiedZipOutputFile"
                                    )
                            )
                            .authenticateToInception()
                            .retryOnResponseFailure()
                }
            }
        } else {
            failureSet.add(project.key.name)
        }
    }
    logger.info { "All done!" }
    if (failureSet.size > 0) {
        logger.info {
            "The following projects may need their tags added manually: $failureSet"
        }
    }
}

private fun addArgumentsToJson(
        jsonTree: JsonNode, projectName: String, arguments: JsonNode
): Boolean {
    /**
     * Adds the new tags specified for the given project json.
     *
     * This also determines whether the project should be replaced
     * depending on whether any of the new argument labels were
     * added to the tag list.
     */
    val tagSet = (jsonTree["tag_sets"] as ArrayNode)[0] as ObjectNode
    val tags = tagSet["tags"] as ArrayNode

    // Add arguments
    val tagObjectMapper = ObjectMapper()
    val addedArgumentsList = mutableListOf<Boolean>()
    for (argument in arguments) {
        val tagJson = tagObjectMapper.createObjectNode()
        tagJson.set("tag_name", argument)
        tagJson["tag_description"] = null
        if (tags.contains(tagJson)) {
            logger.info { "$projectName already has tag $argument!" }
            addedArgumentsList.add(false)
        } else {
            tags.add(tagJson)
            addedArgumentsList.add(true)
        }
    }
    logger.info { "Done modifying $projectName's tag set" }
    return if (addedArgumentsList.all { !it }) {
        logger.info { "No tags were added to $projectName; it will not be replaced." }
        false
    } else { true }
}
