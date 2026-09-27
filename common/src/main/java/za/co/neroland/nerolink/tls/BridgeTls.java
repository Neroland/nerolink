package za.co.neroland.nerolink.tls;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.Locale;


/**
 * The bridge's TLS identity: a long-lived, self-signed ECDSA P-256 certificate that the companion
 * app <b>pins</b> by its SHA-256 fingerprint. No certificate authority is involved, so no domain
 * name or public DNS is needed for LAN/direct mode, and no third-party library is required: the
 * certificate is DER-encoded by hand (a few dozen bytes of ASN.1) and signed with the JDK.
 *
 * <p>The key pair and certificate live in a PKCS#12 file under the game's {@code config/} folder
 * (owner-only permissions where the filesystem supports it) and are reused across restarts and
 * worlds, so paired devices keep a stable pin. Deleting the file rotates the identity; every
 * device then has to re-pair (the app shows "server identity changed").
 *
 * <p><b>Pairing channel binding.</b> A pin is only as good as the first connection. In direct
 * mode the app never sends the pairing code itself: it sends a proof derived from the code and
 * the certificate fingerprint it saw (see {@link #pairingProof(String, String)}). The bridge
 * compares it with the proof for its <i>own</i> fingerprint, so a machine-in-the-middle presenting
 * a different certificate cannot complete pairing. The proof is deliberately slow to compute
 * (PBKDF2, 100k iterations), so capturing it and brute-forcing the 40-bit code offline takes
 * months rather than the code's 5-minute lifetime. The player additionally compares the short
 * security code shown in chat and in the app.
 *
 * <p>Contains no personal data; nothing here is logged except the public fingerprint.
 */
public final class BridgeTls {

    /** Stable store password — the file's secrecy comes from filesystem permissions, as with SSH host keys. */
    private static final char[] STORE_PASSWORD = "nerolink-bridge".toCharArray();
    private static final String ALIAS = "nerolink-bridge";
    private static final String SUBJECT_CN = "NeroLink Bridge";
    private static final int VALIDITY_YEARS = 20;

    /** Domain-separation prefix for the pairing proof (versioned so the scheme can evolve). */
    public static final String PAIR_PROOF_CONTEXT = "nerolink-pair-v1:";

    private final PrivateKey privateKey;
    private final X509Certificate certificate;
    private final String fingerprint;

    private BridgeTls(PrivateKey privateKey, X509Certificate certificate) {
        this.privateKey = privateKey;
        this.certificate = certificate;
        this.fingerprint = sha256Hex(encoded(certificate));
    }

