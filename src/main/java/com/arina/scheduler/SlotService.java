package com.arina.scheduler;

import java.time.Instant;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import static com.arina.scheduler.SlotController.*;

@Service
class SlotService {
    private final SlotRepository slots;
    private final AccountRepository accounts;

    SlotService(SlotRepository slots, AccountRepository accounts) {
        this.slots = slots;
        this.accounts = accounts;
    }

    @Transactional
    SlotView create(Jwt jwt, SlotRequest request) {
        Account recruiter = actor(jwt);
        requireRole(recruiter, "RECRUITER");
        // Serialize slot creation by this recruiter, including overlapping requests.
        recruiter = lockAccount(recruiter);
        if (!request.startsAt().isAfter(Instant.now()) || !request.endsAt().isAfter(request.startsAt())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid time interval");
        }
        if (slots.overlaps(recruiter.id, request.startsAt(), request.endsAt())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Slot overlaps an existing interval");
        }
        Slot slot = slots.saveAndFlush(new Slot(recruiter, request.startsAt(), request.endsAt()));
        return SlotView.from(slot, recruiter.id);
    }

    @Transactional(readOnly = true)
    SlotPage list(Jwt jwt, int page, int size) {
        if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid page or size (1-100)");
        }
        Account actor = actor(jwt);
        var result = slots.findAll(PageRequest.of(page, size, Sort.by("startsAt").and(Sort.by("id"))));
        var content = result.map(slot -> SlotView.from(slot, actor.id)).getContent();
        return new SlotPage(content, page, size, result.getTotalElements(), result.getTotalPages());
    }

    @Transactional
    SlotView book(Jwt jwt, Long id) {
        Account candidate = actor(jwt);
        requireRole(candidate, "CANDIDATE");
        // Candidate first, then slot: all booking paths take locks in the same order.
        candidate = lockAccount(candidate);
        Slot slot = lockSlot(id);
        requireAvailable(slot);
        requireNoCandidateOverlap(candidate, slot, slot.id);
        slot.candidate = candidate;
        return SlotView.from(slot, candidate.id);
    }

    @Transactional
    void cancel(Jwt jwt, Long id) {
        Account actor = actor(jwt);
        Slot slot = lockSlot(id);
        if (slot.candidate == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Slot is not booked");
        }
        if (!actor.id.equals(slot.candidate.getId()) && !actor.id.equals(slot.recruiter.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        requireFuture(slot);
        slot.candidate = null;
    }

    @Transactional
    SlotView reschedule(Jwt jwt, Long sourceId, Long targetId) {
        Account candidate = actor(jwt);
        requireRole(candidate, "CANDIDATE");
        candidate = lockAccount(candidate);
        if (sourceId.equals(targetId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a different slot");
        }
        // Lock slots in ascending ID order to prevent opposite-direction deadlocks.
        Slot first = lockSlot(Math.min(sourceId, targetId));
        Slot second = lockSlot(Math.max(sourceId, targetId));
        Slot source = sourceId.equals(first.id) ? first : second;
        Slot target = targetId.equals(first.id) ? first : second;
        if (source.candidate == null || !source.candidate.getId().equals(candidate.id)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        requireFuture(source);
        if (!source.recruiter.getId().equals(target.recruiter.getId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Target must belong to the same recruiter");
        }
        requireAvailable(target);
        requireNoCandidateOverlap(candidate, target, source.id);
        source.candidate = null;
        target.candidate = candidate;
        return SlotView.from(target, candidate.id);
    }

    private Account actor(Jwt jwt) {
        return accounts.findById(Long.valueOf(jwt.getSubject()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    }

    private Account lockAccount(Account account) {
        return accounts.lockById(account.id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    }

    private Slot lockSlot(Long id) {
        return slots.lockById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Slot not found"));
    }

    private void requireRole(Account account, String role) {
        if (!account.role.equals(role)) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }

    private void requireFuture(Slot slot) {
        if (!slot.startsAt.isAfter(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Slot has already started");
        }
    }

    private void requireAvailable(Slot slot) {
        requireFuture(slot);
        if (slot.candidate != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Slot already booked");
        }
    }

    private void requireNoCandidateOverlap(Account candidate, Slot target, Long excludedSlotId) {
        if (slots.candidateOverlaps(candidate.id, target.startsAt, target.endsAt, excludedSlotId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Candidate has an overlapping interview");
        }
    }
}
