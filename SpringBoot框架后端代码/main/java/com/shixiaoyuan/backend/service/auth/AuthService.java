package com.shixiaoyuan.backend.service.auth;

import com.shixiaoyuan.backend.entity.UserAccountEntity;
import com.shixiaoyuan.backend.repository.UserAccountRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class AuthService {

    private final UserAccountRepository userAccountRepository;
    private final PasswordEncoder passwordEncoder;

    public AuthService(UserAccountRepository userAccountRepository, PasswordEncoder passwordEncoder) {
        this.userAccountRepository = userAccountRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public Optional<UserAccountEntity> findByUsername(String username) {
        return userAccountRepository.findByUsername(username);
    }

    public boolean verifyPassword(UserAccountEntity user, String rawPassword) {
        return passwordEncoder.matches(rawPassword, user.getPasswordHash());
    }

    public UserAccountEntity register(String username, String rawPassword) {
        if (userAccountRepository.existsByUsername(username)) {
            throw new IllegalStateException("username_exists");
        }
        UserAccountEntity user = new UserAccountEntity();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        return userAccountRepository.save(user);
    }
}

