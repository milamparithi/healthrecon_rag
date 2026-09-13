package com.healthrecon.rag.service;

import com.healthrecon.rag.api.dto.AuthUser;
import com.healthrecon.rag.api.dto.LoginRequest;
import com.healthrecon.rag.api.dto.RegisterRequest;
import com.healthrecon.rag.domain.User;
import com.healthrecon.rag.exception.ConflictException;
import com.healthrecon.rag.exception.InvalidCredentialsException;
import com.healthrecon.rag.exception.NotFoundException;
import com.healthrecon.rag.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public AuthUser register(RegisterRequest request) {
        String email = request.email().trim().toLowerCase();
        if (userRepository.existsByEmail(email)) {
            throw new ConflictException("An account with this email already exists");
        }
        User user = new User(
                UUID.randomUUID(),
                email,
                passwordEncoder.encode(request.password()),
                request.displayName().trim(),
                Instant.now());
        return AuthUser.from(userRepository.save(user));
    }

    @Transactional(readOnly = true)
    public AuthUser login(LoginRequest request) {
        String email = request.email().trim().toLowerCase();
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new InvalidCredentialsException("Invalid email or password"));
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new InvalidCredentialsException("Invalid email or password");
        }
        return AuthUser.from(user);
    }

    @Transactional(readOnly = true)
    public AuthUser getByUserId(UUID userId) {
        return userRepository.findById(userId)
                .map(AuthUser::from)
                .orElseThrow(() -> new NotFoundException("User not found"));
    }

    @Transactional(readOnly = true)
    public User getEntity(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found"));
    }
}