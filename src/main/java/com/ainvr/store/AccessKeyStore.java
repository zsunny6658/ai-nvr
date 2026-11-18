package com.ainvr.store;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;

/**
 * Web 控制台“访问密码”的本地存储（独立于米家账号登录）。
 *
 * <p>访问密码用于进入本系统的本地门禁，<b>不保存明文、也不可逆</b>：采用
 * {@code PBKDF2WithHmacSHA256} + 每密码独立随机盐 + 高迭代次数派生密钥，落盘
 * 数据目录下的 {@code access.json}（默认运行目录 {@code .ainvr/}，见 {@link DataDir}；
 * base64 的 salt / hash + 迭代次数）。校验时用相同参数
 * 重新派生并做恒定时间比较。</p>
 *
 * <p>该文件与 {@link SessionStore} 的 master.key/session.json 同目录，并尽量收紧为仅
 * owner 可读写。它防的是“随手打开看到密码 / 离线字典直读”，不抵抗拿到整个目录且能离线
 * 高速穷举弱口令的攻击者，因此仍要求密码不少于 {@value #MIN_LEN} 位。</p>
 */
@Component
public class AccessKeyStore {

    private static final Logger log = LoggerFactory.getLogger(AccessKeyStore.class);

    private static final String ACCESS_FILE = "access.json";
    private static final String ALGO = "PBKDF2WithHmacSHA256";
    private static final int SALT_BYTES = 16;
    private static final int KEY_BITS = 256;
    private static final int ITERATIONS = 120_000;
    public static final int MIN_LEN = 10;
    public static final int MAX_LEN = 64;
    /** 解锁/会话密钥派生的域隔离标签，确保其与密码校验哈希（hash）使用不同盐、互不相关。 */
    private static final byte[] UNLOCK_LABEL = "ainvr:unlock-token:v1".getBytes(StandardCharsets.UTF_8);

    private final Path dir;
    private final ObjectMapper mapper = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    private final SecureRandom random = new SecureRandom();

    /** null 表示尚未设置访问密码。 */
    private volatile Record record;
    /** 本次进程内已校验通过的访问密码派生出的会话主密钥（仅内存，不落盘）。 */
    private volatile byte[] cachedSessionKey;

    public AccessKeyStore() {
        this(DataDir.get());
    }

    public AccessKeyStore(Path dir) {
        this.dir = dir;
        this.record = load();
    }

    public boolean isConfigured() {
        return record != null;
    }

    /**
     * 首次设置访问密码。
     *
     * @throws IllegalStateException 已设置过（不允许覆盖，需先删除 access.json）
     * @throws IllegalArgumentException 密码不满足强度要求
     */
    public synchronized void setup(char[] password) {
        if (record != null) {
            throw new IllegalStateException("访问密码已设置，不能重复设置");
        }
        String bad = validate(password);
        if (bad != null) {
            throw new IllegalArgumentException(bad);
        }
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        byte[] hash = pbkdf2(password, salt, ITERATIONS, KEY_BITS);

        Record r = new Record();
        r.algo = ALGO;
        r.iterations = ITERATIONS;
        r.salt = Base64.getEncoder().encodeToString(salt);
        r.hash = Base64.getEncoder().encodeToString(hash);
        r.urlHash = urlTokenHashB64(password, salt, ITERATIONS);
        persist(r);
        record = r;
        cacheSessionKey(password, salt, ITERATIONS);
        log.info("访问密码已设置（PBKDF2 迭代 {}）", ITERATIONS);
    }

    /** 校验访问密码；未设置或参数/算法异常一律返回 false（不泄露具体原因）。 */
    public synchronized boolean verify(char[] password) {
        Record r = record;
        if (r == null || password == null || password.length == 0) {
            return false;
        }
        try {
            byte[] salt = Base64.getDecoder().decode(r.salt);
            byte[] expected = Base64.getDecoder().decode(r.hash);
            byte[] actual = pbkdf2(password, salt, r.iterations, KEY_BITS);
            boolean ok = MessageDigest.isEqual(expected, actual);
            if (ok) {
                cacheSessionKey(password, salt, r.iterations);
            }
            return ok;
        } catch (Exception e) {
            log.warn("访问密码校验异常：{}", e.getMessage());
            return false;
        }
    }

    /* ===================== URL 一键解锁令牌 ===================== */

    /**
     * 由访问密码派生 URL 解锁令牌：{@code base64url(PBKDF2(密码, 本密码独立盐, 迭代次数))}。
     * 与访问密码哈希共用同一 salt / 迭代次数，因此离线爆破访问密码的成本与 PBKDF2 一致
     * （不再是单次 SHA-256）；令牌本身即 32 字节会话主密钥的编码，供 opt-in 的会话加密复用。
     * 确定性、凭密码可重算；令牌不含也不暴露原密码。
     */
    public String buildUnlockToken(char[] password) {
        Record r = record;
        if (r == null) {
            throw new IllegalStateException("尚未设置访问密码");
        }
        return unlockTokenOf(password, tokenSalt(Base64.getDecoder().decode(r.salt)), r.iterations);
    }

