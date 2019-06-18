package edu.isi.vista.annotationutils

import edu.isi.nlp.io.OffsetIndexedCorpus
import edu.isi.nlp.symbols.Symbol
import edu.isi.nlp.io.DocIDToFileMappings
import com.google.common.base.Optional
import com.fasterxml.jackson.databind.ObjectMapper
import edu.isi.nlp.parameters.Parameters
import java.io.File

fun main(argv: Array<String>) {
    // Load parameters:
    val params = Parameters.loadSerifStyle(File(argv[0]))
    val indexDirectory = params.getExistingDirectory("indexDirectory")
    val gigawordDataDirectory = params.getExistingDirectory("gigawordDataDirectory")
    val jsonInputDirectory = params.getCreatableDirectory("dehydratedJsonDirectory")
    val outputDirectory = params.getCreatableDirectory("rehydratedJsonDirectory")
    outputDirectory.toString()

    // Create an OriginalTextSource for getting original document text to put in json:
    val textMap = DocIDToFileMappings.forFunction { symbol ->
        Optional.of(File(gigawordDataDirectory, docIDToFilename(symbol)))
    }
    val indexMap = DocIDToFileMappings.forFunction { symbol ->
        Optional.of(File(indexDirectory, docIDToFilename(symbol) + ".index"))
    }
    val textSource = OffsetIndexedCorpus.fromTextAndOffsetFiles(textMap, indexMap)

    // Make objects for reading, parsing, and writing json:
    val objectMapper = ObjectMapper()
    val prettyPrinter = objectMapper.writerWithDefaultPrettyPrinter()

    for (projectDir in jsonInputDirectory.listFiles()) {
        if (!projectDir.isDirectory()) {
            continue
        }
        val projectOutDir = File(outputDirectory, projectDir.name)
        projectOutDir.mkdir()
        for (jsonFile in projectDir.listFiles()) {
            val jsonTree = objectMapper.readTree(jsonFile)
            // First 21 characters of the filename are the document id
            // Example filename is AFP_ENG_19960918.0012-admin.json
            val docID = Symbol.from(jsonFile.name.substring(0, 21))
            val text = textSource.getOriginalText(docID).get()
            jsonTree.replaceFieldEverywhere("sofaString", text)
            val outFile = File(projectOutDir, jsonFile.name)
            outFile.writeBytes(prettyPrinter.writeValueAsBytes(jsonTree))
        }
    }
}

fun docIDToFilename(symbol: Symbol?): String? {
    // e.g. AFP_ENG_19960918.0012 -> afp_eng/afp_eng_199609
    val st = symbol?.asString()?.toLowerCase()
    val dir = st?.substring(0, 7) //
    val basename = st?.substring(0, 14)
    return "$dir/$basename"
}
