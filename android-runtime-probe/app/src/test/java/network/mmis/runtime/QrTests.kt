package network.mmis.runtime

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.*
import org.junit.Test

class QrTests {
    private val wallet="6fHkwweD2gMMGNXKPvH6tYAVZsL1BW8LadVgPgvVWLcE"
    private val direct=PrivateAddress.encode(PeerIdentity(java.util.Base64.getEncoder().encodeToString(ByteArray(32) {7}),4294967295L))
    @Test fun exactRoundTripForEveryAddressIncludingHighUnsignedToken() {
        for(text in listOf("x0d.skr",wallet,direct)) {
            val matrix=AddressQrGenerator.generate(text,512)
            val pixels=IntArray(matrix.width*matrix.height) { if(matrix[it%matrix.width,it/matrix.width]) 0xff000000.toInt() else -1 }
            val decoded=QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(matrix.width,matrix.height,pixels)))).text
            assertEquals(text,decoded)
            assertFalse(matrix[0,0])
        }
    }
    @Test fun scannerUsesExistingParserAndOnlyTrimsOuterWhitespace() {
        for(text in listOf("x0d.skr",wallet,direct)) {
            val result=qrInput(QrScanResult.Text(" \n$text\t"))
            assertEquals(text,result.address);assertEquals(PrivateAddress.classify(text),PrivateAddress.classify(result.address!!))
        }
        for(text in listOf("hello","https://example.com/x0d.skr","mmis://$direct","{\"address\":\"$wallet\"}","x0d .skr","x0d.SKR")) {
            val result=qrInput(QrScanResult.Text(text));assertNull(result.address)
            assertEquals("This QR code doesn't contain a valid MMIS address.",result.message)
        }
    }
    @Test fun cancellationUnavailableAndEmptyNeverSupplyReplacementInput() {
        for(result in listOf(QrScanResult.Cancelled,QrScanResult.Unavailable,QrScanResult.Text(null),QrScanResult.Text(" \n"))) {
            assertNull(qrInput(result).address);assertTrue(qrInput(result).message.isNotBlank())
        }
    }
}
