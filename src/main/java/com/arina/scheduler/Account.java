package com.arina.scheduler;

import jakarta.persistence.*;

@Entity @Table(name="accounts")
class Account {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) Long id;
    @Column(nullable=false, unique=true) String email;
    @Column(name="password_hash", nullable=false) String passwordHash;
    @Column(nullable=false) String role;
    protected Account() {}
    public Long getId() { return id; }
    Account(String email, String passwordHash, String role) { this.email=email; this.passwordHash=passwordHash; this.role=role; }
}