    /**
     * Load the identity from {@code file}, creating (and persisting) a fresh one if the file is
     * missing or unreadable. A corrupt file is moved aside rather than deleted so an admin can
     * inspect it.
     */
    public static BridgeTls loadOrCreate(Path file) throws IOException, GeneralSecurityException {
        if (Files.isRegularFile(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                KeyStore ks = KeyStore.getInstance("PKCS12");
                ks.load(in, STORE_PASSWORD);
                if (ks.getKey(ALIAS, STORE_PASSWORD) instanceof PrivateKey key
                        && ks.getCertificate(ALIAS) instanceof X509Certificate cert) {
                    cert.checkValidity();
                    return new BridgeTls(key, cert);
                }
            } catch (IOException | GeneralSecurityException e) {
                Path aside = file.resolveSibling(file.getFileName() + ".broken");
                Files.move(file, aside, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        BridgeTls fresh = generate();
        fresh.save(file);
        return fresh;
    }

    /** Generate a fresh identity (not persisted). Visible for tests. */
    public static BridgeTls generate() throws GeneralSecurityException {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
        gen.initialize(new ECGenParameterSpec("secp256r1"), new SecureRandom());
        KeyPair pair = gen.generateKeyPair();
        byte[] der = selfSignedCertificate(pair);
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        X509Certificate cert;
        try {
            cert = (X509Certificate) cf.generateCertificate(new java.io.ByteArrayInputStream(der));
        } catch (Exception e) {
            throw new GeneralSecurityException("could not parse generated certificate", e);
        }
        cert.verify(pair.getPublic());
        return new BridgeTls(pair.getPrivate(), cert);
    }

    private void save(Path file) throws IOException, GeneralSecurityException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setKeyEntry(ALIAS, privateKey, STORE_PASSWORD, new Certificate[] {certificate});
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (OutputStream out = Files.newOutputStream(tmp)) {
            ks.store(out, STORE_PASSWORD);
        }
        restrictToOwner(tmp);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static void restrictToOwner(Path file) {
        try {
            Files.setPosixFilePermissions(file,
                    EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows / non-POSIX filesystems: rely on the user profile's ACLs.
        }
    }

    public PrivateKey privateKey() {
        return privateKey;
    }

    public X509Certificate certificate() {
        return certificate;
    }

    /** Lower-case hex SHA-256 of the DER certificate — the value the app pins. */
    public String fingerprint() {
        return fingerprint;
    }

    /**
     * A short human-comparable form of the fingerprint for chat: the first 16 hex digits in
     * groups of four, e.g. {@code 3F9A-0C21-7B44-E1D0}. Used only for eyeball verification; the
     * app always pins the full fingerprint.
     */
    public String shortFingerprint() {
        return shortForm(fingerprint);
    }

    public static String shortForm(String hexFingerprint) {
        String up = hexFingerprint.toUpperCase(Locale.ROOT);
        return up.substring(0, 4) + "-" + up.substring(4, 8) + "-" + up.substring(8, 12) + "-" + up.substring(12, 16);
    }

    // --- pairing channel binding -------------------------------------------------------

    /** Canonical form of a pairing code for the proof: upper-case, dashes/whitespace removed. */
    public static String canonicalCode(String code) {
        StringBuilder sb = new StringBuilder(code.length());
        for (char c : code.toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                sb.append(Character.toUpperCase(c));
            }
        }
        return sb.toString();
    }

    /** PBKDF2 work factor for the pairing proof (shared with the app's pinning.dart). */
    public static final int PAIR_PROOF_ITERATIONS = 100_000;

    /**
     * {@code hex(PBKDF2-HMAC-SHA256(password = canonical code, salt = "nerolink-pair-v1:" +
     * lower-case hex fingerprint, iterations = 100000, length = 32 bytes))} — the value a
     * direct-mode client sends instead of the code. Takes tens of milliseconds by design; the
     * bridge computes it once per issued code, never per attempt.
     */
    public static String pairingProof(String code, String hexFingerprint) {
        try {
            javax.crypto.SecretKeyFactory f = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            byte[] salt = (PAIR_PROOF_CONTEXT + hexFingerprint.toLowerCase(Locale.ROOT)).getBytes(StandardCharsets.UTF_8);
            javax.crypto.spec.PBEKeySpec spec = new javax.crypto.spec.PBEKeySpec(
                    canonicalCode(code).toCharArray(), salt, PAIR_PROOF_ITERATIONS, 256);
            try {
                return HexFormat.of().formatHex(f.generateSecret(spec).getEncoded());
            } finally {
                spec.clearPassword();
            }
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2WithHmacSHA256 unavailable", e);
        }
    }

    // --- certificate construction (minimal DER) ----------------------------------------

    private static byte[] selfSignedCertificate(KeyPair pair) throws GeneralSecurityException {
        // ecdsa-with-SHA256 (1.2.840.10045.4.3.2), parameters absent.
        byte[] sigAlg = seq(oid(new int[] {1, 2, 840, 10045, 4, 3, 2}));
        byte[] name = seq(set(seq(oid(new int[] {2, 5, 4, 3}), utf8(SUBJECT_CN))));
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC).withNano(0);
        byte[] validity = seq(utcTime(now.minusDays(1)), utcTime(now.plusYears(VALIDITY_YEARS)));
        byte[] serialBytes = new byte[16];
        new SecureRandom().nextBytes(serialBytes);
        serialBytes[0] &= 0x7F; // keep it positive
        byte[] serial = integer(new BigInteger(1, serialBytes).toByteArray());
        byte[] version = explicit(0, integer(new byte[] {2})); // v3
        byte[] spki = pair.getPublic().getEncoded(); // already a DER SubjectPublicKeyInfo
        byte[] tbs = seq(version, serial, sigAlg, name, validity, name, spki);

        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(pair.getPrivate());
        signer.update(tbs);
        byte[] signature = signer.sign(); // DER Ecdsa-Sig-Value
        byte[] bitString = tlv(0x03, concat(new byte[] {0}, signature));
        return seq(tbs, sigAlg, bitString);
    }

    private static byte[] utcTime(ZonedDateTime t) {
        String s = t.format(DateTimeFormatter.ofPattern("yyMMddHHmmss'Z'"));
        if (t.getYear() >= 2050) {
            s = t.format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss'Z'"));
            return tlv(0x18, s.getBytes(StandardCharsets.US_ASCII)); // GeneralizedTime
        }
        return tlv(0x17, s.getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] oid(int[] arcs) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(arcs[0] * 40 + arcs[1]);
        for (int i = 2; i < arcs.length; i++) {
            int v = arcs[i];
            byte[] tmp = new byte[5];
            int n = 0;
            do {
                tmp[n++] = (byte) (v & 0x7F);
                v >>>= 7;
            } while (v != 0);
            for (int j = n - 1; j >= 0; j--) {
                out.write(tmp[j] | (j == 0 ? 0 : 0x80));
            }
        }
        return tlv(0x06, out.toByteArray());
    }

    private static byte[] integer(byte[] value) {
        return tlv(0x02, value);
    }

    private static byte[] utf8(String s) {
        return tlv(0x0C, s.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] seq(byte[]... parts) {
        return tlv(0x30, concat(parts));
    }

    private static byte[] set(byte[]... parts) {
        return tlv(0x31, concat(parts));
    }

    private static byte[] explicit(int tag, byte[] inner) {
        return tlv(0xA0 | tag, inner);
    }

    private static byte[] tlv(int tag, byte[] value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(value.length + 6);
        out.write(tag);
        int len = value.length;
        if (len < 0x80) {
            out.write(len);
        } else if (len < 0x100) {
            out.write(0x81);
            out.write(len);
        } else if (len < 0x10000) {
            out.write(0x82);
            out.write(len >> 8);
            out.write(len & 0xFF);
        } else {
            throw new IllegalArgumentException("DER value too long");
        }
        out.writeBytes(value);
        return out.toByteArray();
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            out.writeBytes(p);
        }
        return out.toByteArray();
    }

    private static byte[] encoded(X509Certificate cert) {
        try {
            return cert.getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static String sha256Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
