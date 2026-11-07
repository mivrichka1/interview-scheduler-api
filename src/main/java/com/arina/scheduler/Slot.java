package com.arina.scheduler;

import jakarta.persistence.*;
import java.time.Instant;

@Entity @Table(name="slots")
class Slot {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) Long id;
    @ManyToOne(fetch=FetchType.LAZY, optional=false) @JoinColumn(name="recruiter_id") Account recruiter;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="candidate_id") Account candidate;
    @Column(name="starts_at", nullable=false) Instant startsAt;
    @Column(name="ends_at", nullable=false) Instant endsAt;
    protected Slot() {}
    Slot(Account recruiter, Instant start, Instant end) { this.recruiter=recruiter; startsAt=start; endsAt=end; }
}
