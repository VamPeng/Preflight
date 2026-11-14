package gitviz.git

import kotlin.test.*

class LfsTest {
    private val oid = "a".repeat(64)
    private fun pointer(size: String = "12345") = "version https://git-lfs.github.com/spec/v1\noid sha256:$oid\nsize $size\n"
    @Test fun `valid pointers require canonical encoding and bounded size`() {
        assertEquals(LfsPointer(oid, 12345), parseLfsPointer(pointer().toByteArray()))
        assertNotNull(parseLfsPointer(pointer().replace("oid ", "ext-0-test abc\noid ").toByteArray()))
        listOf(pointer("-1"), pointer("01"), pointer("999999999999999999999"), pointer().replace("a", "A"), pointer().replace("\n", "\r\n"), pointer().trimEnd(), pointer() + "size 1\n", "x".repeat(1024)).forEach { assertNull(parseLfsPointer(it.toByteArray()), it) }
    }
}
