package edu.isi.vista.annotationutils

import edu.isi.nlp.io.OffsetIndexedCorpus
import edu.isi.nlp.symbols.Symbol
import edu.isi.nlp.io.DocIDToFileMappings
import com.google.common.base.Optional
import com.fasterxml.jackson.databind.ObjectMapper
import edu.isi.nlp.io.OriginalTextSource
import edu.isi.nlp.parameters.Parameters
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.io.IOException;

/**
 * Puts the document text back into the 'sofaString' field inside json files created by `ExportAnnotations.kt`
 * They are redacted from the json export because our LDC license does not permit us to distribute the full document text.
 *
 *
 * The program takes one argument, a parameter file in the format described at
 * https://github.com/isi-vista/nlp-util/blob/master/common-core-open/src/main/java/edu/isi/nlp/parameters/serifstyle/SerifStyleParameterFileLoader.java
 *
 * An example is provided in curated-training-annotator/sample_params/restore-text.params
 * The required parameter values are:
 * <ul>
 *     <li> {@code indexDirectory} is the location of the files produced by {@code IndexFlatGigaword.java}
(https://github.com/isi-vista/nlp-util/blob/master/nlp-core-open/src/main/java/edu/isi/nlp/corpora/gigaword/IndexFlatGigaword.java) </li>
 *     <li> {@code gigawordDataDirectory} is the location of the gigaword text files </li>
 *     <li> {@code inputJsonDirectory} is the location of the stripped json files produced by {@code ExportAnnotations.kt} </li>
 *     <li> {@code restoredJsonDirectory} is where the new json files will go </li>
 *  </ul>
 *
 *  The optional parameters listed below are for selecting non-English/other projects to restore.
 *  <ul>
 *      <li> `restoreAce` - if false, the program will skip restoring the text of ACE project documents.
 *      True by default if `aceEngDataDirectory` has a value, false if not.</li>
 *      <li> `aceEngDataDirectory` is the data/English directory of the ace corpus files.</li>
 *      <li> `restoreCord19` - if false, the program will skip restoring the text of CORD-19 project documents.
 *      True by default if `cord19DataDirectory` has a value, false if not.</li>
 *      <li> `cord19DataDirectory` is the data directory of the covid19 corpus files.</li>
 *      <li> `restoreRussian` - if false, the program will skip restoring the text of Russian LTF documents.
 *      True by default if `russianDataDirectory` has a value, false if not.</li>
 *      <li> `russianDataDirectory` is the location of the Russian LTF corpus files.</li>
 *      <li> `restoreSpanish` - if false, the program will skip restoring the text of Spanish gigaword documents.
 *      True by default if `spanishDataDirectory` has a value, false if not.</li>
 *      <li> `spanishDataDirectory` is the location of the Spanish gigaword text files.</li>
 *      <li> `spanishIndexDirectory` is the location of the files produced by `IndexFlatGigaword.java`
 *      for Spanish.</li>
 *  </ul>
 *
 */

