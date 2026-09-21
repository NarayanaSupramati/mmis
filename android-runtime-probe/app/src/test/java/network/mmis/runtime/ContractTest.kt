package network.mmis.runtime
import org.junit.Assert.*
import org.junit.Test
class ContractTest {
    @Test fun tokenBoundaries() {
        for((u,s) in listOf(0L to 0,2147483647L to Int.MAX_VALUE,2147483648L to Int.MIN_VALUE,4294967295L to -1)) {
            assertEquals(s,tokenBits(u)); assertEquals(u,logicalToken(s))
        }
        assertThrows(IllegalArgumentException::class.java) { tokenBits(-1) }
        assertThrows(IllegalArgumentException::class.java) { tokenBits(4294967296L) }
    }
    @Test fun statuses() { assertEquals("networkAccepted",statusName(2)); assertEquals("queued",statusName(0)); assertEquals("submitted",statusName(1)); assertEquals("failed",statusName(3)) }
}
