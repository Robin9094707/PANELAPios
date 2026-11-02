package eu.simplexsmp.adminsuite;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.*;
import java.util.*;

/**
 * 9.8.0: minimal, strict WebAuthn relying party for passkeys (ES256, EdDSA, RS256). Attestation statements are
 * ignored on purpose ("none"); trust comes from the registered public key and the later signed assertions.
 */
final class WebAuthn {
    private WebAuthn() { }
    record Credential(byte[] id, String publicKey, int alg, long signCount, boolean backupEligible) { }
    record Assertion(byte[] credentialId, long signCount, byte[] userHandle) { }

    static String b64(byte[] value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(value); }
    static byte[] unb64(String value) {
        if (value == null || value.length() > 16_384) throw new IllegalArgumentException("Passkey-Daten fehlen oder sind zu groß");
        try { return Base64.getUrlDecoder().decode(value.replace('+', '-').replace('/', '_').replace("=", "")); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("Passkey-Daten sind ungültig"); }
    }

    /** Accepts exactly https://rpId or https://rpId:port. */
    static boolean originAllowed(String origin, String rpId) {
        if (origin == null || rpId == null || rpId.isBlank() || !origin.startsWith("https://")) return false;
        String host = origin.substring(8);
        if (host.contains("/") || host.contains("@")) return false;
        int colon = host.lastIndexOf(':');
        if (colon >= 0) { if (!host.substring(colon + 1).matches("[0-9]{1,5}")) return false; host = host.substring(0, colon); }
        return host.equalsIgnoreCase(rpId);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> clientData(byte[] raw, String type, byte[] challenge, String rpId) {
        Object parsed = DiscordBotManager.Json.parse(new String(raw, StandardCharsets.UTF_8));
        if (!(parsed instanceof Map<?, ?> map)) throw new IllegalArgumentException("Passkey-Antwort ungültig");
        Map<String, Object> data = (Map<String, Object>) map;
        if (!type.equals(data.get("type"))) throw new IllegalArgumentException("Falscher Passkey-Vorgang");
        byte[] received = unb64(String.valueOf(data.get("challenge")));
        if (!MessageDigest.isEqual(received, challenge)) throw new IllegalArgumentException("Passkey-Anfrage abgelaufen oder nicht von diesem Server");
        if (!originAllowed(String.valueOf(data.get("origin")), rpId)) throw new IllegalArgumentException("Passkey nur über https://" + rpId + " möglich");
        if (Boolean.TRUE.equals(data.get("crossOrigin"))) throw new IllegalArgumentException("Eingebettete Passkey-Anfragen sind nicht erlaubt");
        return data;
    }

    private static void checkAuthData(byte[] auth, String rpId, boolean requireUv) {
        if (auth.length < 37) throw new IllegalArgumentException("Authenticator-Daten zu kurz");
        if (!MessageDigest.isEqual(Arrays.copyOf(auth, 32), sha256(rpId.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8)))) throw new IllegalArgumentException("Passkey gehört zu einer anderen Domain");
        int flags = auth[32] & 0xff;
        if ((flags & 0x01) == 0) throw new IllegalArgumentException("Passkey ohne Benutzerbestätigung");
        if (requireUv && (flags & 0x04) == 0) throw new IllegalArgumentException("Passkey verlangt Face ID, Touch ID oder Geräte-PIN");
    }

    private static long counter(byte[] auth) { return ((auth[33] & 0xffL) << 24) | ((auth[34] & 0xffL) << 16) | ((auth[35] & 0xffL) << 8) | (auth[36] & 0xffL); }

    /** Verifies navigator.credentials.create() output and returns the credential to store. */
    @SuppressWarnings("unchecked")
    static Credential register(byte[] clientDataJson, byte[] attestationObject, byte[] challenge, String rpId) throws GeneralSecurityException {
        clientData(clientDataJson, "webauthn.create", challenge, rpId);
        Cbor.Item attestation = Cbor.decode(attestationObject, 0);
        if (!(attestation.value() instanceof Map<?, ?> map) || !(map.get("authData") instanceof byte[] auth)) throw new IllegalArgumentException("Attestierung ungültig");
        checkAuthData(auth, rpId, true);
        int flags = auth[32] & 0xff;
        if ((flags & 0x40) == 0 || auth.length < 55) throw new IllegalArgumentException("Passkey ohne Schlüsseldaten");
        int length = ((auth[53] & 0xff) << 8) | (auth[54] & 0xff);
        if (length < 16 || length > 1023 || auth.length < 55 + length + 1) throw new IllegalArgumentException("Passkey-ID ungültig");
        byte[] id = Arrays.copyOfRange(auth, 55, 55 + length);
        Cbor.Item cose = Cbor.decode(auth, 55 + length);
        if (!(cose.value() instanceof Map<?, ?> keyMap)) throw new IllegalArgumentException("Passkey-Schlüssel ungültig");
        Map<Object, Object> key = (Map<Object, Object>) keyMap;
        int alg = ((Number) Objects.requireNonNull(key.get(3L), "alg")).intValue();
        PublicKey publicKey = coseKey(key, alg);
        return new Credential(id, b64(publicKey.getEncoded()), alg, counter(auth), (flags & 0x08) != 0);
    }

