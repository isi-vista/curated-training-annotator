package edu.isi.vista.annotationutils

import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import edu.isi.nlp.parameters.Parameters
import edu.isi.nlp.parameters.serifstyle.SerifStyleParameterFileLoader
import java.io.File
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.api.TransportConfigCallback
import org.eclipse.jgit.transport.*
import org.eclipse.jgit.util.FS
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * The goal of this program is to export the annotations, save the restored
 * documents, compare with the previous commit to see if any changes have been
 * made, and if so, push those changes to the Git repository specified by
 * the parameter `repoToPushTo`.
 *
 * The program takes one argument: a file containing these parameter values:
 *
 * <ul>
 *     <li> `inceptionUrl` is the base URL of your Inception server. </li>
 *     <li> `inceptionUsername` is the user name of an Inception user with access to the
 *          remote API. This privilege must be granted in the Inception administration interface.
 *          </li>
 *     <li> `inceptionPassword` is the password for the user above. Note that parameter files can
 *          interpolate from environmental variables </li>
 *     <li> `exportedAnnotationRoot` is the directory to download annotation to within
 *          `localWorkingCopyDirectory`. The hierarchy beneath it will look like
 *          "projectName/documentName/documentName-userName.json" </li>
 *     <li> `usernameJson`: (optional) the JSON mapping from usernames to their respective
 *          alternate names; use this if you want to change the annotator's name
 *          to something other than their username when saving the project files,
 *          e.g. if the username contains info that should not be shared. </li>
 *     <li> `compressOutput` (optional) determines whether the project directories
 *          will be copied to zip archives. The default is false. </li>
 *     <li> `zipExportRoot` (optional) is the directory where the zipped output will be saved.
 *          If no value is given, the output will not have a compressed version. </li>
 *
 *     <li> `restoreJson`: "true" if you would like to restore the original document text to the annotation
 *          files; if your repository is public, please put "false"; if "true", you must also provide
 *          the following three parameter values:
 *       <ul>
 *
 *         <li> `indexDirectory` is the location of the files produced by `indexFlatGigaword.java`
