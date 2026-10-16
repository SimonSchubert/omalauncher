package dev.omalauncher.linux

import org.bouncycastle.bcpg.HashAlgorithmTags
import org.bouncycastle.bcpg.PublicKeyAlgorithmTags
import org.bouncycastle.openpgp.PGPUtil
import org.bouncycastle.openpgp.PGPPublicKeyRingCollection
import org.bouncycastle.openpgp.PGPSignature
import org.bouncycastle.openpgp.PGPSignatureGenerator
import org.bouncycastle.openpgp.operator.bc.BcKeyFingerprintCalculator
import org.bouncycastle.openpgp.operator.bc.BcPGPContentSignerBuilder
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPKeyPair
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.util.Date

class RootfsSignatureTest {
    private val key = JcaPGPKeyPair(PublicKeyAlgorithmTags.RSA_SIGN, KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair(), Date())
    private val fingerprint get() = key.publicKey.fingerprint.joinToString("") { "%02X".format(it) }
    private val contents = "trusted system image".toByteArray()
    private fun signature(hash: Int = HashAlgorithmTags.SHA256): ByteArray {
        val generator = PGPSignatureGenerator(BcPGPContentSignerBuilder(key.publicKey.algorithm, hash))
        generator.init(PGPSignature.BINARY_DOCUMENT, key.privateKey)
        generator.update(contents)
        return generator.generate().encoded
    }
    private fun verify(bytes: ByteArray, sig: ByteArray = signature(), expected: String = fingerprint) {
        val archive = Files.createTempFile("rootfs", ".gz").toFile()
        try {
            archive.writeBytes(bytes)
            RootfsSignature.verify(archive, sig.inputStream(), key.publicKey.encoded.inputStream(), expected)
        } finally { archive.delete() }
    }
    @Test fun acceptsSignatureFromPinnedKey() = verify(contents)
    @Test fun rejectsChangedArchive() { assertThrows(Exception::class.java) { verify(contents + 0) } }
    @Test fun rejectsWrongTrustedKey() { assertThrows(Exception::class.java) { verify(contents, expected = "0".repeat(40)) } }
    @Test fun rejectsSignatureFromAnotherKey() {
        val other = JcaPGPKeyPair(PublicKeyAlgorithmTags.RSA_SIGN, KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair(), Date())
        val generator = PGPSignatureGenerator(BcPGPContentSignerBuilder(other.publicKey.algorithm, HashAlgorithmTags.SHA256))
        generator.init(PGPSignature.BINARY_DOCUMENT, other.privateKey)
        generator.update(contents)
        assertThrows(Exception::class.java) { verify(contents, generator.generate().encoded) }
    }
    @Test fun rejectsTruncatedSignature() { assertThrows(Exception::class.java) { verify(contents, signature().take(20).toByteArray()) } }
    @Test fun rejectsMissingSignature() { assertThrows(Exception::class.java) { verify(contents, byteArrayOf()) } }
    @Test fun rejectsWeakHashEvenWithValidSignature() { assertThrows(Exception::class.java) { verify(contents, signature(HashAlgorithmTags.SHA1)) } }
    @Test fun bundledKeyMatchesIndependentlyPublishedFingerprint() {
        val file = java.io.File("src/main/assets/security/archlinuxarm.asc")
        file.inputStream().use { input ->
            val rings = PGPPublicKeyRingCollection(PGPUtil.getDecoderStream(input), BcKeyFingerprintCalculator())
            val fingerprints = rings.keyRings.asSequence().map { it.publicKey.fingerprint.joinToString("") { b -> "%02X".format(b) } }.toList()
            assertEquals(listOf(RootfsSignature.FINGERPRINT), fingerprints)
        }
    }
}
