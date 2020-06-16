package edu.isi.vista.annotationutils

import edu.isi.nlp.parameters.serifstyle.SerifStyleParameterFileLoader
import java.io.File

/**
 * Script for running GetAnnotationDurations independently
 */

fun main(argv: Array<String>) {
    if (argv.size != 1) {
        throw RuntimeException("Expected a single argument: a parameter file")
    }
    val paramsLoader = SerifStyleParameterFileLoader.Builder().build()
    val params = paramsLoader.load(File(argv[0]))

    GetAnnotationDurations.getDurations(params)
}