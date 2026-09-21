package network.mmis.runtime

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import org.json.JSONArray
import kotlinx.coroutines.runBlocking

class RegistryTests {
    @Test fun structuredErrors() {
        assertEquals("ENDPOINT_NO_CHANGE",RegistryErrors.normalize(JSONObject("{\"InstructionError\":[0,{\"Custom\":6002}]}")))
        assertEquals("TRANSACTION_FAILED",RegistryErrors.normalize(JSONObject("{\"InstructionError\":[0,\"MissingRequiredSignature\"]}")))
    }
    private fun resource(name:String)=javaClass.classLoader!!.getResource(name)!!.readText()
    private fun hex(s:String)=s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private val vectors get()=JSONObject(resource("test-vectors.json"))
    private fun decode(b:ByteArray?)=RegistryProtocol.decode("P","P","R","R",false,b)
    @Test fun allFrozenAccountFixtures() {
        val v=vectors
        for(i in 0 until v.getJSONArray("valid").length()) {
            val row=v.getJSONArray("valid").getJSONObject(i)
            val r=decode(hex(row.getString("hex"))) as RegistryResult.Registered
            assertEquals(row.getLong("dmToken"),r.endpoint.token)
            assertEquals(row.getString("revision"),r.endpoint.revision.toString())
        }
        for(i in 0 until v.getJSONArray("invalid").length()) {
            val row=v.getJSONArray("invalid").getJSONObject(i)
            assertEquals(row.getString("name"),if(row.getString("expected")=="ENDPOINT_UNSUPPORTED_VERSION") RegistryResult.UnsupportedVersion else RegistryResult.Malformed,decode(hex(row.getString("hex"))))
        }
    }
    @Test fun readEnvelope() {
        assertEquals(RegistryResult.NotRegistered,decode(null))
        assertEquals(RegistryResult.NotRegistered,RegistryProtocol.decode("P","P","R",RegistryProtocol.SYSTEM,false,byteArrayOf()))
        assertEquals(RegistryResult.WrongOwner,RegistryProtocol.decode("P","P","R",RegistryProtocol.SYSTEM,false,byteArrayOf(1)))
        assertEquals(RegistryResult.Malformed,RegistryProtocol.decode("P","wrong","R",null,false,null))
        assertEquals(RegistryResult.Malformed,RegistryProtocol.decode("P","P","R",RegistryProtocol.SYSTEM,true,byteArrayOf()))
    }
    @Test fun canonicalPdasAgreeWithRust()=runBlocking {
        val rows=JSONArray(resource("pda-vectors.json"))
        for(i in 0 until rows.length()) { val row=rows.getJSONObject(i)
            val d=RegistryDeployment("solana:devnet","test",row.getString("programId"),1)
            assertEquals(row.getString("pda"),RegistryProtocol.pda(d,row.getString("wallet")))
            for(op in listOf("register","update","close")) {
                val ix=RegistryProtocol.instruction(d,row.getString("wallet"),op,ByteArray(32) { 1 },4294967295)
                assertTrue(ix.accounts[0].isSigner); assertEquals(op!="update",ix.accounts[0].isWritable)
                assertTrue(ix.accounts[1].isWritable); assertFalse(ix.accounts[1].isSigner)
                assertEquals(if(op=="register") 3 else 2,ix.accounts.size)
            }
        }
    }
    @Test fun instructionFixtures() {
        val rows=vectors.getJSONArray("instructions")
        for(i in 0 until rows.length()) { val r=rows.getJSONObject(i);val expected=hex(r.getString("dataHex"))
            val op=r.getString("name").removeSuffix("_endpoint")
            val key=if(expected.size==44) expected.copyOfRange(8,40) else byteArrayOf()
            val token=if(expected.size==44) java.nio.ByteBuffer.wrap(expected).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(40).toLong() and 0xffffffffL else 0L
            assertArrayEquals(expected,RegistryProtocol.instructionBytes(op,key,token))
        }
    }
    @Test fun cacheTupleResetOutageAndExpiry()=runBlocking {
        val row=JSONArray(resource("pda-vectors.json")).getJSONObject(0)
        val d=RegistryDeployment("solana:devnet","test",row.getString("programId"),1)
        val w=row.getString("wallet"); var time=1000L;var fail=false;var absent=false;var calls=0
        var bytes=hex(vectors.getJSONArray("valid").getJSONObject(0).getString("hex"))
        val resolver=RegistryResolver(d,{method,_ -> calls++;if(fail) throw java.net.SocketTimeoutException()
            if(method=="getGenesisHash") "test" else JSONObject().put("context",JSONObject().put("slot",10)).put("value",if(absent) JSONObject.NULL else JSONObject().put("owner",d.programId).put("executable",false).put("data",JSONArray().put(java.util.Base64.getEncoder().encodeToString(bytes)).put("base64"))) },{time})
        val first=resolver.resolve(w); assertTrue(first.result is RegistryResult.Registered)
        val n=calls; resolver.resolve(w,false);assertEquals(n,calls)
        fail=true;assertEquals(RegistryResult.RpcFailure,resolver.resolve(w).result);assertEquals(first,resolver.cached(w))
        time+=RegistryResolver.POSITIVE_TTL;assertEquals(RegistryResult.RpcFailure,resolver.resolve(w,false).result)
        fail=false;absent=true;assertEquals(RegistryResult.NotRegistered,resolver.resolve(w).result)
        absent=false;bytes=hex(vectors.getJSONArray("valid").getJSONObject(1).getString("hex"))
        assertTrue(resolver.resolve(w).tupleChanged)
        // Identical revision with changed key still invalidates the mapping.
        bytes[9]=99;assertTrue(resolver.resolve(w).tupleChanged)
    }
}
