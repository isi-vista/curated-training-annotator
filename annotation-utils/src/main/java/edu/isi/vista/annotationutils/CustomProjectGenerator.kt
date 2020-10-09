package edu.isi.vista.annotationutils

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.common.collect.Sets
import com.google.common.io.Resources
import edu.isi.nlp.parameters.Parameters
import java.io.File
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Files

/**
 * Generate Inception projects for import for the cross-product of a list of users and the
 * events/relations of an ontology.
 *
 * Required parameters:
 * --------------------
 *  usersList - path to a txt file with a different username on each line
 *  projectTemplate - path to one of the project templates;
 *      example: "edu/isi/vista/annotationutils/covid19_project_template.json"
 *  outputDirectory - where the new project files will be saved
 *  projectsList - path to the JSON file listing the info for each project
 *      (see docs/covid19_relations.json for an example)
 *  elasticSearchIndexName - the ElasticSearch index to search against
 *      (e.g. "gigaword" for English projects)
 *
 * Optional parameters:
 * --------------------
 *  projectPrefix - the string that attaches to the beginning of a project name
 *      (e.g. "CORD19", "russian")
 *
 */

fun main(argv: Array<String>)  {
    val params = Parameters.loadSerifStyle(File(argv[0]))


    val users = params.getExistingFile("usersList").readLines().toSet()
    val projectTemplate = params.getString("projectTemplate")
    val outputDirectory = params.getCreatableDirectory("outputDirectory")!!
    outputDirectory.mkdirs()

    // handles JSON (de)serialization
    val objectMapper = ObjectMapper()
    val prettyPrinter = objectMapper.writerWithDefaultPrettyPrinter()

    // Load the relation types from a project list
    val eventTypesToArguments = objectMapper.readTree(params.getExistingFile("projectsList")) as ObjectNode
    val eventTypes: MutableSet<String> = eventTypesToArguments.fieldNames().asSequence().toMutableSet()

    val jsonProjectTemplate = Resources.getResource(
            projectTemplate
    ).readText()
    val elasticSearchIndexName = params.getString("elasticSearchIndexName")
    val projectPrefix = params.getOptionalString("projectPrefix").orNull()

    Sets.cartesianProduct(users, eventTypes).forEach { combination ->
        val (user, eventType) = combination
        val prefix = if (projectPrefix != null) {
            "$projectPrefix-"
        } else {
            ""
        }

        val projectName = "$prefix$eventType-$user"

        // jsonTree will be the JSON project config we will output
        // we cast to ObjectNode in order to have a mutable interface
        val jsonTree = objectMapper.readTree(jsonProjectTemplate) as ObjectNode
        // tons of things in the JSON have to refer back to the project name
        jsonTree.put("name",  projectName)
        jsonTree.replaceFieldEverywhere("project_name", projectName)

        val arguments = eventTypesToArguments[eventType]

        // tag sets - these will be added to below for each argument
        val tagSet = (jsonTree["tag_sets"] as ArrayNode)[0] as ObjectNode
        val tags = tagSet["tags"] as ArrayNode

        // first layer is for spans, second for argument relation type
        val argumentTypeLayer = (jsonTree["layers"] as ArrayNode)[1]
        val argumentTypeTags = (argumentTypeLayer["features"] as ArrayNode)[0]["tag_set"]["tags"] as ArrayNode

        for (argument in arguments) {
            val tagJson = objectMapper.createObjectNode()
            tagJson.put("tag_name", argument)
            tagJson["tag_description"] = null
            // the project tempalte JSON adds a top-level tag for each argument role
            // I am not sure this is actually necessary, but there's no harm in it
            tags.add(tagJson)
            // I *am* sure we need to add a tag to the argument type layer for each argument type
            argumentTypeTags.add(tagJson)
        }

        // mark the user for this project as authorized to annotate it
        val projectPermissions = jsonTree["project_permissions"] as ArrayNode
        val permissionsJson = objectMapper.createObjectNode()
        permissionsJson.put("level", "USER")
        permissionsJson.put("user", user)
        projectPermissions.add(permissionsJson)

        // setup ElasticSearch repository
        val externalSearchJson = jsonTree["external_search"] as ArrayNode
        for (externalSearchConfigJson in externalSearchJson) {
            if (externalSearchConfigJson is ObjectNode) {
                externalSearchConfigJson.put("name", elasticSearchIndexName)
                externalSearchConfigJson.put("properties",
                        externalSearchConfigJson.get("properties").asText()
                                .replace("_ELASTIC_SEARCH_INDEX_NAME_", elasticSearchIndexName))
            } else {
                throw java.lang.RuntimeException("Only object nodes expected in list of " +
                        "external search configurations")
            }
        }

        val outputFile = outputDirectory.resolve("$projectName.zip")
        // we need to delete any existing file or else the zip file system will just add
        // to its content
        outputFile.delete()
        val env = mapOf("create" to "true")
        val uri = URI.create("jar:file:$outputFile")

        FileSystems.newFileSystem(uri, env).use { zipfs ->
            val pathInZipfile = zipfs.getPath("exportedproject123.json")
            Files.newBufferedWriter(pathInZipfile).use { out ->
                out.write(prettyPrinter.writeValueAsString(jsonTree))
            }
        }
    }
}
