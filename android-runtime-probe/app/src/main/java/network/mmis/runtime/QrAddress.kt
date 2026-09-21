package network.mmis.runtime

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

sealed interface QrScanResult {
    data class Text(val value:String?):QrScanResult
    data object Cancelled:QrScanResult
    data object Unavailable:QrScanResult
}
data class QrInput(val address:String?=null,val message:String="")

// No QR-specific address grammar: use the same parser as manual entry.
fun qrInput(result:QrScanResult):QrInput = when(result) {
    QrScanResult.Cancelled->QrInput(message="Scan cancelled")
    QrScanResult.Unavailable->QrInput(message="Scanner unavailable. Google Play services may need to download or update the scanner. Try again with internet access, or enter an address manually.")
    is QrScanResult.Text->{
        val text=result.value?.trim()
        if(text.isNullOrEmpty()) QrInput(message="No QR result. Try again or enter an address manually.")
        else if(runCatching { PrivateAddress.classify(text) }.isSuccess) QrInput(address=text)
        else QrInput(message="This QR code doesn't contain a valid MMIS address.")
    }
}
interface QrCodeGenerator { fun generate(text:String,sizePx:Int):BitMatrix }
object AddressQrGenerator:QrCodeGenerator {
    override fun generate(text:String,sizePx:Int):BitMatrix {
        require(text==text.trim());PrivateAddress.classify(text)
        require(sizePx in 128..2048)
        return QRCodeWriter().encode(text,BarcodeFormat.QR_CODE,sizePx,sizePx,mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",EncodeHintType.MARGIN to 4,
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M))
    }
}
