package com.healthrecon.rag.api;

import com.healthrecon.rag.api.dto.AuthResponse;
import com.healthrecon.rag.api.dto.AuthUser;
import com.healthrecon.rag.api.dto.LoginRequest;
import com.healthrecon.rag.api.dto.RegisterRequest;
import com.healthrecon.rag.security.CurrentUserSupport;
import com.healthrecon.rag.security.JwtService;
import com.healthrecon.rag.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final JwtService jwtService;

    public AuthController(AuthService authService, JwtService jwtService) {
        this.authService = authService;
        this.jwtService = jwtService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.OK)
    public AuthResponse register(@Valid @RequestBody RegisterRequest request) {
        AuthUser user = authService.register(request);
        return new AuthResponse(jwtService.createToken(user.id()), user);
    }

    @PostMapping("/login")
    @ResponseStatus(HttpStatus.OK)
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {
        AuthUser user = authService.login(request);
        return new AuthResponse(jwtService.createToken(user.id()), user);
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout() {
        // JWT is stateless; the client simply discards the token.
    }

    @GetMapping("/me")
    public AuthUser me() {
        return authService.getByUserId(CurrentUserSupport.require().id());
    }
}