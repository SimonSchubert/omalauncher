package dev.omalauncher.linux

import org.bouncycastle.bcpg.HashAlgorithmTags
import org.bouncycastle.openpgp.PGPObjectFactory
import org.bouncycastle.openpgp.PGPPublicKeyRingCollection
import org.bouncycastle.openpgp.PGPSignature
import org.bouncycastle.openpgp.PGPSignatureList
import org.bouncycastle.openpgp.PGPUtil
import org.bouncycastle.openpgp.operator.bc.BcKeyFingerprintCalculator
import org.bouncycastle.openpgp.operator.bc.BcPGPContentVerifierBuilderProvider
import java.io.File
import java.io.InputStream
/** Authenticate before parsing the archive. Never run gpg from the unverified guest. */
internal object RootfsSignature {
    // Independently published at https://archlinuxarm.org/about/package-signing.
    const val FINGERPRINT = "68B3537F39A313B3E574D06777193F152BDBE6A6"

    fun verify(archive: File, signature: InputStream, publicKey: InputStream, fingerprint: String = FINGERPRINT) {
        val calculator = BcKeyFingerprintCalculator()
        val rings = PGPPublicKeyRingCollection(PGPUtil.getDecoderStream(publicKey), calculator)
        val keys = rings.keyRings.asSequence().flatMap { it.publicKeys.asSequence() }
        val key = keys.singleOrNull { k -> k.fingerprint.joinToString("") { "%02X".format(it) } == fingerprint }
            ?: error("The trusted Linux signing key is missing")
        val objects = PGPObjectFactory(PGPUtil.getDecoderStream(signature), calculator)
        val signatures = objects.nextObject() as? PGPSignatureList ?: error("Invalid Linux signature")
        check(signatures.size() == 1 && objects.nextObject() == null) { "Invalid Linux signature" }
        val signed = signatures[0]
        check(signed.keyID == key.keyID && signed.signatureType == PGPSignature.BINARY_DOCUMENT) { "Linux was signed by an untrusted key" }
        check(signed.hashAlgorithm in setOf(HashAlgorithmTags.SHA256, HashAlgorithmTags.SHA384, HashAlgorithmTags.SHA512)) { "Linux signature uses an unsafe hash" }
        signed.init(BcPGPContentVerifierBuilderProvider(), key)
        archive.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                signed.update(buffer, 0, n)
            }
        }
        check(signed.verify()) { "Linux download failed signature verification; nothing was installed" }
    }
}
