package com.fusionsolar.monitoring.provider.solis;

import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

@Service
public class SolisSignatureService {
    public String contentMd5(String body) {
        try {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            byte[] digest = md5.digest(body.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException("Impossible de calculer Content-MD5", e);
        }
    }

    public String authorization(
            String keyId1,
            String keySecret,
            String contentMd5,
            String contentType,
            String date,
            String path
    ) {
        String stringToSign = "POST\n"
                + contentMd5 + "\n"
                + contentType + "\n"
                + date + "\n"
                + path;

        String signature = hmacSha1Base64(stringToSign, keySecret);
        return "API " + keyId1 + ":" + signature;
    }

    private String hmacSha1Base64(String data, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            return Base64.getEncoder().encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Impossible de calculer la signature Solis", e);
        }
    }
}