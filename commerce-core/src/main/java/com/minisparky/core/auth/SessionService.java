package com.minisparky.core.auth;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Service;

import com.minisparky.core.config.AppProperties;

@Service
public class SessionService {

    public record Session(String sessionId, String userToken) {}

    private final byte[] secret;

    public SessionService(AppProperties props) {
        this.secret = props.userTokenSecret().getBytes(UTF_8);
    }

    public Session issue() {
        String id = UUID.randomUUID().toString();
        return new Session(id, id + "." + sign(id));
    }

    /** Returns the session id if the token is genuine, otherwise empty. */
    public Optional<String> verify(String token) {
        if (token == null) return Optional.empty();
        int dot = token.lastIndexOf('.');
        if (dot <= 0) return Optional.empty();
        String id = token.substring(0, dot);
        String sig = token.substring(dot + 1);
        boolean ok = MessageDigest.isEqual(sign(id).getBytes(UTF_8), sig.getBytes(UTF_8));
        return ok ? Optional.of(id) : Optional.empty();
    }

    private String sign(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(data.getBytes(UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}