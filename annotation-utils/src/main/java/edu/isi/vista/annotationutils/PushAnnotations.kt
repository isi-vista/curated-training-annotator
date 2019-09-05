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
 *     `localWorkingCopyDirectory`. The hierarchy beneath it will look like
 *     "projectName/documentName/documentName-userName.json" </li>
 *     <li> `restoreJson`: "true" if you would like to restore the original document text to the annotation
 *     files; if your repository is public, please put "false"; if "true", you must also provide
 *     the following three parameter values:
 *       <ul>
 *         <li> `indexDirectory` is the location of the files produced by `indexFlatGigaword.java`
(https://github.com/isi-vista/nlp-util/blob/master/nlp-core-open/src/main/java/edu/isi/nlp/corpora/gigaword/IndexFlatGigaword.java) </li>
 *         <li> `gigawordDataDirectory` is the location of the gigaword text files </li>
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

class PushAnnotations {
    companion object {
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

            val exportAnnotationsParamsBuilder = Parameters.builder()
            exportAnnotationsParamsBuilder.set("inceptionUrl", params.getString("inceptionUrl"))
            exportAnnotationsParamsBuilder.set("inceptionUsername", params.getString("inceptionUsername"))
            exportAnnotationsParamsBuilder.set("inceptionPassword", params.getString("inceptionPassword"))
            exportAnnotationsParamsBuilder.set("exportedAnnotationRoot","$localWorkingCopyDirectory" + params.getString("exportedAnnotationRoot"))
            val exportAnnotationsParams = exportAnnotationsParamsBuilder.build()

            val restoreJsonParams = if (params.getOptionalBoolean("restoreJson").or(false)) {
                Parameters.builder()
                        .set("indexDirectory", params.getString("indexDirectory"))
                        .set("gigawordDataDirectory", params.getString("gigawordDataDirectory"))
                        .set("inputJsonDirectory", "$localWorkingCopyDirectory" + params.getString("exportedAnnotationRoot"))
                        .set("restoredJsonDirectory", "$localWorkingCopyDirectory" + params.getString("restoredJsonDirectory"))
                        .build()
            } else {
                null
            }

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

                // Push new annotations
                pushUpdatedAnnotations(git)
            }

            logger.info {"Done!"}
        }
    }
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
