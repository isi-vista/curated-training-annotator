package edu.isi.vista.annotationutils

import edu.isi.nlp.parameters.Parameters
import edu.isi.nlp.parameters.serifstyle.SerifStyleParameterFileLoader
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import java.io.File

/**
 * Script for running ExtractAnnotationStats independently
 */

fun main(argv: Array<String>) {
    if (argv.size != 1) {
        throw RuntimeException("Expected a single argument: a parameter file")
    }
    val paramsLoader = SerifStyleParameterFileLoader.Builder().build()
    val params = paramsLoader.load(File(argv[0]))

    ExtractAnnotationStats.extractStats(params)
}