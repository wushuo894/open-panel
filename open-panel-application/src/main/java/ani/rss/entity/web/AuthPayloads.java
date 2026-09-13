package ani.rss.entity.web;

import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

public final class AuthPayloads {
    private AuthPayloads() {
    }

    @Data
    @Accessors(chain = true)
    public static class Credentials implements Serializable {
        private String username;
        private String password;
    }

    @Data
    @Accessors(chain = true)
    public static class PasswordChange implements Serializable {
        private String currentPassword;
        private String newPassword;
    }

    @Data
    @Accessors(chain = true)
    public static class UsernameChange implements Serializable {
        private String currentPassword;
        private String newUsername;
    }
}