    /** 校验 URL 携带的解锁令牌（对其二次哈希做恒定时间比较）；未启用/损坏/为空一律 false。 */
    public synchronized boolean verifyUnlockToken(String token) {
        return verifyUnlockTokenAndGetKey(token).isPresent();
    }

    /**
     * 校验 URL 解锁令牌并<strong>返回其编码的 32 字节会话主密钥</strong>。
     *
     * <p>用于 opt-in 的“会话密钥由访问密码派生”模式：一键解锁链接的 key 本身就是会话密钥的
     * base64url 编码，校验通过即代表持有该密码派生密钥，可直接用于解密 {@code session.json}
     * 恢复米家会话。校验失败（哈希不符/编码损坏/未设置）返回 {@link Optional#empty()}。</p>
     */
    public synchronized Optional<byte[]> verifyUnlockTokenAndGetKey(String token) {
        Record r = record;
        if (r == null || r.urlHash == null || token == null || token.isEmpty()) {
            return Optional.empty();
        }
        try {
            byte[] expected = Base64.getDecoder().decode(r.urlHash);
            byte[] actual = sha256(token.getBytes(StandardCharsets.UTF_8));
            if (!MessageDigest.isEqual(expected, actual)) {
                return Optional.empty();
            }
            byte[] key = Base64.getUrlDecoder().decode(token);
            if (key.length != KEY_BITS / 8) {
                return Optional.empty();
            }
            return Optional.of(key);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * 校验访问密码通过后返回解锁令牌；若为旧版记录（无 urlHash）则补算并落盘。
     * 密码错误抛 {@link IllegalArgumentException}，未设置抛 {@link IllegalStateException}。
     */
    public synchronized String regenerateUnlockToken(char[] password) {
        Record r = record;
        if (r == null) {
            throw new IllegalStateException("尚未设置访问密码");
        }
        if (!verify(password)) {
            throw new IllegalArgumentException("访问密码错误");
        }
        String expectedHash = urlTokenHashB64(password, Base64.getDecoder().decode(r.salt), r.iterations);
        if (!expectedHash.equals(r.urlHash)) {
            r.urlHash = expectedHash;
            persist(r);
        }
        return buildUnlockToken(password);
    }

    /**
     * 在已通过门禁的会话内修改访问密码：先校验当前密码，再用<strong>新随机盐</strong>重新派生落盘。
     * 成功后返回新的一键解锁令牌（密码改变后旧令牌随之失效）；当前会话的解锁态不受影响。
     *
     * @throws IllegalStateException 尚未设置访问密码
     * @throws IllegalArgumentException 当前密码错误、新密码不合规或与旧密码相同
     */
    public synchronized String changePassword(char[] oldPassword, char[] newPassword) {
        Record r = record;
        if (r == null) {
            throw new IllegalStateException("尚未设置访问密码");
        }
        if (oldPassword == null || oldPassword.length == 0 || !verify(oldPassword)) {
            throw new IllegalArgumentException("当前密码错误");
        }
        String bad = validate(newPassword);
        if (bad != null) {
            throw new IllegalArgumentException(bad);
        }
        // 新密码与当前密码相同则拒绝（用旧盐派生后做恒定时间比较）
        byte[] oldSalt = Base64.getDecoder().decode(r.salt);
        byte[] oldHash = Base64.getDecoder().decode(r.hash);
        if (MessageDigest.isEqual(oldHash, pbkdf2(newPassword, oldSalt, r.iterations, KEY_BITS))) {
            throw new IllegalArgumentException("新密码不能与当前密码相同");
        }
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        byte[] hash = pbkdf2(newPassword, salt, ITERATIONS, KEY_BITS);

        Record nr = new Record();
        nr.algo = ALGO;
        nr.iterations = ITERATIONS;
        nr.salt = Base64.getEncoder().encodeToString(salt);
        nr.hash = Base64.getEncoder().encodeToString(hash);
        nr.urlHash = urlTokenHashB64(newPassword, salt, ITERATIONS);
        persist(nr);
        record = nr;
        cacheSessionKey(newPassword, salt, ITERATIONS);
        log.info("访问密码已修改（PBKDF2 迭代 {}）", ITERATIONS);
        return buildUnlockToken(newPassword);
    }

    /** 返回本次进程内已校验密码派生出的会话主密钥（32 字节）；未解锁/未设置返回 null。 */
    public byte[] cachedSessionKey() {
        byte[] k = cachedSessionKey;
        return k == null ? null : k.clone();
    }

    /** 由访问密码派生 32 字节会话主密钥（AES-GCM 用），供 opt-in 的会话加密复用。 */
    public byte[] sessionKey(char[] password) {
        Record r = record;
        if (r == null) {
            throw new IllegalStateException("尚未设置访问密码");
        }
        return deriveSessionKey(password, tokenSalt(Base64.getDecoder().decode(r.salt)), r.iterations);
    }

    /** 落盘的是“令牌的 SHA-256”，避免 access.json 中直接存放可立即使用的解锁令牌。 */
    private static String urlTokenHashB64(char[] password, byte[] baseSalt, int iterations) {
        String token = unlockTokenOf(password, tokenSalt(baseSalt), iterations);
        return Base64.getEncoder().encodeToString(sha256(token.getBytes(StandardCharsets.UTF_8)));
    }

    private static String unlockTokenOf(char[] password, byte[] salt, int iterations) {
        byte[] key = pbkdf2(password, salt, iterations, KEY_BITS);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(key);
    }

    private static byte[] deriveSessionKey(char[] password, byte[] salt, int iterations) {
        return pbkdf2(password, salt, iterations, KEY_BITS);
    }

    /**
     * 由基准盐派生“解锁/会话密钥”专用盐：{@code SHA-256(基准盐 ‖ 域标签)} 取前 16 字节。
     * 使会话密钥 / 解锁令牌与密码校验哈希 {@code hash} 使用不同盐、互不相关，
     * 避免 access.json 中的 hash 被直接当作会话密钥或可逆推出令牌。
     */
    private static byte[] tokenSalt(byte[] baseSalt) {
        byte[] mixed = new byte[baseSalt.length + UNLOCK_LABEL.length];
        System.arraycopy(baseSalt, 0, mixed, 0, baseSalt.length);
        System.arraycopy(UNLOCK_LABEL, 0, mixed, baseSalt.length, UNLOCK_LABEL.length);
        byte[] h = sha256(mixed);
        byte[] ts = new byte[SALT_BYTES];
        System.arraycopy(h, 0, ts, 0, SALT_BYTES);
        return ts;
    }

    private void cacheSessionKey(char[] password, byte[] baseSalt, int iterations) {
        this.cachedSessionKey = deriveSessionKey(password, tokenSalt(baseSalt), iterations);
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 强度校验，返回 null 表示通过，否则返回可读原因。 */
    public static String validate(char[] pw) {
        if (pw == null || pw.length < MIN_LEN) {
            return "访问密码至少 " + MIN_LEN + " 位";
        }
        if (pw.length > MAX_LEN) {
            return "访问密码不能超过 " + MAX_LEN + " 位";
        }
        for (char c : pw) {
            if (!Character.isWhitespace(c)) {
                return null;
            }
        }
        return "访问密码不能全为空白字符";
    }

    private static byte[] pbkdf2(char[] password, byte[] salt, int iterations, int keyBits) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, keyBits);
            try {
                SecretKeyFactory factory = SecretKeyFactory.getInstance(ALGO);
                return factory.generateSecret(spec).getEncoded();
            } finally {
                spec.clearPassword();
            }
        } catch (Exception e) {
            throw new IllegalStateException("派生密钥失败：" + e.getMessage(), e);
        }
    }

