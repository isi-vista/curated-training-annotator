package edu.isi.vista.annotationutils

import edu.isi.nlp.parameters.serifstyle.SerifStyleParameterFileLoader
import java.io.File

/**
 * Script for running ExportAnnotations independently
 */

fun main(argv: Array<String>) {
    if (argv.size != 1) {
        throw RuntimeException("Expected a single argument: a parameter file")
    }
    val paramsLoader = SerifStyleParameterFileLoader.Builder()
            .interpolateEnvironmentalVariables(true).build()
    val params = paramsLoader.load(File(argv[0]))

    ExportAnnotations.export(params)
}