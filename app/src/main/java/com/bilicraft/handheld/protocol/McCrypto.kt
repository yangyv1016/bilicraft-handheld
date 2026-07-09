package com.bilicraft.handheld.protocol

import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * MC 在线模式加密握手所需的密码学工具。
 *
 * 流程：
 * 1. 服务器发来 RSA 公钥 + verifyToken
 * 2. 客户端生成 16 字节 AES 共享密钥
 * 3. 用服务器公钥 RSA 加密 [sharedSecret, verifyToken] → Encryption Response
 * 4. 用 sharedSecret 计算 serverId 哈希，向 Mojang sessionserver 报到（joinServer）
 * 5. 之后所有流量用 AES/CFB8（sharedSecret 同时作为 key 和 iv）加密
 */
object McCrypto {

    /** 生成 128-bit AES 共享密钥 */
    fun generateSharedSecret(): SecretKey =
        KeyGenerator.getInstance("AES").apply { init(128) }.generateKey()

    /** 解析服务器发来的 X.509 DER 编码 RSA 公钥 */
    fun decodePublicKey(der: ByteArray): PublicKey =
        KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(der))

    /** 用 RSA 公钥加密（PKCS#1 v1.5，MC 约定） */
    fun rsaEncrypt(key: PublicKey, data: ByteArray): ByteArray =
        Cipher.getInstance("RSA/ECB/PKCS1Padding").run {
            init(Cipher.ENCRYPT_MODE, key)
            doFinal(data)
        }

    /**
     * 计算 joinServer 用的 serverId 哈希。
     * MC 用的是「非标准」的十六进制：对 sha1 结果按有符号大整数取十六进制。
     */
    fun serverHash(serverId: String, sharedSecret: SecretKey, publicKey: PublicKey): String {
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update(serverId.toByteArray(Charsets.US_ASCII))
        digest.update(sharedSecret.encoded)
        digest.update(publicKey.encoded)
        return java.math.BigInteger(digest.digest()).toString(16)
    }

    /** 创建 AES/CFB8 加/解密 Cipher（key 与 iv 都为 sharedSecret） */
    fun newCipher(mode: Int, secret: SecretKey): Cipher =
        Cipher.getInstance("AES/CFB8/NoPadding").apply {
            init(mode, SecretKeySpec(secret.encoded, "AES"), IvParameterSpec(secret.encoded))
        }

    val random = SecureRandom()
}