    private synchronized Record load() {
        Path file = dir.resolve(ACCESS_FILE);
        if (!Files.exists(file)) {
            return null;
        }
        try {
            Record r = mapper.readValue(Files.readString(file, StandardCharsets.UTF_8), Record.class);
            if (r == null || r.salt == null || r.hash == null || r.iterations <= 0) {
                log.warn("访问密码文件内容不完整，视为未设置：{}", file);
                return null;
            }
            return r;
        } catch (Exception e) {
            log.warn("读取访问密码失败，视为未设置（{}）：{}", file, e.getMessage());
            return null;
        }
    }

    private synchronized void persist(Record r) {
        try {
            Files.createDirectories(dir);
            Path file = dir.resolve(ACCESS_FILE);
            Files.writeString(file, mapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(r), StandardCharsets.UTF_8);
            restrictOwnerOnly(file);
        } catch (Exception e) {
            throw new IllegalStateException("保存访问密码失败：" + e.getMessage(), e);
        }
    }

    /** v2.1.0 配置导出：返回当前密码凭据（PBKDF2 哈希，不可逆）；未设置时返回 null。 */
    public synchronized Record exportRecord() {
        return record;
    }

    /** v2.1.0 配置导入：恢复导出的密码凭据（覆盖现有）。校验结构与算法标识后落盘。 */
    public synchronized void importRecord(Record r) {
        if (r == null || r.algo == null || !ALGO.equals(r.algo)
                || r.salt == null || r.hash == null) {
            throw new IllegalArgumentException("访问密码凭据格式无效");
        }
        persist(r);
        record = r;
        cachedSessionKey = null; // 旧密码派生的会话密钥全部作废
        log.info("访问密码已从配置备份恢复");
    }

    /** 尽量收紧文件权限（Windows 上仅尽力而为）。 */
    private void restrictOwnerOnly(Path path) {
        File file = path.toFile();
        file.setReadable(false, false);
        file.setWritable(false, false);
        file.setReadable(true, true);
        file.setWritable(true, true);
    }

    /** access.json 映射结构。 */
    public static class Record {
        public String algo;
        public int iterations;
        public String salt;
        public String hash;
        /** URL 解锁令牌的二次 SHA-256（标准 base64）；旧版本文件可能为 null。 */
        public String urlHash;
    }
}