(https://github.com/isi-vista/nlp-util/blob/master/nlp-core-open/src/main/java/edu/isi/nlp/corpora/gigaword/IndexFlatGigaword.java) </li>
 *         <li> `aceEngDataDirectory` is the location of the ACE English corpus files
 *         (ace_2005_td_v7_LDC2006T06/data/English) </li>
 *         <li> `gigawordDataDirectory` is the location of the gigaword text files </li>
 *         <li> `restoredJsonDirectory` is where the new json files will go </li>
 *
 *         <li> `annotatedFlexNLPOutputDir` is the directory where the annotated FlexNLP documents will be saved</li>
 *         <li> `compressFlexNLPOutput` (optional) determines whether the FlexNLP project directories
 *         will be copied to zip archives. The default is False. </li>
 *         <li> `zipFlexNLPOutputDir` (optional) is the directory where the zipped FlexNLP output
 *         will be saved. If no value is given, the output will not have a compressed version. </li>
 *         <li> `ingesterParametersDirectory` is where the parameters file for `curated_training_ingester.py` will
 *          be saved</li>
 *         <li> `pythonPath` is the path to the python interpreter that will be used to run
 *         `curated_training_ingester.py`</li>
 *         <li> `curatedTrainingIngesterPath` is the path to `curated_training_ingester.py`</li>
 *
 *       </ul>
 *     </li>
 *
 *     <li> `statisticsDirectory` is the directory where the annotation statistics reports
 *     will be saved</li>
 *     <li> `repoToPushTo`: the ssh url of the repository to which the annotation data
 *     will be pushed; for ISI's curated training work, this is
 *     git@github.com:isi-vista/curated-training-annotation.git,
 *     but this will differ for other projects </li>
 *     <li> `localWorkingCopyDirectory`: the location of the local repository; the repository
 *     specified by `repoToPushTo` will be cloned to this location if it does not exist here</li>
 * </ul>
 *
 * Precondition: Your ssh private key is saved in ~/.ssh/id_rsa.
 * Postcondition: The exported annotations will be saved within a folder named `data/` in the local
 * working directory.
 */

fun main(argv: Array<String>) {
    if (argv.size != 1) {
        throw RuntimeException("Expected a single argument: a parameter file")
    }
    val paramsLoader = SerifStyleParameterFileLoader.Builder()
            .interpolateEnvironmentalVariables(true).build()
    val params = paramsLoader.load(File(argv[0]))

    /**
     * WARNING: while the following line is intended for debugging purposes, it will print the
     * interpolated Inception password to the log. If this is of concern to you, we recommend
     * removing this line before running the code.
     */
    logger.info {params.dump()}

    val repoToPushTo = params.getString("repoToPushTo")
    val localWorkingCopyDirectory = File(params.getString("localWorkingCopyDirectory"))

    val exportedAnnotationRoot = params.getCreatableDirectory("exportedAnnotationRoot").absolutePath
    val usernameJson = params.getOptionalExistingFile("usernameJson").orNull()
    val hasUsernameJson = usernameJson != null

    // Build params for exporting the annotations
    val exportAnnotationsParamsBuilder = Parameters.builder()
    exportAnnotationsParamsBuilder.set("inceptionUrl", params.getString("inceptionUrl"))
    exportAnnotationsParamsBuilder.set("inceptionUsername", params.getString("inceptionUsername"))
    exportAnnotationsParamsBuilder.set("inceptionPassword", params.getString("inceptionPassword"))
    exportAnnotationsParamsBuilder.set("exportedAnnotationRoot", exportedAnnotationRoot)
    if (hasUsernameJson) {
        exportAnnotationsParamsBuilder.set("usernameJson", usernameJson!!.absolutePath)
    }
    if (params.isPresent("compressOutput")) {
        exportAnnotationsParamsBuilder.set(
                "compressOutput", params.getBoolean("compressOutput").toString()
        )
    }
    if (params.isPresent("zipExportRoot")) {
        exportAnnotationsParamsBuilder.set(
                "zipExportRoot", params.getCreatableDirectory("zipExportRoot").absolutePath
        )
    }
    val exportAnnotationsParams = exportAnnotationsParamsBuilder.build()

    // Build params for restoring the original text
    val restoreJson = params.getOptionalBoolean("restoreJson").or(false)
    val restoreJsonParams = if (restoreJson) {
        Parameters.builder()
                .set("indexDirectory", params.getExistingDirectory("indexDirectory").absolutePath)
                .set("aceEngDataDirectory", params.getExistingDirectory("aceEngDataDirectory").absolutePath)
                .set("gigawordDataDirectory", params.getExistingDirectory("gigawordDataDirectory").absolutePath)
                .set("inputJsonDirectory", exportedAnnotationRoot)
                .set("restoredJsonDirectory", params.getCreatableDirectory("restoredJsonDirectory").absolutePath)
                .build()
    } else {
        null
    }

    // Build params for extracting the annotation statistics
    val extractAnnotationStatsParamsBuilder = Parameters.builder()
    extractAnnotationStatsParamsBuilder.set("exportedAnnotationRoot", exportedAnnotationRoot)
    if (hasUsernameJson) {
        extractAnnotationStatsParamsBuilder.set(
                "originalLogsRoot", params.getExistingDirectory("originalLogsRoot").absolutePath
        )
    }
    extractAnnotationStatsParamsBuilder.set(
            "indicatorSearchesRoot", params.getCreatableDirectory("indicatorSearchesRoot").absolutePath
    )
    extractAnnotationStatsParamsBuilder.set(
            "timeReportRoot", params.getCreatableDirectory("timeReportRoot").absolutePath
    )
    if (hasUsernameJson) {
        extractAnnotationStatsParamsBuilder.set("usernameJson", usernameJson!!.absolutePath)
    }
    extractAnnotationStatsParamsBuilder.set(
            "statisticsDirectory", params.getCreatableDirectory("statisticsDirectory").absolutePath
    )
    val extractAnnotationStatsParams = extractAnnotationStatsParamsBuilder.build()

    setUpRepository(localWorkingCopyDirectory, repoToPushTo).use { git ->

        // Run ExportAnnotations.kt
        logger.info { "Beginning ExportAnnotations" }
        ExportAnnotations.export(exportAnnotationsParams)

        // If user requests, run RestoreJson.kt
        if (restoreJsonParams != null) {
            logger.info { "Beginning RestoreJson" }
            RestoreJson.restore(restoreJsonParams)
        } else {
            logger.info { "Skipping RestoreJson" }
        }

        // Get annotation statistics
        logger.info { "Collecting annotation statistics"}
        ExtractAnnotationStats.extractStats(extractAnnotationStatsParams)

        // Get FlexNLP documents
        // only if the original text has been restored
        if (restoreJson) {
            // Assemble params for converting the JSON to FlexNLP documents
            val curatedTrainingIngesterParamsBuilder = Parameters.builder()
            curatedTrainingIngesterParamsBuilder.set(
                    "input_annotation_json_dir",
                    params.getCreatableDirectory("restoredJsonDirectory").absolutePath
            )
            curatedTrainingIngesterParamsBuilder.set(
                    "annotated_flexnlp_output_dir",
                    params.getCreatableDirectory("annotatedFlexNLPOutputDir").absolutePath
            )
            if (params.isPresent("compressFlexNLPOutput")) {
                curatedTrainingIngesterParamsBuilder.set(
                        "compress_output",
                        params.getBoolean("compressFlexNLPOutput").toString()
                )
            }
            if (params.isPresent("zipFlexNLPOutputDir")) {
                curatedTrainingIngesterParamsBuilder.set(
                        "zip_dir",
                        params.getCreatableDirectory("zipFlexNLPOutputDir").absolutePath
                )
            }
            val curatedTrainingIngesterParams = curatedTrainingIngesterParamsBuilder.build()
            val ingesterParametersDir = params.getCreatableDirectory("ingesterParametersDirectory")
                    .toPath()
                    .resolve("curated_training_ingester_params.yaml")
                    .toFile()
            val pythonPath = params.getExistingFile("pythonPath")
            val curatedTrainingIngesterPath = params.getExistingFile("curatedTrainingIngesterPath")
            ingesterParametersDir.bufferedWriter().use { out ->
                out.write(curatedTrainingIngesterParams.dump())
            }
            val execString = "$pythonPath" +
                    " $curatedTrainingIngesterPath" +
                    " $ingesterParametersDir"

            // Execute the curated training ingester
            execString.runCommand()
        }

        // Push new annotations
        pushUpdatedAnnotations(git)
    }

    logger.info {"Done!"}
}


/**
 * Ensures that `localWorkingCopyDirectory` is a checkout of `repoToPushTo` in a clean state.
 *
 * This method first checks whether or not `localWorkingCopyDirectory` currently exists.
 * If it does exist, it will initialize git and set the local repository to this directory.
 * It will then perform three checks:
 * <ol>
 *     <li> That the remote URL is the same as the URL of the corresponding remote repository </li>
 *     <li> That the status is clean; no local modifications should exist before exporting
 *     the annotations here </li>
 *     <li> That it is currently on the master branch </li>
 * </ol>
 * If any of these checks fail, a RuntimeException will be thrown.
 * If it passes the checks, it will then be synced to the remote repository.
 * If it does not exist, `repoToPushTo` will be cloned to the location specified by
 * `localWorkingCopyDirectory`
 */
fun setUpRepository(localWorkingCopyDirectory: File, repoToPushTo: String): Git {
    if (localWorkingCopyDirectory.isDirectory()) {
        val git = Git.open(localWorkingCopyDirectory)
        val workingCopyRemoteUrl = git.getRepository()
                .getConfig()
                .getString("remote", "origin", "url")

        if (workingCopyRemoteUrl != repoToPushTo){
            throw RuntimeException("$localWorkingCopyDirectory's remote URL ($workingCopyRemoteUrl)" +
                    "does not match that of the remote repository ($repoToPushTo).")
        }

        val status = git.status().call()
        if (!status.isClean()) {
            throw RuntimeException("$localWorkingCopyDirectory has been modified locally.")
        }

        val currentBranch = git.getRepository().getBranch()
        if (currentBranch != "master") {
            throw RuntimeException("$localWorkingCopyDirectory is not on the master branch.")
        }

        git.fetch().setRemote("origin").call()
        git.reset().setRef("origin/master").setMode(ResetCommand.ResetType.HARD).call()

        return git

    } else {
        return Git.cloneRepository()
                .setURI(repoToPushTo)
                .setDirectory(localWorkingCopyDirectory)
                .call()
    }
}

/**
 * Run external command
 *
 * This is used to run `curated_training_ingester.py`.
 * Source: https://stackoverflow.com/questions/35421699/how-to-invoke-external-command-from-within-kotlin-code
 */
fun String.runCommand() {
    ProcessBuilder(*split(" ").toTypedArray())
            .redirectOutput(ProcessBuilder.Redirect.INHERIT)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()
            .waitFor(60, TimeUnit.MINUTES)
}

/**
 * Pushes updated files to the remote repository
 *
 * It will first get the status of the local repo and then check if it is clean.
 * If the status is not clean, the new and modified files will be committed and pushed.
 */
fun pushUpdatedAnnotations(git: Git) {

    // Source: https://github.com/kittinunf/Kotlin-Playground/blob/master/buildSrc/src/main/java/com/github/kittinunf/gradle/ReleaseToMasterMergeTask.kt
    class SshConfig(val sshKeyPath: String): TransportConfigCallback {
        override fun configure(transport: Transport?) {
            (transport as SshTransport).sshSessionFactory = object: JschConfigSessionFactory() {
                override fun configure(hc: OpenSshConfig.Host?, session: Session?) {}
                override fun createDefaultJSch(fs: FS?): JSch = super.createDefaultJSch(fs).apply {
                    addIdentity(sshKeyPath)

                }
            }
        }
    }

    val status = git.status().call()

    // A clean status indicates no updates
    if (!status.isClean()) {
        val currentDate = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        val formatter = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss")

        git.add().addFilepattern("data/").call()
        git.commit()
                .setMessage("includes annotations through ${currentDate.getTime()}").call()
        val tag = git.tag().setName(formatter.format(currentDate.getTime())).call()
        logger.info {"Pushing updated data"}
        // This first push ensures that the tag gets pushed along with the commit
        git.push().add(tag)
                .setTransportConfigCallback(SshConfig("~/.ssh/id_rsa"))
                .call()
        val iterable = git.push()
                .setTransportConfigCallback(SshConfig("~/.ssh/id_rsa"))
                .call()
        val pushResult = iterable.iterator().next()
        val pushUpdate = pushResult.getRemoteUpdate("refs/heads/master")
        val pushStatus = pushUpdate.getStatus()
        if (pushStatus == RemoteRefUpdate.Status.OK) {
            logger.info {"Push was successful"}
        } else {
            throw RuntimeException("Push failed with status: $pushStatus.")
        }
    } else {
        logger.info {"No updates - nothing will be pushed"}
    }
}
