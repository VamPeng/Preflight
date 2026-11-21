package gitviz.logic

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReadmeTest {
    private fun dir(): File = Files.createTempDirectory("gitviz-readme").toFile().also { it.deleteOnExit() }

    @Test fun `root markdown wins over other readmes`() {
        val root = dir()
        try {
            File(root, "readme.txt").writeText("plain")
            File(root, "README").writeText("bare")
            File(root, "docs").mkdir(); File(root, "docs/README.md").writeText("# docs")
            File(root, "README.md").writeText("# Root")
            assertEquals("README.md", findReadme(root)?.name)
            File(root, "README.md").delete()
            assertEquals("README", findReadme(root)?.name)
        } finally { root.deleteRecursively() }
    }

    @Test fun `falls back to github then docs and ignores directories`() {
        val root = dir()
        try {
            File(root, "README.md").mkdir()
            File(root, "docs").mkdir(); File(root, "docs/README.md").writeText("# docs")
            assertEquals("docs/README.md", findReadme(root)?.relativeTo(root)?.invariantSeparatorsPath)
            File(root, ".github").mkdir(); File(root, ".github/readme.markdown").writeText("# gh")
            assertEquals(".github/readme.markdown", findReadme(root)?.relativeTo(root)?.invariantSeparatorsPath)
            assertNull(findReadme(dir()))
        } finally { root.deleteRecursively() }
    }

    @Test fun `chinese interface prefers a chinese readme`() {
        val root = dir()
        try {
            File(root, "README.md").writeText("# English")
            File(root, "README.zh-CN.md").writeText("# 中文")
            assertEquals("README.md", findReadme(root)?.name)
            assertEquals("README.zh-CN.md", findReadme(root, preferChinese = true)?.name)
            File(root, "README.zh-CN.md").delete()
            assertEquals("README.md", findReadme(root, preferChinese = true)?.name)
        } finally { root.deleteRecursively() }
    }

    @Test fun `loads markdown, plain text and truncates big files`() {
        val root = dir()
        try {
            File(root, "README.md").writeText("﻿# Hello\n\nworld")
            val md = assertNotNull(loadReadme(root))
            assertEquals("README.md", md.path)
            assertEquals(2, md.markdown?.size)
            assertTrue(!md.truncated)

            File(root, "README.md").delete()
            File(root, "README.txt").writeText("*not markdown*")
            val plain = assertNotNull(loadReadme(root))
            assertNull(plain.markdown)
            assertEquals("*not markdown*", plain.text)

            File(root, "README.txt").writeText("中".repeat(TEXT_PREVIEW_MAX_BYTES / 3 + 10))
            val big = assertNotNull(loadReadme(root))
            assertTrue(big.truncated)
            assertTrue(big.text.all { it == '中' }, "a cut multi-byte character is dropped")

            File(root, "README.txt").writeText("  \n")
            assertNull(loadReadme(root), "blank readme shows commit details instead")
        } finally { root.deleteRecursively() }
    }
}
