package com.arina.scheduler;

import java.util.Optional;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

interface AccountRepository extends JpaRepository<Account, Long> {
    Optional<Account> findByEmail(String email);
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select a from Account a where a.id = :id")
    Optional<Account> lockById(@Param("id") Long id);
}
interface SlotRepository extends JpaRepository<Slot, Long> {
    @Query("select count(s) > 0 from Slot s where s.recruiter.id = :recruiterId and s.startsAt < :end and s.endsAt > :start")
    boolean overlaps(@Param("recruiterId") Long recruiterId, @Param("start") java.time.Instant start, @Param("end") java.time.Instant end);
    @Query("select count(s) > 0 from Slot s where s.candidate.id = :candidateId and s.id <> :excludeId and s.startsAt < :end and s.endsAt > :start")
    boolean candidateOverlaps(@Param("candidateId") Long candidateId, @Param("start") java.time.Instant start,
                              @Param("end") java.time.Instant end, @Param("excludeId") Long excludeId);
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select s from Slot s where s.id = :id")
    Optional<Slot> lockById(@Param("id") Long id);
}
