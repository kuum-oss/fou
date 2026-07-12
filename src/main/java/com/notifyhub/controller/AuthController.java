package com.notifyhub.controller;

import com.notifyhub.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final JwtUtil jwtUtil;

    @PostMapping("/token")
    public ResponseEntity<Map<String, String>> getToken(@RequestParam String userId) {
        String token = jwtUtil.generateToken(userId);
        return ResponseEntity.ok(Map.of("token", token));
    }
}
