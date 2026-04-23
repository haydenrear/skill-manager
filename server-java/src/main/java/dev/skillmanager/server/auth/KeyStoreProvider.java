package dev.skillmanager.server.auth;

import com.nimbusds.jose.jwk.RSAKey;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * Loads — or on first boot, generates — the RSA keypair we sign JWTs with.
 *
 * <p>Backed by a PKCS#12 keystore at {@code ${SKILL_REGISTRY_ROOT}/jwt-keystore.p12}.
 * The store contains a single entry under alias {@link #ALIAS}: a 2048-bit RSA
 * keypair plus a self-signed wrapping cert (PKCS#12 requires a cert chain even
 * when all we care about is the key material).
 *
 * <p>Password comes from {@code skill-registry.keystore.password} /
 * {@code SKILL_REGISTRY_KEYSTORE_PASSWORD}. Change it before exposing a registry.
 */
@Component
public class KeyStoreProvider {

    public static final String ALIAS = "jwt-signing";
    public static final String FILENAME = "jwt-keystore.p12";

    private final RSAKey rsaKey;

    public KeyStoreProvider(
            Path registryRoot,
            @Value("${skill-registry.keystore.password:changeit}") String password) throws Exception {
        Files.createDirectories(registryRoot);
        Path store = registryRoot.resolve(FILENAME);
        KeyStore ks = KeyStore.getInstance("PKCS12");
        if (Files.isRegularFile(store)) {
            try (InputStream in = Files.newInputStream(store)) {
                ks.load(in, password.toCharArray());
            }
        } else {
            ks.load(null, password.toCharArray());
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            KeyPair kp = gen.generateKeyPair();
            X509Certificate cert = selfSigned(kp, "CN=skill-manager-registry");
            ks.setKeyEntry(ALIAS, kp.getPrivate(), password.toCharArray(), new Certificate[]{cert});
            try (OutputStream out = Files.newOutputStream(store)) {
                ks.store(out, password.toCharArray());
            }
            try {
                Files.setPosixFilePermissions(store, java.util.Set.of(
                        java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                        java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
            } catch (UnsupportedOperationException ignored) {}
        }
        PrivateKey privateKey = (PrivateKey) ks.getKey(ALIAS, password.toCharArray());
        Certificate[] chain = ks.getCertificateChain(ALIAS);
        RSAPublicKey pub = (RSAPublicKey) chain[0].getPublicKey();
        this.rsaKey = new RSAKey.Builder(pub)
                .privateKey((RSAPrivateKey) privateKey)
                .keyID(UUID.nameUUIDFromBytes(pub.getEncoded()).toString())
                .build();
    }

    public RSAKey rsaKey() { return rsaKey; }

    private static X509Certificate selfSigned(KeyPair kp, String dn) throws Exception {
        X500Name subject = new X500Name(dn);
        BigInteger serial = new BigInteger(64, new SecureRandom());
        Date from = Date.from(Instant.now().minusSeconds(60));
        Date to = Date.from(Instant.now().plusSeconds(10L * 365 * 24 * 3600));
        X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                subject, serial, from, to, subject, kp.getPublic());
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(kp.getPrivate());
        return new JcaX509CertificateConverter().getCertificate(builder.build(signer));
    }
}
