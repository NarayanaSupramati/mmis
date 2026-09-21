package network.mmis.runtime

import org.json.JSONObject
import java.util.Base64
import java.security.SecureRandom
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.digests.Blake2bDigest
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters

/** Version 1 wraps the supported xxDK encrypted export, never reconstructs private material. */
object IdentityBackup {
    const val FORMAT="mmis-identity-backup"
    const val MAX_BYTES=16*1024*1024
    const val ITERATIONS=600000
    private val aad="$FORMAT/1/PBKDF2-HMAC-SHA256/AES-256-GCM".toByteArray(Charsets.UTF_8)
    private fun encode(b:ByteArray)=Base64.getEncoder().encodeToString(b)
    private fun decode(s:String)=Base64.getDecoder().decode(s).also {require(encode(it)==s)}
    private fun key(password:CharArray,salt:ByteArray):ByteArray {
        require(password.size in 16..1024) {"Use a password of 16–1024 characters."}
        val spec=PBEKeySpec(password,salt,ITERATIONS,256)
        return try {SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded} finally {spec.clearPassword()}
    }
    fun encrypt(payload:JSONObject,password:CharArray):ByteArray {
        validatePayload(payload)
        val plain=payload.toString().toByteArray(Charsets.UTF_8)
        require(plain.size<=MAX_BYTES/2)
        val salt=ByteArray(16);val nonce=ByteArray(12);SecureRandom().apply {nextBytes(salt);nextBytes(nonce)}
        val k=key(password,salt)
        try {
            val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,SecretKeySpec(k,"AES"),GCMParameterSpec(128,nonce));c.updateAAD(aad)
            val sealed=c.doFinal(plain)
            return JSONObject().put("format",FORMAT).put("version",1).put("kdf","PBKDF2-HMAC-SHA256").put("iterations",ITERATIONS)
                .put("cipher","AES-256-GCM").put("salt",encode(salt)).put("nonce",encode(nonce))
                .put("ciphertext",encode(sealed.copyOfRange(0,sealed.size-16))).put("tag",encode(sealed.takeLast(16).toByteArray()))
                .toString(2).toByteArray(Charsets.UTF_8)
        } finally {k.fill(0);plain.fill(0)}
    }
    fun decrypt(bytes:ByteArray,password:CharArray):JSONObject {
        require(bytes.size in 1..MAX_BYTES)
        val j=JSONObject(bytes.toString(Charsets.UTF_8))
        require(j.getString("format")==FORMAT && j.get("version") is Number && j.get("version").toString()=="1")
        require(j.getString("kdf")=="PBKDF2-HMAC-SHA256" && j.get("iterations") is Number && j.get("iterations").toString()==ITERATIONS.toString() && j.getString("cipher")=="AES-256-GCM")
        val salt=decode(j.getString("salt"));val nonce=decode(j.getString("nonce"));val tag=decode(j.getString("tag"))
        require(salt.size==16 && nonce.size==12 && tag.size==16)
        val k=key(password,salt)
        try {
            val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,SecretKeySpec(k,"AES"),GCMParameterSpec(128,nonce));c.updateAAD(aad)
            val plain=c.doFinal(decode(j.getString("ciphertext"))+tag)
            return try {JSONObject(plain.toString(Charsets.UTF_8)).also(::validatePayload)} finally {plain.fill(0)}
        } finally {k.fill(0)}
    }
    fun validatePayload(j:JSONObject):PeerIdentity {
        require(j.getString("format")==FORMAT && j.get("version") is Number && j.get("version").toString()=="1" && j.getString("xxdkVersion")=="4.7.9")
        java.time.Instant.parse(j.getString("createdAt"))
        val token=j.get("dmToken");require(token is Number && token.toString().matches(Regex("[0-9]+")))
        val peer=PeerIdentity(j.getString("signingPublicKey"),token.toString().toLong())
        require(peer.usable && PrivateAddress.encode(peer)==j.getString("privateAddress"))
        // Fixed native export version/cost prevents an unbounded Argon2 allocation on import.
        val native=j.getString("xxIdentity")
        require(native.startsWith("<xxChannelIdentity(0)") && native.endsWith("xxChannelIdentity>"))
        val body=decode(native.removePrefix("<xxChannelIdentity(0)").removeSuffix("xxChannelIdentity>"))
        require(body.size==163)
        val params=ByteBuffer.wrap(body).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        require(params.getInt(16)==1 && params.getInt(20)==65536 && body[24]==4.toByte())
        return peer
    }
    /** Independent consistency check of the native import; bytes are not modified or constructed. */
    fun derivedEndpoint(identity:ByteArray):PeerIdentity {
        require(identity.size==97 && identity[0]==0.toByte())
        val seed=identity.copyOfRange(1,33)
        try {
            val public=Ed25519PrivateKeyParameters(seed,0).generatePublicKey().encoded
            require(public.contentEquals(identity.copyOfRange(33,65)) && public.contentEquals(identity.copyOfRange(65,97)))
            val hash=ByteArray(32);Blake2bDigest(256).apply {update(seed,0,seed.size);doFinal(hash,0)}
            val token=ByteBuffer.wrap(hash).int.toLong() and 0xffffffffL;hash.fill(0)
            return PeerIdentity(encode(public),token).also {require(it.usable)}
        } finally {seed.fill(0)}
    }
    fun validateIdentity(identity:ByteArray,expected:PeerIdentity) {require(derivedEndpoint(identity)==expected) {"Backup identity does not match its public endpoint."}}
}
