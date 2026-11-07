package com.arina.scheduler;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/slots")
class SlotController {
    private final SlotService service;

    SlotController(SlotService service) {
        this.service = service;
    }

    record SlotRequest(@NotNull Instant startsAt, @NotNull Instant endsAt) {}
    record MoveRequest(@NotNull Long targetSlotId) {}
    record SlotView(Long id, Long recruiterId, Instant startsAt, Instant endsAt,
                    boolean booked, Long candidateId) {
        static SlotView from(Slot slot, Long viewerId) {
            Long candidateId = slot.candidate == null ? null : slot.candidate.getId();
            boolean canSeeCandidate = slot.recruiter.getId().equals(viewerId)
                    || (candidateId != null && candidateId.equals(viewerId));
            return new SlotView(slot.id, slot.recruiter.getId(), slot.startsAt, slot.endsAt,
                    candidateId != null, canSeeCandidate ? candidateId : null);
        }
    }
    record SlotPage(List<SlotView> content, int page, int size, long totalElements, int totalPages) {}

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    SlotView create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody SlotRequest request) {
        return service.create(jwt, request);
    }

    @GetMapping
    SlotPage list(@AuthenticationPrincipal Jwt jwt,
                  @RequestParam(defaultValue = "0") int page,
                  @RequestParam(defaultValue = "20") int size) {
        return service.list(jwt, page, size);
    }

    @PostMapping("/{id}/book")
    SlotView book(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return service.book(jwt, id);
    }

    @DeleteMapping("/{id}/booking")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        service.cancel(jwt, id);
    }

    @PostMapping("/{id}/reschedule")
    SlotView reschedule(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                        @Valid @RequestBody MoveRequest request) {
        return service.reschedule(jwt, id, request.targetSlotId());
    }
}
