package edu.isi.vista.annotationutils

import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import edu.isi.nlp.parameters.serifstyle.SerifStyleParameterFileLoader
import java.io.File
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.api.TransportConfigCallback
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.transport.*
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.eclipse.jgit.util.FS
import java.text.SimpleDateFormat
import java.util.*

/**
 * The goal of this program is to export the annotations, save the restored
 * documents, compare with the previous commit to see if any changes have been
 * made, and if so, push those changes to the repo.
 *
 * The program takes one argument: a file containing these parameter values:
 *
 * <ul>
 *     <li> {@code exportAnnotationsParams}: the location of the parameter file meant for {@code ExportAnnotations.kt} </li>
 *     <li> {@code restoreJsonParams}: the location of the parameter file meant for {@code RestoreJson.kt} </li>
 *     <li> {@code repoPath}: the location of the local repository
 *     (https://github.com/isi-vista/curated-training-annotation)</li>
 * </ul>
 *
 * This program currently assumes these things:
 * <ul>
 *     <li>The annotations will be saved to .../curated-training-annotation/data/exported</li>
 *     <li>Your ssh private key is saved in ~/.ssh/id_rsa.</li>
 * </ul>
 */


fun pushNew(repoPath: String) {

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

    val git = Git.open(File(repoPath))
    val config = git.getRepository().getConfig()
    config.setString(
            "remote",
            "origin",
            "url",
            "git@github.com:isi-vista/curated-training-annotation.git")
    config.save()

    val currentDate = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
    TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    val formatter = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss")

    // Currently working on test_branch
    git.checkout().setName("test_branch").call()

    git.add().addFilepattern("data/").call()
    val newCommit = git.commit()
            .setMessage("includes annotations from ${currentDate.getTime()}").call()
    git.tag().setName(formatter.format(currentDate.getTime())).call()


    if (hasDiff(git, newCommit)) {
        val pushIter = git.push()
                .setTransportConfigCallback(SshConfig("~/.ssh/id_rsa"))
                .call()
        val pushResult = pushIter.iterator().next()
    } else {
        println("No new annotations - nothing will be pushed.")
        git.reset().setRef("HEAD~1").setMode(ResetCommand.ResetType.SOFT).call()
    }
}

// Compare old data to newly exported data
fun hasDiff(git: Git, newCommit: RevCommit): Boolean {
    val oldCommit = getPrevCommit(git, newCommit)
    if (oldCommit == null) {
        println("This is the first commit.")
        return true
    }
    val oldTree = getCanonicalTreeParser(git, oldCommit)
    val newTree = getCanonicalTreeParser(git, newCommit)
    val diffs: List<DiffEntry> =
            git.diff().setNewTree(newTree).setOldTree(oldTree).call()
    if (diffs.isEmpty()) {
        return false
    }
    return true
}

fun getPrevCommit(git: Git, commit: RevCommit): RevCommit? {
    val walk = RevWalk(git.getRepository())
    walk.markStart(commit)
    var count = 0
    for (rev in walk) {
        if (count ==1) {
            return rev
        }
        count = count.inc()
    }
    return null
}

fun getCanonicalTreeParser(git: Git, commit: ObjectId): CanonicalTreeParser {
    val walk = RevWalk(git.getRepository())
    val revCommit = walk.parseCommit(commit)
    val treeId = revCommit.getTree().getId()
    val reader = git.getRepository().newObjectReader()
    return CanonicalTreeParser(null, reader, treeId)
}


fun main(argv: Array<String>) {
    val paramsLoader = SerifStyleParameterFileLoader.Builder()
            .interpolateEnvironmentalVariables(true).build()
    val params = paramsLoader.load(File(argv[0]))
    val exportAnnotationsParams = params.getString("exportAnnotationsParams")
    val restoreJsonParams = params.getString("restoreJsonParams")
    val repoPath = params.getString("repoPath")

    // Run ExportAnnotations.kt
    ExportAnnotations.main(arrayOf(exportAnnotationsParams))

    // Run RestoreJson.kt
    RestoreJson.main(arrayOf(restoreJsonParams))

    // Push new annotations
    pushNew(repoPath)
}