class RestoreJson {
    companion object {
        fun main(argv: Array<String>) {
            if (argv.size != 1) {
                throw RuntimeException("Expected a single argument, a parameter file")
            }
            val params = Parameters.loadSerifStyle(File(argv[0]))
            restore(params)
        }
        fun restore(params: Parameters) {
            val inputJsonDirectory = params.getExistingDirectory("inputJsonDirectory")!!
            val outputDirectory = params.getCreatableDirectory("restoredJsonDirectory")!!
            val restoreAce = params.getOptionalBoolean("restoreAce").or(
                    params.isPresent("aceEngDataDirectory")
            )
            val restoreCord19 = params.getOptionalBoolean("restoreCord19").or(
                    params.isPresent("cord19DataDirectory")
            )
            val restoreRussian = params.getOptionalBoolean("restoreRussian").or(
                    params.isPresent("russianDataDirectory")
            )
            val restoreSpanish = params.getOptionalBoolean("restoreSpanish").or(
                    params.isPresent("spanishDataDirectory")
            )

            // Make objects for reading, parsing, and writing json:
            val objectMapper = ObjectMapper()
            val prettyPrinter = objectMapper.writerWithDefaultPrettyPrinter()
            val gigaWordTextSource = makeTextSource(params, "gigaword")
            val spanishTextSource = if (restoreSpanish) {
                makeTextSource(params, "spanish-gigaword")
            } else {null}
            val aceTextSource = if (restoreAce) {
                makeTextSource(params, "ace")
            } else {null}
            val cord19TextSource = if (restoreCord19) {
                makeTextSource(params, "covid19")
            } else {null}
            val russianTextSource = if (restoreRussian) {
                makeTextSource(params, "russian-ltf")
            } else {null}

            inputJsonDirectory.walk().filter { it.isFile }.forEach { jsonFile ->
                val filename = jsonFile.name
                val englishPattern = Regex("[\b_]ENG[\b_]")
                val spanishPattern = Regex("[\b_]SPA[\b_]")
                //returns docID if ace file, else returns null
                val aceDocID = getAceDocID(filename)
                // returns docID if CORD-19 file, else returns null
                val cord19DocID = getCord19DocID(filename)
                val russianDocID = getRussianDocID(filename)
                if (filename.endsWith(".json")) {
                    // The project directory is the path to this file with the input directory components
                    // stripped off the front, then joined with the desired output directory.
                    val projectOutDir = Paths.get(
                            outputDirectory.toString(),
                            jsonFile.toPath().removePrefixPath(inputJsonDirectory.toPath()).toString()
                    ).parent.toAbsolutePath()
                    if (Files.notExists(projectOutDir)) {
                        Files.createDirectories(projectOutDir)
                    }
                    val jsonTree = objectMapper.readTree(jsonFile)
                    // If aceDocID is empty, it means the document being processed is not an
                    // ace document
                    val text = if(restoreAce && aceDocID != null) {
                        // If it is an Ace doc: Filename contains event_type.subtype
                        // Example Filename is:
                        // CNNHL_ENG_20030304_142751.10-Business.Declare-Bankruptcy.xmi-liz_lee.json
                        val docID = Symbol.from(aceDocID)
                        aceTextSource!!.getOriginalText(docID).orNull()
                                ?: throw RuntimeException("Could not get original text for $docID")
                    } else if (restoreCord19 && cord19DocID != null) {
                        val docID = Symbol.from(cord19DocID)
                        cord19TextSource!!.getOriginalText(docID).orNull()
                                ?: throw RuntimeException("Could not get original text for $docID")
                    } else if (restoreRussian && russianDocID != null) {
                        val docID = Symbol.from(russianDocID)
                        russianTextSource!!.getOriginalText(docID).orNull()
                                ?: throw RuntimeException("Could not get original text for $docID")
                    } else if (restoreSpanish && filename.contains(spanishPattern)) {
                        // If it is a Gigaword doc: First 21 characters of the filename are the
                        // document id
                        // Example filename is AFP_ENG_19960918.0012-admin.json
                        val docID = Symbol.from(filename.substring(0, 21))
                        spanishTextSource!!.getOriginalText(docID).orNull()
                                ?: throw RuntimeException("Could not get original text for $docID")
                    } else if (aceDocID == null && filename.contains(englishPattern)) {
                        val docID = Symbol.from(filename.substring(0, 21))
                        gigaWordTextSource.getOriginalText(docID).orNull()
                                ?: throw RuntimeException("Could not get original text for $docID")
                    } else {null}
                    if (text != null) {
                        jsonTree.replaceFieldEverywhere("sofaString", text)
                        val outFile = File(projectOutDir.toString(), filename)
                        outFile.writeBytes(prettyPrinter.writeValueAsBytes(jsonTree))
                        logger.info { "Restored $filename" }
                    } else {
                        logger.info {
                            "Skipping $filename"
                        }
                    }
                } else {
                    logger.warn { "Cannot restore $filename; this file or project type is not supported" }
                }
            }
        }
    }
}

