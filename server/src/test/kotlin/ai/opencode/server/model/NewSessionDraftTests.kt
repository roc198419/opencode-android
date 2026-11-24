package ai.opencode.server.model

import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.server.live.directoryListing
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class NewSessionDraftTests {
    @get:Rule val isolation = TestIsolationRule()

    private val projects = (1..7).map { OpenCodeProject(id = "p$it", name = "project-$it", directory = "/srv/code/project-$it") }

    @Test
    fun aFolderThatIsAKnownProjectChoosesThatProject() {
        assertEquals(projects[2], OpenCodeProject.folder("/srv/code/project-3", projects))
        assertEquals("/", OpenCodeProject.folder("/", projects).name)
    }

    // MARK: Listings

    @Test
    fun listedEntriesResolveAgainstTheServersLocation() {
        // What V2 answered for `?path=/Users` from a server whose default location is /Users/kit.
        val listing = directoryListing(
            location = "/Users/kit", path = "/Users",
            entries = listOf("../Shared/" to "directory", "./" to "directory", "../.localized" to "file", "../.hidden/" to "directory"),
        )
        assertEquals("/Users", listing.directory)
        assertEquals("By name, hidden folders last, no files", listOf("/Users/kit", "/Users/Shared", "/Users/.hidden"), listing.folders)
        val home = directoryListing(location = "/Users/kit", path = null, entries = listOf("code/" to "directory", "Desktop/" to "directory"))
        assertEquals("/Users/kit", home.directory)
        assertEquals(listOf("/Users/kit/code", "/Users/kit/Desktop"), home.folders)
    }

    @Test fun projectFilesKeepFilesAndOnlyImmediateChildrenWithinTheRoot() {
        val listing = ai.opencode.server.live.projectFiles(
            "/srv/app", "/srv/app/src", "/srv",
            listOf("app/src/z10.kt" to "file", "app/src/z2.kt" to "file", "app/src/lib" to "directory",
                "app/src/.env" to "file", "app/src" to "directory", "app/other.txt" to "file",
                "outside/secret" to "file", "app/src/nested/file" to "file", "app/src/link" to "symlink"),
        )
        assertEquals(listOf("lib", "z2.kt", "z10.kt", ".env"), listing.entries.map { it.name })
        assertEquals(true, listing.entries.first().isDirectory)
        assertEquals(false, listing.entries.last().isDirectory)
    }

    @Test fun projectFilePathsUseRemoteWindowsSemantics() {
        assertEquals("C:/work/app/src/main.kt", ProjectFilePaths.resolve("C:/work/app", "src\\main.kt"))
        assertEquals("src/main.kt", ProjectFilePaths.relative("c:/WORK/app", "C:/work/app/src/main.kt"))
        assertEquals(null, ProjectFilePaths.relative("C:/work/app", "C:/work/apple/secret"))
        assertEquals(null, ProjectFilePaths.relative("C:/work/app", "D:/work/app/file"))
        assertEquals("C:/", ProjectFilePaths.parent("C:/file"))
        assertEquals(null, ProjectFilePaths.normalize("/../../etc"))
        assertEquals("/work/app/文件 #1.kt", ProjectFilePaths.resolve("/work/app/src", "../文件 #1.kt"))
        assertEquals(null, ProjectFilePaths.relative("/work/app", "/work/app/../secret"))
    }

    @Test fun projectFilesResolveWindowsEntriesAndDriveRoots() {
        val listing = ai.opencode.server.live.projectFiles("C:/work/app", "C:/work/app/src", "C:/work/app",
            listOf("src\\文件.kt" to "file", "src\\nested" to "directory", "..\\secret" to "file"))
        assertEquals(listOf("C:/work/app/src/nested", "C:/work/app/src/文件.kt"), listing.entries.map { it.path })
    }

    @Test fun folderPickerListingsAlsoUseRemoteWindowsPaths() {
        val listing = directoryListing("C:/work/app", "C:/work", listOf("./" to "directory", "../notes" to "directory", "../file.txt" to "file"))
        assertEquals("C:/work", listing.directory)
        assertEquals(listOf("C:/work/app", "C:/work/notes"), listing.folders)
    }
}
