package network.mmis.runtime

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp

@Composable fun QrShareButton(text:String,title:String,tag:String,privateAddress:Boolean=false) {
    var showing by rememberSaveable(text) { mutableStateOf(false) }
    val clipboard=LocalClipboardManager.current
    OutlinedButton(onClick={showing=true},modifier=Modifier.testTag(tag)) {Text("Show QR")}
    if(showing) {
        val bitmap=remember(text) {
            runCatching {
                val grid=AddressQrGenerator.generate(text,768)
                val pixels=IntArray(grid.width*grid.height) { if(grid[it%grid.width,it/grid.width]) 0xff000000.toInt() else 0xffffffff.toInt() }
                Bitmap.createBitmap(pixels,grid.width,grid.height,Bitmap.Config.ARGB_8888).asImageBitmap()
            }.getOrNull()
        }
        AlertDialog(onDismissRequest={showing=false},modifier=Modifier.semantics {testTagsAsResourceId=true}.testTag("qr-dialog"),title={Text(title)},
            text={ Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                if(bitmap!=null) Image(bitmap,"$title QR",Modifier.fillMaxWidth().aspectRatio(1f).testTag("qr-image"),filterQuality=FilterQuality.None)
                else Text("QR unavailable. You can still copy the address.")
                Text(text,modifier=Modifier.testTag("qr-text"),style=MaterialTheme.typography.bodySmall)
                Text(if(privateAddress) "Anyone with this private address can message this identity directly." else "This address can be used to discover your public MMIS messaging listing.",style=MaterialTheme.typography.bodySmall)
            } },
            confirmButton={TextButton(onClick={clipboard.setText(AnnotatedString(text))},modifier=Modifier.testTag("qr-copy")) {Text("Copy")}},
            dismissButton={TextButton(onClick={showing=false},modifier=Modifier.testTag("qr-close")) {Text("Close")}})
    }
}
