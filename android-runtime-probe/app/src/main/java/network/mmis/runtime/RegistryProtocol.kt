package network.mmis.runtime

import com.solana.publickey.ProgramDerivedAddress
import com.solana.publickey.SolanaPublicKey
import com.solana.transaction.AccountMeta
import com.solana.transaction.TransactionInstruction
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import org.json.JSONObject

data class RegistryDeployment(val chain:String, val genesisHash:String, val programId:String, val protocolVersion:Int) {
    init { require(chain in setOf("solana:devnet","solana:mainnet") && genesisHash.isNotBlank() && protocolVersion==1); SolanaPublicKey.from(programId) }
    val namespace get()="$chain/$genesisHash/$programId"
    companion object {
        fun from(j:JSONObject)=RegistryDeployment(j.getString("chain"),j.getString("genesisHash"),j.getString("programId"),j.getInt("protocolVersion"))
    }
}
data class RegistryEndpoint(val keyBase64:String,val token:Long,val revision:ULong) {
    fun json()=JSONObject().put("signingPublicKey",keyBase64).put("dmToken",token.toString()).put("revision",revision.toString()).put("version",1)
    fun descriptor()=JSONObject().put("signingPublicKey",keyBase64).put("dmToken",token).put("version",1)
}
sealed class RegistryResult(val kind:String) {
    data class Registered(val endpoint:RegistryEndpoint):RegistryResult("Registered")
    data object NotRegistered:RegistryResult("NotRegistered")
    data object UnsupportedVersion:RegistryResult("UnsupportedVersion")
    data object Malformed:RegistryResult("Malformed")
    data object WrongOwner:RegistryResult("WrongOwner")
    data object RpcFailure:RegistryResult("RpcFailure")
}
object RegistryProtocol {
    const val SYSTEM="11111111111111111111111111111111"
    private fun hex(s:String)=s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    val discriminator=hex("3a6155735f174535")
    suspend fun pda(deployment:RegistryDeployment,wallet:String):String = ProgramDerivedAddress.find(
        listOf("mmis-endpoint".toByteArray(Charsets.UTF_8),SolanaPublicKey.from(wallet).bytes),SolanaPublicKey.from(deployment.programId)
    ).getOrThrow().let { com.funkatronics.encoders.Base58.encodeToString(it.bytes) }
    fun decode(expectedPda:String,actualPda:String,program:String,owner:String?,executable:Boolean,bytes:ByteArray?):RegistryResult {
        if(expectedPda!=actualPda) return RegistryResult.Malformed
        if(bytes==null) return RegistryResult.NotRegistered
        if(executable) return RegistryResult.Malformed
        if(owner==SYSTEM && bytes.isEmpty()) return RegistryResult.NotRegistered
        if(owner!=program) return RegistryResult.WrongOwner
        if(bytes.size<9 || !bytes.copyOfRange(0,8).contentEquals(discriminator)) return RegistryResult.Malformed
        if(bytes[8].toInt()!=1) return RegistryResult.UnsupportedVersion
        if(bytes.size!=53) return RegistryResult.Malformed
        val key=bytes.copyOfRange(9,41)
        val b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val token=b.getInt(41).toLong() and 0xffffffffL
        val revision=b.getLong(45).toULong()
        if(key.all { it==0.toByte() } || token==0L || revision==0uL) return RegistryResult.Malformed
        return RegistryResult.Registered(RegistryEndpoint(Base64.getEncoder().encodeToString(key),token,revision))
    }
    fun instructionBytes(operation:String,key:ByteArray=byteArrayOf(),token:Long=0):ByteArray {
        val disc=hex(when(operation) { "register"->"2b7fe037c43f05aa"; "update"->"b5e1148b9dec015e"; "close"->"16305a89fc5dc520"; else->error("Invalid operation") })
        if(operation=="close") return disc
        require(key.size==32 && key.any { it!=0.toByte() } && token in 1..0xffffffffL)
        return disc+key+ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(token.toInt()).array()
    }
    suspend fun instruction(d:RegistryDeployment,wallet:String,operation:String,key:ByteArray=byteArrayOf(),token:Long=0):TransactionInstruction {
        val accounts=mutableListOf(AccountMeta(SolanaPublicKey.from(wallet),true,operation!="update"),AccountMeta(SolanaPublicKey.from(pda(d,wallet)),false,true))
        if(operation=="register") accounts.add(AccountMeta(SolanaPublicKey.from(SYSTEM),false,false))
        return TransactionInstruction(SolanaPublicKey.from(d.programId),accounts,instructionBytes(operation,key,token))
    }
}

object RegistryErrors {
    private val codes=mapOf(6000 to "ENDPOINT_ALREADY_REGISTERED",6001 to "ENDPOINT_NOT_REGISTERED",6002 to "ENDPOINT_NO_CHANGE",6003 to "ENDPOINT_REVISION_OVERFLOW",6004 to "ENDPOINT_INVALID_KEY",6005 to "ENDPOINT_INVALID_TOKEN",6006 to "ENDPOINT_UNSUPPORTED_VERSION",6007 to "ENDPOINT_MALFORMED")
    fun normalize(error:Any?):String {
        val instruction=(error as? JSONObject)?.optJSONArray("InstructionError")
        val custom=instruction?.optJSONObject(1)?.optInt("Custom",-1)
        return codes[custom] ?: "TRANSACTION_FAILED"
    }
}