fun makeTextSource(params: Parameters, corpusName: String): OriginalTextSource {
    // Create an OriginalTextSource for getting original document text to put in json:
    if (corpusName.equals("gigaword", ignoreCase = true)) {
        // Create the gigaword corpus TextSource
        val textMap = DocIDToFileMappings.forFunction { symbol ->
            Optional.of(File(params.getExistingDirectory("gigawordDataDirectory"), docIDToFilename(symbol)))
        }
        val indexMap = DocIDToFileMappings.forFunction { symbol ->
            Optional.of(File(params.getExistingDirectory("indexDirectory"), docIDToFilename(symbol) + ".index"))
        }
        return OffsetIndexedCorpus.fromTextAndOffsetFiles(textMap, indexMap)
    } else if (corpusName.equals("spanish-gigaword", ignoreCase = true)) {
        // Create the spanish-gigaword corpus TextSource
        val textMap = DocIDToFileMappings.forFunction { symbol ->
            Optional.of(File(params.getExistingDirectory("spanishDataDirectory"), docIDToFilename(symbol)))
        }
        val indexMap = DocIDToFileMappings.forFunction { symbol ->
            Optional.of(File(params.getExistingDirectory("spanishIndexDirectory"), docIDToFilename(symbol) + ".index"))
        }
        return OffsetIndexedCorpus.fromTextAndOffsetFiles(textMap, indexMap)

    } else if (corpusName.equals("ace", ignoreCase = true)) {
        // Create the Ace corpus TextSource
        return AceCorpusTextSource(params.getExistingDirectory("aceEngDataDirectory"))
    } else if (corpusName.equals("covid19", ignoreCase = true)) {
        // Create the CORD-19 corpus TextSource
        return Cord19CorpusTextSource(params.getExistingDirectory("cord19DataDirectory"))
    } else if (corpusName.equals("russian-ltf", ignoreCase = true)) {
        // Create the Russian corpus TextSource
        return RussianCorpusTextSource(params.getExistingDirectory("russianDataDirectory"))
    }
    else {
        throw IOException("A corpus: $corpusName does not exist.");
    }
}

fun docIDToFilename(symbol: Symbol?): String {
    if (symbol == null) {
        throw RuntimeException("Got null symbol")
    }
    // e.g. AFP_ENG_19960918.0012 -> afp_eng/afp_eng_199609
    val st = symbol.asString()?.toLowerCase()
    val dir = st?.substring(0, 7) //
    val basename = st?.substring(0, 14)
    return Paths.get(dir, basename).toString()
}

private fun Path.removePrefixPath(prefix: Path): Path {
    require(this.startsWith(prefix)) {
        "The path $this does not start with the prefix $prefix"
    }
    return this.subpath(prefix.nameCount, this.nameCount)
}


private fun getAceDocID(filename: String): String? {
    // Returns null if not an AceFileName
    // Ace filenames contain an event.subtype in the file name
    // Example: CNNHL_ENG_20030304_142751.10-Business.Declare-Bankruptcy.xmi-liz_lee.json
    val regex = """(.*_[^a-zA-Z]*)-(.*?)-.*?""".toRegex()
    if (regex.containsMatchIn(filename)) {
        val matchResult = regex.find(filename)
        //Returns only the doc id. In this case: CNNHL_ENG_20030304_142751.10
        val matchGroups = matchResult!!.groups
        // match groups = ("<docid>-john_bob.json", "<docid>")
        return matchGroups[1]!!.value
    }
    return null
}

private fun getCord19DocID(filename: String): String? {
    // Returns null if not a CORD-19 filename
    // CORD-19 filenames consist of a string of letters and numbers
    // and then the annotator's username
    // Example: hfofi34yrohwfniw94tyowlefnweiug8iryfhwen-john_bob.json

    val regex = Regex(pattern = """([a-z0-9]{30,})-.*""")
    if (regex.containsMatchIn(filename)) {
        val matchResult = regex.find(filename)
        //Returns only the doc id. In this case: CNNHL_ENG_20030304_142751.10
        val matchGroups = matchResult!!.groups
        // match groups = ("<docid>-john_bob.json", "<docid>")
        return matchGroups[1]!!.value
    }
    return null
}

private fun getRussianDocID(filename: String): String? {
    // Returns null if not a Russian filename
    // Russian filenames consist of a string of letters and numbers
    // starting with "RUS_"
    // Example: RUS_DF_579382_08212053_P861983UGN-john_bob.json

    val regex = Regex(pattern = """(RUS_[A-Z]{2}_[A-Z0-9]{6}_[A-Z0-9]{8}_[A-Z0-9]{9})-.*""")
    if (regex.containsMatchIn(filename)) {
        // The section of the above pattern in parentheses is the "capturing group";
        // in this case it is the pattern of the document ID,
        // since this is the information we want to return.
        // If the pattern is found in the filename, it will fetch
        // the filename and the capturing group
        // and assign them as `groups` of matchResult.
        val matchResult: MatchResult? = regex.find(filename)
        val matchGroups: MatchGroupCollection = matchResult!!.groups
        // match groups = ("RUS_DF_579382_08212053_P861983UGN-john_bob.json", "RUS_DF_579382_08212053_P861983UGN")
        // The capturing group match is the second item.
        return matchGroups[1]!!.value
    }
    return null
}