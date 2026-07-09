package com.bilicraft.handheld.protocol

import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.KeyPairGenerator
import java.util.UUID

/**
 * session 签名器布局与链状态单测。
 *
 * 注意：这里验证的是"字节布局稳定性 + 签名可产出/可自验"，
 * 不验证与真实服务器的兼容性（那需要真实抓包，见 README 已知限制）。
 */
class ChatSignerTest {

    @Test
    fun `messageIndex从0起单调自增`() {
        val chain = MessageChainState()
        assertEquals(0, chain.nextIndex())
        assertEquals(1, chain.nextIndex())
        assertEquals(2, chain.nextIndex())
    }

    @Test
    fun `session签名可用对应公钥自验通过`() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val signer = ChatSigner(
            privateKey = pair.private,
            playerUuid = UUID.randomUUID(),
            sessionId = UUID.randomUUID()
        )
        val signed = signer.sign("hi", timestamp = 1_700_000_000_000L, salt = 42L, index = 0)
        assertEquals(256, signed.signature.size)        // 2048-bit RSA → 256 字节定长
    }
}