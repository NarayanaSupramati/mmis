package network.mmis.runtime

import android.content.Context
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

// UI/platform adapter; no Activity reference is retained by the result callback.
class QrScanner(context:Context) {
    private val context=context.applicationContext
    fun scan(result:(QrScanResult)->Unit) {
        try {
            if(GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)!=ConnectionResult.SUCCESS) {
                result(QrScanResult.Unavailable);return
            }
            val options=GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).enableAutoZoom().build()
            GmsBarcodeScanning.getClient(context,options).startScan()
                .addOnSuccessListener { result(QrScanResult.Text(it.rawValue)) }
                .addOnCanceledListener { result(QrScanResult.Cancelled) }
                .addOnFailureListener { result(QrScanResult.Unavailable) }
        } catch(_:Exception) { result(QrScanResult.Unavailable) }
    }
}
