package eu.simplexsmp.adminsuite;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** 9.8.0: RFC 4648 Base32, RFC 6238 TOTP, recovery codes and generated passwords. No external libraries. */
final class AccountCrypto {
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final SecureRandom RANDOM = new SecureRandom();
    static final int STEP_SECONDS = 30;
    private AccountCrypto() { }

    static String base32(byte[] data) {
        StringBuilder out = new StringBuilder();
        int buffer = 0, bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff); bits += 8;
            while (bits >= 5) { out.append(ALPHABET.charAt((buffer >> (bits - 5)) & 31)); bits -= 5; }
        }
        if (bits > 0) out.append(ALPHABET.charAt((buffer << (5 - bits)) & 31));
        return out.toString();
    }

    static byte[] unbase32(String text) {
        String clean = text == null ? "" : text.replace(" ", "").replace("=", "").toUpperCase(Locale.ROOT);
        ByteBuffer out = ByteBuffer.allocate(clean.length() * 5 / 8 + 1);
        int buffer = 0, bits = 0;
        for (char c : clean.toCharArray()) {
            int value = ALPHABET.indexOf(c);
            if (value < 0) throw new IllegalArgumentException("Ungültiger Base32-Schlüssel");
            buffer = (buffer << 5) | value; bits += 5;
            if (bits >= 8) { out.put((byte) ((buffer >> (bits - 8)) & 0xff)); bits -= 8; }
        }
        return Arrays.copyOf(out.array(), out.position());
    }

    /** 160-bit secret, the size recommended by RFC 4226. */
    static String newTotpSecret() { byte[] b = new byte[20]; RANDOM.nextBytes(b); return base32(b); }

    static int totp(byte[] secret, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1"); mac.init(new SecretKeySpec(secret, "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int offset = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[offset] & 0x7f) << 24) | ((hash[offset + 1] & 0xff) << 16) | ((hash[offset + 2] & 0xff) << 8) | (hash[offset + 3] & 0xff);
            return binary % 1_000_000;
        } catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
    }

    static String code(int value) { return String.format(Locale.ROOT, "%06d", value); }

    /**
     * Returns the matched time step within ±1 step, or -1. Callers reject steps that are not newer than the
     * last accepted one, so a captured code cannot be replayed.
     */
    static long verifyTotp(String secret, String input, long nowMillis) {
        String digits = input == null ? "" : input.replaceAll("[\\s-]", "");
        if (!digits.matches("[0-9]{6}")) return -1;
        byte[] key = unbase32(secret); long step = Math.floorDiv(nowMillis / 1000, STEP_SECONDS); long found = -1;
        for (long candidate = step - 1; candidate <= step + 1; candidate++)
            if (MessageDigest.isEqual(code(totp(key, candidate)).getBytes(StandardCharsets.US_ASCII), digits.getBytes(StandardCharsets.US_ASCII))) found = candidate;
        return found;
    }

    static String otpauth(String issuer, String account, String secret) {
        String label = enc(issuer) + ":" + enc(account);
        return "otpauth://totp/" + label + "?secret=" + secret + "&issuer=" + enc(issuer) + "&algorithm=SHA1&digits=6&period=" + STEP_SECONDS;
    }

    private static String enc(String value) { return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"); }

    /** Ten readable one-time codes such as 7KQ4-MZ2P-X9TB. Only their hashes are stored. */
    static List<String> recoveryCodes() {
        String readable = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"; List<String> out = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            StringBuilder s = new StringBuilder();
            for (int j = 0; j < 12; j++) { if (j > 0 && j % 4 == 0) s.append('-'); s.append(readable.charAt(RANDOM.nextInt(readable.length()))); }
            out.add(s.toString());
        }
        return out;
    }

    static String normalizeRecovery(String code) { return code == null ? "" : code.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT); }

    /** Emergency password: 4 groups of 5 readable characters (~100 bit). */
    static String password() {
        String readable = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"; StringBuilder s = new StringBuilder();
        for (int i = 0; i < 20; i++) { if (i > 0 && i % 5 == 0) s.append('-'); s.append(readable.charAt(RANDOM.nextInt(readable.length()))); }
        return s.toString();
    }

    static String token(int bytes) { byte[] b = new byte[bytes]; RANDOM.nextBytes(b); return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
}
