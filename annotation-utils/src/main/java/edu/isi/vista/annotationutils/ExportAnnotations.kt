package edu.isi.vista.annotationutils

import com.github.kittinunf.fuel.core.Request
import com.github.kittinunf.fuel.core.extensions.authentication
import com.github.kittinunf.fuel.httpGet
import com.github.kittinunf.fuel.jackson.responseObject
import edu.isi.nlp.parameters.serifstyle.SerifStyleParameterFileLoader
import java.io.File
import com.github.kittinunf.result.Result

fun main(argv: Array<String>) {
    if (argv.size != 1) {
        throw RuntimeException("Expected a single argument, a parameter file")
    }
    val paramsLoader = SerifStyleParameterFileLoader.Builder()
            .interpolateEnvironmentalVariables(true).build()
    val params = paramsLoader.load(File(argv[0]))
    val inceptionUrl = params.getString("inceptionUrl")
    val inceptionUserName = params.getString("inceptionUsername")
    val inceptionPassword = params.getString("inceptionPassword")

    if(!inceptionUrl.startsWith("http://")) {
        throw RuntimeException("Inception URL must start with http:// but got $inceptionUrl")
    }
    println("Connecting to inception on $inceptionUrl")


    // extension function to avoid authentication boilerplate
    fun Request.authenticateToInception() =
            this.authentication().basic(inceptionUserName, inceptionPassword)



    val (_, _, projectsResult) = "$inceptionUrl/api/aero/v1/projects".httpGet()
            .authenticateToInception()
            .responseObject<ProjectsResult>()


    val projects =
            when(projectsResult) {
                    is Result.Failure<*> -> {
                        throw projectsResult.getException()
                    }
                    is Result.Success<*> -> {
                        projectsResult.get().body
                    }
                }

    println("Projects on server ${projects.map { it.name }}")

}

private data class Project(val id: Int, val name: String)
private data class ProjectsResult(val messages: List<String>, val body: List<Project>)

