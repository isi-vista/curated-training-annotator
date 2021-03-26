package edu.isi.vista.annotationutils

import edu.isi.nlp.parameters.serifstyle.SerifStyleParameterFileLoader
import java.io.File

/**
 * The goal of this program is to copy data from a previous export
 * and push it to a second GitHub repository.
 *
 * Parameters:
 *
 * <ul>
 *     <li> `repoToPushTo`: the ssh url of the repository to which the annotation data
 *     will be pushed; for ISI's curated training work, this is
 *     git@github.com:isi-vista/curated-training-annotation.git,
 *     but this will differ for other projects </li>
 *     <li> `localWorkingCopyDirectory`: the location of the local repository; the repository
 *     specified by `repoToPushTo` will be cloned to this location if it does not exist here</li>
 *     <li> `dryRun` (optional boolean): if true, the program will list where files will be
 *     copied/moved, and nothing will be pushed to the designated repo.
 *     Default is false. </li>
 *
 *     <li>`pushExport` (boolean) if true, the program will copy export data to the
 *     given repository, and the following will be needed:
 *     <ul>
 *         <li>`exportSource` (path): the directory where the export data is saved </li>
 *         <li>`exportDestination` (optional path): the directory where the export
 *         data will be copied to; default is `<localWorkingCopyDirectory>/data/exported` </li>
 *         <li>`zipExport` (boolean): if true, the export data will be zipped and moved
 *         to the exportDestination; else, it will be copied as-is </li>
 *     </ul></li>
 *
 *     <li>`pushStats` (boolean) if true, the program will copy statistics data to the
 *     given repository. This includes the subdirectories `annotation-statistics`,
 *     `indicator-searches`, and `time-reports`. The following will be needed:
 *     <ul>
 *         <li>`statsSource` (path): the directory containing the statistics subdirectories </li>
 *         <li>`statsDestination` (optional path): the directory where the statistics
 *         data will be copied to; default is `<localWorkingCopyDirectory>/data` </li>
 *     </ul></li>
 *
 *     <li>`pushFlexNLP` (boolean) if true, the program will copy FlexNLP data to the
 *     given repository, and the following will be needed:
 *     <ul>
 *         <li>`flexNLPSource` (path): the directory where the FlexNLP data is saved </li>
 *         <li>`flexNLPDestination` (optional path): the directory where the FlexNLP
 *         data will be copied to; default is `<localWorkingCopyDirectory>/data/flexnlp_pickled` </li>
 *         <li>`zipFlexNLP` (boolean): if true, the FlexNLP data will be zipped and
 *         moved to the flexNLPDestination; else, it will be copied as-is </li>
 *     </ul></li>
 *
 * </ul>
 *
 */

fun main(argv: Array<String>) {
    if (argv.size != 1) {
        throw RuntimeException("Expected a single argument: a parameter file")
    }
    val paramsLoader = SerifStyleParameterFileLoader.Builder()
            .interpolateEnvironmentalVariables(true).build()
    val params = paramsLoader.load(File(argv[0]))

    // Load repo params
    val repoToPushTo = params.getString("repoToPushTo")
    val localWorkingCopyDirectory = File(params.getString("localWorkingCopyDirectory"))
    val dryRun = params.getOptionalBoolean("dryRun").or(false)

    setUpRepository(localWorkingCopyDirectory, repoToPushTo).use { git ->

        // Handle exported data
        val pushExport = params.getBoolean("pushExport")
        if (pushExport) {
            copyProjectFiles(
                    zipToDestination = params.getBoolean("zipExport"),
                    dryRun = dryRun,
                    projectSource = File(params.getExistingDirectory("exportSource").absolutePath),
                    projectDestination = File(params.getOptionalCreatableDirectory("exportDestination")
                        .or(File("$localWorkingCopyDirectory/data/exported")).absolutePath)
            )
        }

        // Handle statistics files
        val pushStats = params.getBoolean("pushStats")
        val statsDirs = listOf("annotation-statistics", "indicator-searches", "time-reports")
        if (pushStats) {
            val statsSource = params.getExistingDirectory("statsSource").absolutePath
            val statsDestination = params.getOptionalCreatableDirectory("statsDestination")
                    .or(File("$localWorkingCopyDirectory/data")).absolutePath
            for (statsDir in statsDirs) {
                val fullStatsDir = "$statsSource/$statsDir"
                if (dryRun) {
                    logCopyInfo(
                            false, File(fullStatsDir), File("$statsDestination/$statsDir")
                    )
                } else {
                    if (File(fullStatsDir).exists()) {
                        File(fullStatsDir)
                                .copyRecursively(File("$statsDestination/$statsDir"), overwrite = true)
                    } else {
                        logger.warn { "$fullStatsDir does not exist; skipping copy" }
                    }
                }
            }
        }

        // Handle FlexNLP files
        val pushFlexNLP = params.getBoolean("pushFlexNLP")
        if (pushFlexNLP) {
            copyProjectFiles(
                    zipToDestination = params.getBoolean("zipFlexNLP"),
                    dryRun = dryRun,
                    projectSource = File(params.getExistingDirectory("flexNLPSource").absolutePath),
                    projectDestination = File(params.getOptionalCreatableDirectory("flexNLPDestination")
                        .or(File("$localWorkingCopyDirectory/data/flexnlp_pickled")).absolutePath)
            )
        }

        if (!dryRun) {
            pushUpdatedAnnotations(git)
        }
    }

    logger.info {"Done!"}
}

fun copyProjectFiles(
        dryRun: Boolean,
        zipToDestination: Boolean,
        projectSource: File,
        projectDestination: File
) {
    if (zipToDestination) {
        if (dryRun) {
            logCopyInfo(true, projectSource, projectDestination)
        }
        else {
            projectSource.walk().filter { it.isDirectory && it != projectSource }
                    .forEach { projectDir ->
                        zipProject(projectDir, projectDestination.toPath())
                    }
        }
    } else {
        if (dryRun) {
            logCopyInfo(false, projectSource, projectDestination)
        } else {
            projectSource.copyRecursively(projectDestination, overwrite = true)
        }
    }
}

fun logCopyInfo(zipToDestination: Boolean, source: File, destination: File) {
    if (zipToDestination) {
        logger.info {
            "All projects of the name " +
                    "$source/Event.Type-username\n" +
                    "would be compressed to " +
                    "$destination/Event.Type-username.zip"
        }
    } else {
        logger.info {
            "All directories of the name " +
                    "$source/Event.Type-username\n" +
                    "and their contents would be saved to " +
                    "$destination/Event.Type-username"
        }
    }
}
