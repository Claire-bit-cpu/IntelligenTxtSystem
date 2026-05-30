package com.example.intelligentxtsystem.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 飞书签名验证工具
 * 用于验证飞书开放平台发送的请求签名
 */
@Component
public class FeishuSignatureVerifier {

    @Value("${feishu.encrypt-key:}")
    private String encryptKey;

    private static final String HMAC_SHA256 = "HmacSHA256";
    private static final String FEISHU_TIMESTAMP_HEADER = "X-Lark-Request-Timestamp";
    private static final String FEISHU_SIGNATURE_HEADER = "X-Lark-Request-Signature";

    /**
     * 验证飞书请求签名
     *
     * @param timestamp 请求时间戳
     * @param signature 请求签名
     * @param body      请求体（原始字符串）
     * @return 验证是否通过
     */
    public boolean verify(String timestamp, String signature, String body) {
        // 如果没有配置加密密钥，跳过验证（开发环境）
        if (encryptKey == null || encryptKey.isEmpty()) {
            return true;
        }

        // 如果时间戳或签名为空，验证失败
        if (timestamp == null || signature == null || body == null) {
            return false;
        }

        try {
            // 构造签名内容：timestamp + "\n" + body
            String signContent = timestamp + "\n" + body;

            // 使用 HMAC-SHA256 计算签名
            Mac mac = Mac.getInstance(HMAC_SHA256);
            SecretKeySpec secretKeySpec = new SecretKeySpec(
                    encryptKey.getBytes(StandardCharsets.UTF_8),
                    HMAC_SHA256
            );
            mac.init(secretKeySpec);

            byte[] hash = mac.doFinal(signContent.getBytes(StandardCharsets.UTF_8));

            // 将签名进行 Base64 编码
            String calculatedSignature = Base64.getEncoder().encodeToString(hash);

            // 比较签名
            return calculatedSignature.equals(signature);

        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 生成签名（用于测试）
     */
    public String sign(String timestamp, String body) {
        if (encryptKey == null || encryptKey.isEmpty()) {
            return "";
        }

        try {
            String signContent = timestamp + "\n" + body;

            Mac mac = Mac.getInstance(HMAC_SHA256);
            SecretKeySpec secretKeySpec = new SecretKeySpec(
                    encryptKey.getBytes(StandardCharsets.UTF_8),
                    HMAC_SHA256
            );
            mac.init(secretKeySpec);

            byte[] hash = mac.doFinal(signContent.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);

        } catch (Exception e) {
            return "";
        }
    }
}
