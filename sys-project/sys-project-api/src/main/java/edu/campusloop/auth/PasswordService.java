package edu.campusloop.auth;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
/** Reference BCrypt implementation retained; legacy cleartext/MD5 compatibility deliberately removed. */
@Service
public class PasswordService {
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    public String encode(String password) {
        if (password == null || password.length() < 12 || password.getBytes(StandardCharsets.UTF_8).length > 72)
            throw new IllegalArgumentException("密码至少 12 个字符，且 UTF-8 编码不超过 72 字节");
        return encoder.encode(password);
    }
    public boolean matches(String raw, String encoded) {
        return raw != null && encoded != null && encoded.startsWith("$2")
            && raw.getBytes(StandardCharsets.UTF_8).length <= 72 && encoder.matches(raw, encoded);
    }
}
