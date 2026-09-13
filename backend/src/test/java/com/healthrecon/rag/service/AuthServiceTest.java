package com.healthrecon.rag.service;

import com.healthrecon.rag.api.dto.LoginRequest;
import com.healthrecon.rag.api.dto.RegisterRequest;
import com.healthrecon.rag.domain.User;
import com.healthrecon.rag.exception.ConflictException;
import com.healthrecon.rag.exception.InvalidCredentialsException;
import com.healthrecon.rag.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private AuthService authService;

    private final RegisterRequest registerRequest =
            new RegisterRequest("  User@Example.com  ", "password123", "  Ada  ");

    @Test
    void registerCreatesUserWithEncodedPassword() {
        when(userRepository.existsByEmail("user@example.com")).thenReturn(false);
        when(passwordEncoder.encode("password123")).thenReturn("encoded");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        var user = authService.register(registerRequest);

        assertThat(user.email()).isEqualTo("user@example.com");
        assertThat(user.displayName()).isEqualTo("Ada");

        var captor = org.mockito.ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getPasswordHash()).isEqualTo("encoded");
    }

    @Test
    void registerRejectsDuplicateEmail() {
        when(userRepository.existsByEmail("user@example.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(registerRequest))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void loginSucceedsWithValidCredentials() {
        User user = new User(UUID.randomUUID(), "user@example.com", "encoded", "Ada", Instant.now());
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("password123", "encoded")).thenReturn(true);

        var result = authService.login(new LoginRequest("user@example.com", "password123"));

        assertThat(result.email()).isEqualTo("user@example.com");
    }

    @Test
    void loginRejectsWrongPassword() {
        User user = new User(UUID.randomUUID(), "user@example.com", "encoded", "Ada", Instant.now());
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", "encoded")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(new LoginRequest("user@example.com", "wrong")))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void loginRejectsUnknownEmail() {
        when(userRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(new LoginRequest("nobody@example.com", "password123")))
                .isInstanceOf(InvalidCredentialsException.class);
    }
}