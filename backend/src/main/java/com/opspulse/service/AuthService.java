package com.opspulse.service;

import com.opspulse.common.error.InvalidCredentialsException;
import com.opspulse.domain.entity.UserAccount;
import com.opspulse.repository.UserAccountRepository;
import com.opspulse.security.JwtService;
import com.opspulse.security.RefreshTokenStore;
import com.opspulse.web.dto.AuthDtos.TokenResponse;
import com.opspulse.web.dto.UserDtos.UserResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserAccountRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RefreshTokenStore refreshTokens;

    /**
     * Compared against when the email doesn't exist, so a login for an unknown
     * email takes the same ~250ms as a wrong password. Otherwise response time
     * alone reveals which emails have accounts. Generated with the real
     * encoder so it has the same cost factor (a malformed hash returns instantly).
     */
    private volatile String dummyHash;

    @Transactional(readOnly = true)
    public TokenResponse login(String email, String password) {
        UserAccount user = users.findByEmailIgnoreCase(email.trim()).orElse(null);
        if (user == null) {
            passwordEncoder.matches(password, dummyHash());
            throw new InvalidCredentialsException();
        }
        if (!passwordEncoder.matches(password, user.getPasswordHash()) || !user.isActive()) {
            throw new InvalidCredentialsException();
        }
        return issueTokens(user);
    }

    /**
     * Rotation: the presented refresh token is consumed and a brand-new pair
     * is issued. The user is re-read from the DB here -- this is the moment a
     * deactivation or role change takes effect (access tokens can't be
     * recalled, but they expire in 15 minutes).
     */
    @Transactional(readOnly = true)
    public TokenResponse refresh(String refreshToken) {
        var claims = jwtService.parseRefreshToken(refreshToken)
                .orElseThrow(InvalidCredentialsException::new);
        if (!refreshTokens.consume(claims.jti(), claims.userId())) {
            throw new InvalidCredentialsException(); // already used, revoked, or expired
        }
        UserAccount user = users.findById(claims.userId())
                .filter(UserAccount::isActive)
                .orElseThrow(InvalidCredentialsException::new);
        return issueTokens(user);
    }

    /** Idempotent: logging out with a bad or already-used token still returns 204. */
    public void logout(String refreshToken) {
        jwtService.parseRefreshToken(refreshToken).ifPresent(c -> refreshTokens.revoke(c.jti()));
    }

    private String dummyHash() {
        if (dummyHash == null) dummyHash = passwordEncoder.encode("not-a-real-password");
        return dummyHash;
    }

    private TokenResponse issueTokens(UserAccount user) {
        var refresh = jwtService.issueRefreshToken(user.getId());
        refreshTokens.register(refresh.jti(), user.getId(), refresh.ttl());
        return TokenResponse.bearer(jwtService.issueAccessToken(user), refresh.token(),
                jwtService.accessTtlSeconds(), UserResponse.from(user));
    }
}
