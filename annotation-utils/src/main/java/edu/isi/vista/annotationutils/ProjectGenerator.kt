package edu.isi.vista.annotationutils

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.common.collect.ImmutableSetMultimap
import com.google.common.collect.Sets
import com.google.common.io.Resources
import edu.isi.nlp.parameters.Parameters
import org.apache.jena.query.ParameterizedSparqlString
import org.apache.jena.query.QueryExecutionFactory
import org.apache.jena.query.QueryFactory
import org.apache.jena.rdf.model.ModelFactory
import org.apache.jena.rdf.model.Resource
import java.io.File
import java.net.URI
import java.nio.file.StandardCopyOption
import java.nio.file.Paths
import java.nio.file.FileSystems
import java.nio.file.Files

/*
Generate Inception projects for import for the cross-product of a list of users and the
events of an ontology.

The ElasticSearch index to search against is specified by the elasticSearchIndexName parameter.
*/

fun main(argv: Array<String>)  {
    val params = Parameters.loadSerifStyle(File(argv[0]))


    val users = params.getExistingFile("users_list").readLines().toSet()
    val (eventTypes, eventTypesToArguments) = loadOntologyInfo(params)
    val outputDirectory = params.getCreatableDirectory("output_dir")!!
    outputDirectory.mkdirs()

    // handles JSON (de)serialization
    val objectMapper = ObjectMapper()
    val prettyPrinter = objectMapper.writerWithDefaultPrettyPrinter()

    val jsonProjectTemplate = Resources.getResource("edu/isi/vista/annotationutils/project_template.json").readText()
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

data class OntologyInfo(val eventTypes: Set<String>,
                        val eventTypesToArguments: ImmutableSetMultimap<String, String>)

private fun loadOntologyInfo(params: Parameters): OntologyInfo {
    val model = ModelFactory.createDefaultModel()
    // we need to load this because this is where the super class of all event types
    // is defined.  It can be found at
    // https://github.com/NextCenturyCorporation/AIDA-Interchange-Format/blob/master/src/main/resources/com/ncc/aif/ontologies/LDCOntology
    model.read(params.getExistingFile("aidaDomainCommon").absolutePath, "TURTLE")
    model.read(params.getExistingFile("ontology").absolutePath, "TURTLE")

    val queryExecution = QueryExecutionFactory.create(
            gather_events_sparql_query, model)
    val eventTypeNodes = queryExecution.use { execution ->
        val results = execution.execSelect()
        results.asSequence().map {
            it["subclass"].asResource()!!
        }.toSet()
    }

    // we need shorter names than the full IRIs or the project names will be unreadable
    fun shortenIri(eventTypeResource: Resource): String {
        val eventFullIri = eventTypeResource.uri
        // chop off the prefix shared by all event types, which ends with #
        if (!eventFullIri.contains("#") || eventFullIri.endsWith("#")) {
            throw RuntimeException("Event IRI does not contain a # or ends with a #: $eventFullIri")
        }
        return eventFullIri.substring(eventFullIri.lastIndexOf("#") + 1)
    }

    // the argument types are additionally prefixed with
    fun stripEventType(argNameWithEventPrefix: String, eventType: String) : String {
        // +1 to eat the _ separating the event type from the argument name
        return argNameWithEventPrefix.substring(eventType.length + 1)
    }

    val eventTypesToArgumentsB = ImmutableSetMultimap.builder<String, String>()
    for (eventType in eventTypeNodes) {
        gather_event_arguments_sparql_query.setParam("eventType", eventType)
        val argQueryExecution = QueryExecutionFactory.create(
                gather_event_arguments_sparql_query.asQuery(), model)
        argQueryExecution.use { execution ->
            val results = execution.execSelect()
            results.asSequence().forEach { result->
                val shortEventName = shortenIri(eventType)
                eventTypesToArgumentsB.put(
                        shortEventName,
                        stripEventType(shortenIri(result["arg"].asResource()), shortEventName))
            }
        }
    }

    return OntologyInfo(
            eventTypes = eventTypeNodes.map(::shortenIri).toSet(),
            eventTypesToArguments = eventTypesToArgumentsB.build()
    )
}

private val gather_events_sparql_query = QueryFactory.create("""
    PREFIX aidaDomainCommon: <https://tac.nist.gov/tracks/SM-KBP/2018/ontologies/AidaDomainOntologiesCommon#>
    PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>

    SELECT * WHERE {?subclass rdfs:subClassOf+ aidaDomainCommon:EventType}
""".trimIndent())

private val gather_event_arguments_sparql_query = ParameterizedSparqlString("""
    PREFIX aidaDomainCommon: <https://tac.nist.gov/tracks/SM-KBP/2018/ontologies/AidaDomainOntologiesCommon#>
    PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>

    SELECT * WHERE {
        ?arg rdfs:subClassOf aidaDomainCommon:EventArgumentType .
        ?arg rdfs:domain ?eventType
    }
""".trimIndent())

fun JsonNode.replaceFieldEverywhere(fieldName: String, replacement: String) {
    if (this is ObjectNode && this.has(fieldName)) {
        put(fieldName, replacement)
    }

    asSequence().forEach {
        it.replaceFieldEverywhere(fieldName, replacement)
    }
}