    /** Verifies navigator.credentials.get() output against a stored credential. */
    static Assertion verify(byte[] credentialId, byte[] clientDataJson, byte[] authenticatorData, byte[] signature, byte[] userHandle,
                            byte[] challenge, String rpId, String storedKey, int alg, long storedCount) throws GeneralSecurityException {
        clientData(clientDataJson, "webauthn.get", challenge, rpId);
        checkAuthData(authenticatorData, rpId, true);
        PublicKey key = KeyFactory.getInstance(alg == -7 ? "EC" : alg == -8 ? "Ed25519" : "RSA").generatePublic(new X509EncodedKeySpec(Base64.getUrlDecoder().decode(storedKey)));
        Signature verifier = Signature.getInstance(alg == -7 ? "SHA256withECDSA" : alg == -8 ? "Ed25519" : "SHA256withRSA");
        verifier.initVerify(key);
        verifier.update(authenticatorData);
        verifier.update(sha256(clientDataJson));
        if (!verifier.verify(signature)) throw new IllegalArgumentException("Passkey-Signatur ungültig");
        long count = counter(authenticatorData);
        if (count != 0 && storedCount != 0 && count <= storedCount) throw new IllegalArgumentException("Passkey-Zähler unplausibel; möglicherweise kopierter Schlüssel");
        return new Assertion(credentialId, count, userHandle);
    }

    private static PublicKey coseKey(Map<Object, Object> key, int alg) throws GeneralSecurityException {
        long kty = ((Number) key.get(1L)).longValue();
        if (alg == -7 && kty == 2) {
            if (((Number) key.get(-1L)).longValue() != 1) throw new IllegalArgumentException("Nur P-256 wird unterstützt");
            byte[] x = (byte[]) key.get(-2L), y = (byte[]) key.get(-3L);
            if (x == null || y == null || x.length != 32 || y.length != 32) throw new IllegalArgumentException("EC-Schlüssel ungültig");
            AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC"); parameters.init(new ECGenParameterSpec("secp256r1"));
            ECParameterSpec spec = parameters.getParameterSpec(ECParameterSpec.class);
            return KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(new ECPoint(new BigInteger(1, x), new BigInteger(1, y)), spec));
        }
        if (alg == -8 && kty == 1) {
            if (((Number) key.get(-1L)).longValue() != 6) throw new IllegalArgumentException("Nur Ed25519 wird unterstützt");
            byte[] x = (byte[]) key.get(-2L);
            if (x == null || x.length != 32) throw new IllegalArgumentException("Ed25519-Schlüssel ungültig");
            ByteArrayOutputStream der = new ByteArrayOutputStream();
            der.writeBytes(HexFormat.of().parseHex("302a300506032b6570032100")); der.writeBytes(x);
            return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(der.toByteArray()));
        }
        if (alg == -257 && kty == 3) {
            byte[] n = (byte[]) key.get(-1L), e = (byte[]) key.get(-2L);
            if (n == null || e == null || n.length < 256) throw new IllegalArgumentException("RSA-Schlüssel zu schwach");
            return KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(new BigInteger(1, n), new BigInteger(1, e)));
        }
        throw new IllegalArgumentException("Passkey-Algorithmus wird nicht unterstützt");
    }

    static byte[] sha256(byte[] value) { try { return MessageDigest.getInstance("SHA-256").digest(value); } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); } }

    /** Strict subset of CBOR (RFC 8949) used by WebAuthn: definite lengths only, bounded nesting. */
    static final class Cbor {
        record Item(Object value, int end) { }
        private Cbor() { }
        static Item decode(byte[] data, int offset) { return read(data, offset, 0); }
        private static Item read(byte[] d, int i, int depth) {
            if (depth > 8) throw new IllegalArgumentException("CBOR zu tief verschachtelt");
            if (i >= d.length) throw new IllegalArgumentException("CBOR unvollständig");
            int initial = d[i] & 0xff, major = initial >> 5, info = initial & 0x1f; i++;
            long length;
            if (info < 24) length = info;
            else if (info <= 27) {
                int bytes = 1 << (info - 24); if (i + bytes > d.length) throw new IllegalArgumentException("CBOR unvollständig");
                length = 0; for (int k = 0; k < bytes; k++) length = (length << 8) | (d[i + k] & 0xff); i += bytes;
                if (length < 0) throw new IllegalArgumentException("CBOR-Wert zu groß");
            } else throw new IllegalArgumentException("CBOR mit unbestimmter Länge wird nicht unterstützt");
            switch (major) {
                case 0: return new Item(length, i);
                case 1: return new Item(-1 - length, i);
                case 2: case 3: {
                    if (length > d.length - i) throw new IllegalArgumentException("CBOR unvollständig");
                    byte[] value = Arrays.copyOfRange(d, i, i + (int) length);
                    return new Item(major == 2 ? value : new String(value, StandardCharsets.UTF_8), i + (int) length);
                }
                case 4: {
                    if (length > 256) throw new IllegalArgumentException("CBOR-Liste zu groß");
                    List<Object> list = new ArrayList<>();
                    for (long k = 0; k < length; k++) { Item item = read(d, i, depth + 1); list.add(item.value()); i = item.end(); }
                    return new Item(list, i);
                }
                case 5: {
                    if (length > 64) throw new IllegalArgumentException("CBOR-Objekt zu groß");
                    Map<Object, Object> map = new LinkedHashMap<>();
                    for (long k = 0; k < length; k++) { Item key = read(d, i, depth + 1); Item value = read(d, key.end(), depth + 1); map.put(key.value(), value.value()); i = value.end(); }
                    return new Item(map, i);
                }
                case 6: return read(d, i, depth + 1);
                default:
                    if (info == 20) return new Item(false, i);
                    if (info == 21) return new Item(true, i);
                    if (info == 22 || info == 23) return new Item(null, i);
                    if (info >= 25 && info <= 27) return new Item(length, i);
                    return new Item(null, i);
            }
        }
    }
}
