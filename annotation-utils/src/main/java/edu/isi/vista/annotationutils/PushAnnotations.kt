package edu.isi.vista.annotationutils

import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import edu.isi.nlp.parameters.Parameters
import edu.isi.nlp.parameters.serifstyle.SerifStyleParameterFileLoader
import mu.KLogging
import java.io.File
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.api.TransportConfigCallback
import org.eclipse.jgit.transport.*
import org.eclipse.jgit.util.FS
import java.text.SimpleDateFormat
import java.util.*

val pushLogger = KLogging().logger

fun main(argv: Array<String>) {
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
     *     <li> `exportedAnnotationRoot` is the directory to download annotation to.  The
     *     hierarchy beneath it will look like "projectName/documentName/documentName-userName.json" </li>
     *     <li> `restoreJson`: "yes" if you would like to restore the original document text to the annotation
     *     files; if your repository is public, please put "no"; if "yes", you must also provide
     *     the following four parameter values:
     *       <ul>
     *         <li> `indexDirectory` is the location of the files produced by `indexFlatGigaword.java`
    (https://github.com/isi-vista/nlp-util/blob/master/nlp-core-open/src/main/java/edu/isi/nlp/corpora/gigaword/IndexFlatGigaword.java) </li>
     *         <li> `gigawordDataDirectory` is the location of the gigaword text files </li>
     *         <li> `inputJsonDirectory` is the location of the stripped json files produced by `ExportAnnotations.kt` </li>
     *         <li> `restoredJsonDirectory` is where the new json files will go </li>
     *       </ul>
     *     </li>
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
    if (argv.size != 1) {
        throw RuntimeException("Expected a single argument: a parameter file")
    }
    val paramsLoader = SerifStyleParameterFileLoader.Builder()
            .interpolateEnvironmentalVariables(true).build()
    val params = paramsLoader.load(File(argv[0]))
    val exportAnnotationsParamsBuilder = Parameters.builder()
    exportAnnotationsParamsBuilder.set("inceptionUrl", params.getString("inceptionUrl"))
    exportAnnotationsParamsBuilder.set("inceptionUsername", params.getString("inceptionUsername"))
    exportAnnotationsParamsBuilder.set("inceptionPassword", params.getString("inceptionPassword"))
    exportAnnotationsParamsBuilder.set("exportedAnnotationRoot", params.getString("exportedAnnotationRoot"))
    val exportAnnotationsParams = exportAnnotationsParamsBuilder.build()

    val restoreJson = params.getString("restoreJson") == "yes"
    val restoreJsonParamsBuilder = Parameters.builder()
    if (restoreJson) {
        restoreJsonParamsBuilder.set("indexDirectory", params.getString("indexDirectory"))
        restoreJsonParamsBuilder.set("gigawordDataDirectory", params.getString("gigawordDataDirectory"))
        restoreJsonParamsBuilder.set("inputJsonDirectory", params.getString("inputJsonDirectory"))
        restoreJsonParamsBuilder.set("restoredJsonDirectory", params.getString("restoredJsonDirectory"))
    }
    val restoreJsonParams = restoreJsonParamsBuilder.build()

    val repoToPushTo = params.getString("repoToPushTo")
    val localWorkingCopyDirectory = params.getString("localWorkingCopyDirectory")

    val git = setUpRepository(localWorkingCopyDirectory, repoToPushTo)

    // Run ExportAnnotations.kt
    pushLogger.info {"Beginning ExportAnnotations"}
    ExportAnnotations.export(exportAnnotationsParams)

    // Run RestoreJson.kt
    if (restoreJson) {
        pushLogger.info { "Beginning RestoreJson" }
        RestoreJson.restore(restoreJsonParams)
    }

    // Push new annotations
    pushUpdatedAnnotations(git)
    pushLogger.info {"Done!"}
}

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
                .setMessage("includes annotations from ${currentDate.getTime()}").call()
        git.tag().setName(formatter.format(currentDate.getTime())).call()
        pushLogger.info {"Pushing updated data"}
        git.push()
                .setTransportConfigCallback(SshConfig("~/.ssh/id_rsa"))
                .call()
    } else {
        pushLogger.info {"No updates - nothing will be pushed"}
    }
}

fun setUpRepository(localWorkingCopyDirectory: String, repoToPushTo: String): Git {
    /**
     * Check if `localWorkingCopyDirectory` exists.
     *   If it does: check the state
     *   If not: clone `repoToPushTo` to the location specified by `localWorkingCopyDirectory`
     */
    if (File(localWorkingCopyDirectory).isDirectory()) {
        val git = Git.open(File(localWorkingCopyDirectory))
        val remoteUrl = git.getRepository()
                .getConfig()
                .getString("remote", "origin", "url")
        // Does remote URL match `repoToPushTo`?
        if (remoteUrl != repoToPushTo){
            throw RuntimeException("$localWorkingCopyDirectory's remote URL ($remoteUrl)" +
                    "does not match that of the remote repository ($repoToPushTo).")
        }
        // Is the status clean?
        val status = git.status().call()
        if (!status.isClean()) {
            throw RuntimeException("$localWorkingCopyDirectory has been modified locally.")
        }
        // Is it on the master branch?
        val currentBranch = git.getRepository().getBranch()
        if (currentBranch != "master") {
            throw RuntimeException("$localWorkingCopyDirectory is not on the master branch.")
        }
        // Sync to remote master
        git.fetch().setRemote("origin").call()
        git.reset().setRef("origin/master").setMode(ResetCommand.ResetType.HARD).call()

        return git

    } else {
        return Git.cloneRepository()
                .setURI(repoToPushTo)
                .setDirectory(File(localWorkingCopyDirectory))
                .call()
    }
}
