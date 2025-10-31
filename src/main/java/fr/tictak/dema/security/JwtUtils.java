package fr.tictak.dema.security;

import fr.tictak.dema.exception.UnauthorizedException;
import fr.tictak.dema.model.user.User;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.Collections;
import java.util.Date;
import java.util.List;

@Component
public class JwtUtils {

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Value("${jwt.accessTokenExpirationMs}")
    private long accessTokenExpirationMs;

    @Value("${jwt.refreshTokenExpirationMs}")
    private long refreshTokenExpirationMs;

    private static final String TOKEN_PREFIX = "Bearer ";
    private static final String HEADER_AUTH = "Authorization";

    private Key getSigningKey() {
        byte[] keyBytes = jwtSecret.getBytes(StandardCharsets.UTF_8);
        return Keys.hmacShaKeyFor(keyBytes);
    }


    public String generateAccessToken(User user) {
        Key key = getSigningKey();
        return Jwts.builder()
                .setSubject(user.getEmail())
                .claim("roles", Collections.singletonList("ROLE_" + user.getRole().name()))
                .claim("userId", user.getId())
                .claim("firstName", user.getFirstName())
                .claim("lastName", user.getLastName())
                .claim("phoneNumber", user.getPhoneNumber())
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + accessTokenExpirationMs))
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }

    public String generateRefreshToken(User user) {
        Key key = getSigningKey();
        return Jwts.builder()
                .setSubject(user.getEmail())
                .claim("refresh", true)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + refreshTokenExpirationMs))
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }

    public String extractToken(HttpServletRequest request) {
        String bearerToken = request.getHeader(HEADER_AUTH);
        if (bearerToken != null && bearerToken.startsWith(TOKEN_PREFIX)) {
            return bearerToken.substring(TOKEN_PREFIX.length());
        }
        return null;
    }

    public boolean validateToken(String token, boolean isRefresh) {
        try {
            Key key = getSigningKey();
            Claims claims = Jwts.parserBuilder()
                    .setSigningKey(key)
                    .build()
                    .parseClaimsJws(token)
                    .getBody();

            if (isRefresh) {
                Boolean refreshClaim = claims.get("refresh", Boolean.class);
                if (refreshClaim == null || !refreshClaim) {
                    System.out.println("❌ This is not a Refresh Token");
                    return false;
                }
            } else {
                if (claims.get("refresh") != null) {
                    System.out.println("❌ This is not an Access Token (it's a refresh token)!");
                    return false;
                }
            }
            return true;
        } catch (ExpiredJwtException e) {
            System.out.println("❌ Token expired: " + e.getMessage());
        } catch (MalformedJwtException e) {
            System.out.println("❌ Malformed token: " + e.getMessage());
        } catch (SignatureException | UnsupportedJwtException | IllegalArgumentException e) {
            System.out.println("❌ Invalid token: " + e.getMessage());
        }
        return false;
    }

    public String extractUserEmail(String token) {
        try {
            Key key = getSigningKey();
            String email = Jwts.parserBuilder()
                    .setSigningKey(key)
                    .build()
                    .parseClaimsJws(token)
                    .getBody()
                    .getSubject();
            if (email == null || email.isEmpty()) {
                throw new RuntimeException("User email is missing in token");
            }
            return email;
        } catch (JwtException e) {
            throw new RuntimeException("Invalid or expired JWT token: " + e.getMessage());
        }
    }

    public List getRolesFromToken(String token) {
        try {
            Key key = getSigningKey();
            Claims claims = Jwts.parserBuilder()
                    .setSigningKey(key)
                    .build()
                    .parseClaimsJws(token)
                    .getBody();
            return claims.get("roles", List.class);
        } catch (JwtException e) {
            throw new RuntimeException("Invalid token when extracting roles: " + e.getMessage());
        }
    }

    public String getUserIdFromToken(String token) {
        try {
            Key key = getSigningKey();
            Claims claims = Jwts.parserBuilder()
                    .setSigningKey(key)
                    .build()
                    .parseClaimsJws(token)
                    .getBody();
            return claims.get("userId", String.class);
        } catch (JwtException e) {
            throw new RuntimeException("Invalid token when extracting userId: " + e.getMessage());
        }
    }

    public String generateMissionOfferToken(String moveId, String driverId, Date expiry) {
        Key key = getSigningKey();
        return Jwts.builder()
                .setSubject(driverId)
                .claim("moveId", moveId)
                .setExpiration(expiry)
                .signWith(key, SignatureAlgorithm.HS512)
                .compact();
    }

    public Claims verifyMissionOfferToken(String token) {
        try {
            Key key = getSigningKey();
            return Jwts.parserBuilder()
                    .setSigningKey(key)
                    .build()
                    .parseClaimsJws(token)
                    .getBody();
        } catch (JwtException e) {
            throw new UnauthorizedException("Invalid token: " + e.getMessage());
        }
    }
}