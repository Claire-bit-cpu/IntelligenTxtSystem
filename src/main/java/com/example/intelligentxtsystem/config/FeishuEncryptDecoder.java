package com.example.intelligentxtsystem.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * 飞书加密请求解密工具
 * 
 * 加密原理：
 * 1. 使用 SHA256 对 Encrypt Key 进行哈希得到密钥 key
 * 2. 使用 PKCS7Padding 方式将事件内容进行填充
 * 3. 生成 16 字节的随机数作为初始向量 iv
 * 4. 使用 iv 和 key 对事件内容加密得到 encrypted_event
 * 5. 应用收到的密文为 base64(iv + encrypted_event)
 * 
 * 使用方式：
 * - 生产环境：配置 feishu.encrypt-key 后自动启用
 * - 测试环境：不配置 feishu.encrypt-key，Bean 不会创建
 */
@Component
@ConditionalOnProperty(name = "feishu.encrypt-key", matchIfMissing = false)
public class FeishuEncryptDecoder {

    private static final Logger log = LoggerFactory.getLogger(FeishuEncryptDecoder.class);
    private static final String AES_CIPHER = "AES/CBC/PKCS5Padding";

    @Value("${feishu.encrypt-key}")
    private String encryptKey;

    /**
     * 解密飞书加密消息
     * 
     * @param encryptData Base64 编码的加密数据
     * @return 解密后的 JSON 字符串
     */
    public String decrypt(String encryptData) {
        if (encryptKey == null || encryptKey.isEmpty()) {
            throw new IllegalStateException("feishu.encrypt-key 未配置");
        }

        try {
            // 1. SHA256(encryptKey) 作为 AES Key
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] aesKey = digest.digest(encryptKey.getBytes(StandardCharsets.UTF_8));

            // 2. Base64 解码
            byte[] encryptedBytes = Base64.getDecoder().decode(encryptData);
            
            if (encryptedBytes.length < 16) {
                throw new RuntimeException("加密数据长度不足");
            }
            
            // 3. 提取 IV（前 16 字节）和加密数据
            byte[] iv = new byte[16];
            System.arraycopy(encryptedBytes, 0, iv, 0, 16);
            
            byte[] cipherText = new byte[encryptedBytes.length - 16];
            System.arraycopy(encryptedBytes, 16, cipherText, 0, encryptedBytes.length - 16);

            // 4. AES-256-CBC 解密（Java 自动去除 PKCS7Padding）
            Cipher cipher = Cipher.getInstance(AES_CIPHER);
            cipher.init(Cipher.DECRYPT_MODE, 
                       new SecretKeySpec(aesKey, "AES"), 
                       new IvParameterSpec(iv));

            byte[] decryptedBytes = cipher.doFinal(cipherText);

            // 5. 返回 JSON 字符串
            return new String(decryptedBytes, StandardCharsets.UTF_8).trim();

        } catch (Exception e) {
            throw new RuntimeException("解密失败", e);
        }
    }

    /**
     * 判断请求体是否包含加密字段
     */
    public boolean isEncrypted(String body) {
        return body != null && body.contains("\"encrypt\"");
    }
    
    /**
     * 静态工具方法：不依赖 Spring Bean，可直接在测试中使用
     */
    public static String decryptStatic(String encryptKey, String encryptData) {
        if (encryptKey == null || encryptKey.isEmpty()) {
            throw new IllegalArgumentException("encryptKey 不能为空");
        }

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] aesKey = digest.digest(encryptKey.getBytes(StandardCharsets.UTF_8));

            byte[] encryptedBytes = Base64.getDecoder().decode(encryptData);
            
            if (encryptedBytes.length < 16) {
                throw new RuntimeException("加密数据长度不足");
            }
            
            byte[] iv = new byte[16];
            System.arraycopy(encryptedBytes, 0, iv, 0, 16);
            
            byte[] cipherText = new byte[encryptedBytes.length - 16];
            System.arraycopy(encryptedBytes, 16, cipherText, 0, encryptedBytes.length - 16);

            Cipher cipher = Cipher.getInstance(AES_CIPHER);
            cipher.init(Cipher.DECRYPT_MODE, 
                       new SecretKeySpec(aesKey, "AES"), 
                       new IvParameterSpec(iv));

            byte[] decryptedBytes = cipher.doFinal(cipherText);

            return new String(decryptedBytes, StandardCharsets.UTF_8).trim();

        } catch (Exception e) {
            throw new RuntimeException("解密失败", e);
        }
    }
}
