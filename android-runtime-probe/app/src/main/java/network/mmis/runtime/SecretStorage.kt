package network.mmis.runtime

import android.content.Context
import android.util.AtomicFile
import java.io.File

/** Existing bytes and filenames are retained. Keystore wrapping is a separate migration. */
internal class SecretMaterialStore(context:Context) {
    private val root=File(context.filesDir,"mmis").apply { mkdirs() }
    fun hasIdentity()=File(root,"identity.bin").exists() || File(root,"identity.bin.bak").exists()
    fun installIdentity(bytes:ByteArray) {
        check(!hasIdentity()) {"An identity already exists; it will not be overwritten."}
        require(bytes.size==97)
        val atom=AtomicFile(File(root,"identity.bin"));val out=atom.startWrite()
        try {out.write(bytes);atom.finishWrite(out)} catch(e:Exception) {atom.failWrite(out);throw e}
    }
    fun readOrCreate(name:String,size:Int,create:()->ByteArray):ByteArray {
        require(name in setOf("storage-secret.bin","identity.bin"))
        val file=File(root,name);val atom=AtomicFile(file)
        if(file.exists() || File(file.path+".bak").exists()) return atom.readFully().also { check(it.size==size) }
        val bytes=create();check(bytes.size==size)
        val out=atom.startWrite();try {out.write(bytes);atom.finishWrite(out)} catch(e:Exception) {atom.failWrite(out);bytes.fill(0);throw e}
        return bytes
    }
}
internal class WalletAuthorizationStore(context:Context) {
    private val profile=(context.applicationContext as ProbeApplication).registryNetwork.profile
    private val file=AtomicFile(File(context.filesDir,profile.authFileName))
    fun load():WalletAuthorization?=try { WalletAuthorization.decode(file.readFully().toString(Charsets.UTF_8)) } catch(_:Exception) { null }
    fun save(authorization:WalletAuthorization) {
        val out=file.startWrite()
        try {out.write(authorization.encode().toByteArray());file.finishWrite(out)} catch(e:Exception) {file.failWrite(out);throw e}
    }
    fun clear()=file.delete()
}
