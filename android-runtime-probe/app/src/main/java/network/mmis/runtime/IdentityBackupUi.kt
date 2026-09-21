@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
package network.mmis.runtime

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class IdentityBackupActivity:ComponentActivity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val app=application as ProbeApplication
        setContent {MmisTheme {Surface {BackupScreen(app) {finish()}}}}
    }
}

@Composable fun IdentitySetup(runtime:XxRuntime) {
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    var busy by remember {mutableStateOf(false)};var problem by remember {mutableStateOf<String?>(null)}
    Column(Modifier.fillMaxWidth().padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("Your messaging identity",style=MaterialTheme.typography.titleLarge)
        Text("Restore your existing private address, or create a new one. Your Solana wallet is separate.")
        Button(onClick={context.startActivity(Intent(context,IdentityBackupActivity::class.java))},enabled=!busy,modifier=Modifier.testTag("setup-restore")) {Text("Restore existing messaging identity")}
        OutlinedButton(onClick={busy=true;scope.launch {try {runtime.createIdentity()} catch(_:Exception) {problem="Could not initialize. Your stored identity is retained; retry connecting."} finally {busy=false}}},enabled=!busy,modifier=Modifier.testTag("setup-create")) {Text("Create new identity")}
        problem?.let {Text(it)}
    }
}

@Composable fun BackupIdentityButton() {
    val context=LocalContext.current
    OutlinedButton(onClick={context.startActivity(Intent(context,IdentityBackupActivity::class.java))},modifier=Modifier.testTag("identity-backup")) {Text("Messaging identity backup / restore")}
}

@Composable private fun BackupScreen(app:ProbeApplication,done:()->Unit) {
    val scope=rememberCoroutineScope();val resolver=app.contentResolver
    // Not rememberSaveable: passwords never enter saved state or preferences.
    var password by remember {mutableStateOf("")};var repeat by remember {mutableStateOf("")}
    var busy by remember {mutableStateOf(false)};var message by remember {mutableStateOf("")}
    var pending by remember {mutableStateOf<ByteArray?>(null)};var candidate by remember {mutableStateOf<PeerIdentity?>(null)}
    var existing by remember {mutableStateOf(!app.runtime.needsIdentity)}
    fun work(block:suspend (CharArray)->Unit) {
        val secret=password.toCharArray();busy=true
        scope.launch {
            try {block(secret)} catch(_:Exception) {message="Backup operation failed. Check the password and file. Existing identity and history are not overwritten."}
            finally {secret.fill('\u0000');password="";repeat="";busy=false}
        }
    }
    val save=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) {uri->
        if(uri!=null) work {secret->
            val encrypted=app.runtime.exportBackup(secret)
            withContext(Dispatchers.IO) {resolver.openOutputStream(uri,"wt")!!.use {it.write(encrypted)}}
            message="Encrypted identity and local history saved. Keep the file and password separately until migration and demo validation are complete."
        }
    }
    val open=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {uri->
        if(uri!=null) {
            val secret=password.toCharArray();busy=true
            scope.launch {
                try {
                    val bytes=withContext(Dispatchers.IO) {resolver.openInputStream(uri)!!.use {input->
                        val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
                        while(true) {val n=input.read(buffer);if(n<0) break;require(out.size()+n<=IdentityBackup.MAX_BYTES);out.write(buffer,0,n)};out.toByteArray()
                    }}
                    candidate=app.runtime.inspectBackup(bytes,secret);pending=bytes;existing=!app.runtime.needsIdentity
                } catch(_:Exception) {message="Unable to verify backup. Check its password and contents.";password=""}
                finally {secret.fill('\u0000');busy=false}
            }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp).semantics {testTagsAsResourceId=true},verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("Messaging identity backup",style=MaterialTheme.typography.headlineSmall)
        Text("Preview / Advanced. This backup can control your private messaging identity. Keep it secret. It includes local messages, contacts and aliases; it does not include your wallet or wallet authorization.")
        OutlinedTextField(password,{password=it},label={Text("Backup password (at least 16 characters)")},visualTransformation=PasswordVisualTransformation(),singleLine=true,enabled=!busy,modifier=Modifier.testTag("backup-password"))
        OutlinedTextField(repeat,{repeat=it},label={Text("Repeat password for backup")},visualTransformation=PasswordVisualTransformation(),singleLine=true,enabled=!busy,modifier=Modifier.testTag("backup-password-repeat"))
        Button(onClick={save.launch("mmis-identity-backup.json")},enabled=!busy&&existing&&password.length>=16&&password==repeat,modifier=Modifier.testTag("backup-save")) {Text("Back up messaging identity")}
        OutlinedButton(onClick={open.launch(arrayOf("application/json","application/octet-stream"))},enabled=!busy&&password.length>=16,modifier=Modifier.testTag("backup-open")) {Text("Restore messaging identity")}
        Text(message,modifier=Modifier.testTag("backup-result"))
        TextButton(onClick=done,enabled=!busy) {Text("Back")}
    }
    candidate?.let {peer->
        val current=runCatching {org.json.JSONObject(app.runtime.snapshot).optJSONObject("descriptor")?.let {PrivateAddress.encode(PeerIdentity.from(it))}}.getOrNull()
        AlertDialog(onDismissRequest={candidate=null;pending=null;password=""},title={Text(if(existing) "Existing identity is protected" else "Restore this identity?")},
            text={Text("Current endpoint: ${current ?: if(existing) "Stored locally (not initialized)" else "None"}\n\nBackup endpoint: ${PrivateAddress.encode(peer)}\n\n"+
                if(existing) "Restore will not overwrite an existing identity or merge history. Use a fresh installation after making and verifying a backup of this device. No changes will be made."
                else "Your original messages, contacts and aliases will be restored. Your wallet will need to be connected again.")},
            confirmButton={TextButton(onClick={
                if(existing) {candidate=null;pending=null;password=""}
                else {val bytes=pending!!;candidate=null;pending=null;work {secret->app.runtime.restoreBackup(bytes,secret);existing=true;message="Identity and history restored. Connecting…";app.runtime.command("startup")}}
            },modifier=Modifier.testTag("backup-confirm")) {Text(if(existing) "Keep current identity" else "Restore exact identity")}},
            dismissButton={TextButton(onClick={candidate=null;pending=null;password=""}) {Text("Cancel")}})
    }
}
