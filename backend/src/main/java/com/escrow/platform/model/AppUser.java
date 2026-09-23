package com.escrow.platform.model;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "app_user", uniqueConstraints = @UniqueConstraint(columnNames = "email"))
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String passwordHash;

    @Column(nullable = false)
    private String displayName;

    /** Lowercased 0x wallet address this user signs escrow transactions from. */
    @Column(length = 42)
    private String walletAddress;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    protected AppUser() {
        // JPA
    }

    public AppUser(String email, String passwordHash, String displayName, String walletAddress) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.displayName = displayName;
        this.walletAddress = walletAddress == null ? null : walletAddress.toLowerCase();
    }

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getWalletAddress() {
        return walletAddress;
    }

    public void setWalletAddress(String walletAddress) {
        this.walletAddress = walletAddress == null ? null : walletAddress.toLowerCase();
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
