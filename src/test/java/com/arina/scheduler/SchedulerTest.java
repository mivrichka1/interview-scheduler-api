package com.arina.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc
class SchedulerTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    String register(String role) throws Exception {
        String email = UUID.randomUUID() + "@example.com";
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("email",email,"password","password123","role",role))))
            .andExpect(status().isCreated());
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("email",email,"password","password123"))))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("token").asText();
    }
    String createSlot(String recruiter, Instant starts, Instant ends) throws Exception {
        String body = mvc.perform(post("/api/slots").header("Authorization","Bearer " + recruiter)
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("startsAt",starts.toString(),"endsAt",ends.toString()))))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("id").asText();
    }
    @Test void bookingIsExclusiveAndOnlyItsOwnerCanCancel() throws Exception {
        String recruiter=register("RECRUITER"), candidate=register("CANDIDATE"), other=register("CANDIDATE");
        Instant start=Instant.now().plusSeconds(7200);
        String id=createSlot(recruiter,start,start.plusSeconds(1800));
        mvc.perform(post("/api/slots/"+id+"/book").header("Authorization","Bearer "+candidate)).andExpect(status().isOk());
        mvc.perform(post("/api/slots/"+id+"/book").header("Authorization","Bearer "+other)).andExpect(status().isConflict());
        mvc.perform(delete("/api/slots/"+id+"/booking").header("Authorization","Bearer "+other)).andExpect(status().isForbidden());
        mvc.perform(delete("/api/slots/"+id+"/booking").header("Authorization","Bearer "+candidate)).andExpect(status().isNoContent());
        mvc.perform(post("/api/slots/"+id+"/book").header("Authorization","Bearer "+other)).andExpect(status().isOk());
    }
    @Test void rejectsPastSlotAndCandidateCannotCreateSlot() throws Exception {
        String recruiter=register("RECRUITER"), candidate=register("CANDIDATE");
        String past=json.writeValueAsString(Map.of("startsAt",Instant.now().minusSeconds(3600).toString(),"endsAt",Instant.now().minusSeconds(1800).toString()));
        mvc.perform(post("/api/slots").header("Authorization","Bearer "+recruiter).contentType(MediaType.APPLICATION_JSON).content(past)).andExpect(status().isBadRequest());
        mvc.perform(post("/api/slots").header("Authorization","Bearer "+candidate).contentType(MediaType.APPLICATION_JSON).content(past)).andExpect(status().isForbidden());
        mvc.perform(get("/api/slots")).andExpect(status().isUnauthorized());
    }
    @Test void rescheduleMovesBookingAtomically() throws Exception {
        String recruiter=register("RECRUITER"), candidate=register("CANDIDATE");
        Instant start=Instant.now().plusSeconds(10800);
        String source=createSlot(recruiter,start,start.plusSeconds(1800));
        String target=createSlot(recruiter,start.plusSeconds(3600),start.plusSeconds(5400));
        mvc.perform(post("/api/slots/"+source+"/book").header("Authorization","Bearer "+candidate)).andExpect(status().isOk());
        mvc.perform(post("/api/slots/"+source+"/reschedule").header("Authorization","Bearer "+candidate)
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("targetSlotId",Long.valueOf(target)))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(Long.parseLong(target)));
        mvc.perform(post("/api/slots/"+source+"/book").header("Authorization","Bearer "+candidate)).andExpect(status().isOk());
        mvc.perform(post("/api/slots/"+target+"/book").header("Authorization","Bearer "+candidate)).andExpect(status().isConflict());
    }
    @Test void simultaneousRequestsHaveExactlyOneWinner() throws Exception {
        String recruiter=register("RECRUITER"), a=register("CANDIDATE"), b=register("CANDIDATE");
        Instant start=Instant.now().plusSeconds(14400);
        String id=createSlot(recruiter,start,start.plusSeconds(1800));
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        var gate=new java.util.concurrent.CountDownLatch(1);
        try {
            var first=pool.submit(() -> { gate.await(); return mvc.perform(post("/api/slots/"+id+"/book").header("Authorization","Bearer "+a)).andReturn().getResponse().getStatus(); });
            var second=pool.submit(() -> { gate.await(); return mvc.perform(post("/api/slots/"+id+"/book").header("Authorization","Bearer "+b)).andReturn().getResponse().getStatus(); });
            gate.countDown();
            org.assertj.core.api.Assertions.assertThat(java.util.List.of(first.get(),second.get())).containsExactlyInAnyOrder(200,409);
        } finally { pool.shutdownNow(); }
    }
    @Test void recruiterCannotCreateOverlappingSlots() throws Exception {
        String recruiter=register("RECRUITER"); Instant start=Instant.now().plusSeconds(18000);
        createSlot(recruiter,start,start.plusSeconds(3600));
        String overlap=json.writeValueAsString(Map.of("startsAt",start.plusSeconds(1800).toString(),"endsAt",start.plusSeconds(5400).toString()));
        mvc.perform(post("/api/slots").header("Authorization","Bearer "+recruiter).contentType(MediaType.APPLICATION_JSON).content(overlap)).andExpect(status().isConflict());
        createSlot(recruiter,start.plusSeconds(3600),start.plusSeconds(5400));
    }
    @Test void failedRescheduleKeepsOriginalBooking() throws Exception {
        String recruiter=register("RECRUITER"), first=register("CANDIDATE"), second=register("CANDIDATE");
        Instant start=Instant.now().plusSeconds(21000);
        String source=createSlot(recruiter,start,start.plusSeconds(1800));
        String target=createSlot(recruiter,start.plusSeconds(3600),start.plusSeconds(5400));
        mvc.perform(post("/api/slots/"+source+"/book").header("Authorization","Bearer "+first)).andExpect(status().isOk());
        mvc.perform(post("/api/slots/"+target+"/book").header("Authorization","Bearer "+second)).andExpect(status().isOk());
        mvc.perform(post("/api/slots/"+source+"/reschedule").header("Authorization","Bearer "+first).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("targetSlotId",Long.valueOf(target))))).andExpect(status().isConflict());
        mvc.perform(post("/api/slots/"+source+"/book").header("Authorization","Bearer "+second)).andExpect(status().isConflict());
        mvc.perform(delete("/api/slots/"+source+"/booking").header("Authorization","Bearer "+first)).andExpect(status().isNoContent());
    }
    @Test void simultaneousOverlappingSlotCreationHasOneWinner() throws Exception {
        String recruiter=register("RECRUITER"); Instant start=Instant.now().plusSeconds(24000);
        String content=json.writeValueAsString(Map.of("startsAt",start.toString(),"endsAt",start.plusSeconds(1800).toString()));
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2); var gate=new java.util.concurrent.CountDownLatch(1);
        try {
            var a=pool.submit(() -> { gate.await(); return mvc.perform(post("/api/slots").header("Authorization","Bearer "+recruiter).contentType(MediaType.APPLICATION_JSON).content(content)).andReturn().getResponse().getStatus(); });
            var b=pool.submit(() -> { gate.await(); return mvc.perform(post("/api/slots").header("Authorization","Bearer "+recruiter).contentType(MediaType.APPLICATION_JSON).content(content)).andReturn().getResponse().getStatus(); });
            gate.countDown(); org.assertj.core.api.Assertions.assertThat(java.util.List.of(a.get(),b.get())).containsExactlyInAnyOrder(201,409);
        } finally { pool.shutdownNow(); }
    }
    @Test void candidateCannotBookOverlappingInterviews() throws Exception {
        String recruiter=register("RECRUITER"), another=register("RECRUITER"), candidate=register("CANDIDATE");
        Instant start=Instant.now().plusSeconds(27000);
        String first=createSlot(recruiter,start,start.plusSeconds(3600));
        String second=createSlot(another,start.plusSeconds(1800),start.plusSeconds(5400));
        mvc.perform(post("/api/slots/"+first+"/book").header("Authorization","Bearer "+candidate)).andExpect(status().isOk());
        mvc.perform(post("/api/slots/"+second+"/book").header("Authorization","Bearer "+candidate)).andExpect(status().isConflict());
    }
    @Test void concurrentCandidateBookingsForOverlappingSlotsHaveOneWinner() throws Exception {
        String recruiter=register("RECRUITER"), another=register("RECRUITER"), candidate=register("CANDIDATE");
        Instant start=Instant.now().plusSeconds(30000);
        String first=createSlot(recruiter,start,start.plusSeconds(3600));
        String second=createSlot(another,start.plusSeconds(1800),start.plusSeconds(5400));
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2); var gate=new java.util.concurrent.CountDownLatch(1);
        try {
            var a=pool.submit(() -> { gate.await(); return mvc.perform(post("/api/slots/"+first+"/book").header("Authorization","Bearer "+candidate)).andReturn().getResponse().getStatus(); });
            var b=pool.submit(() -> { gate.await(); return mvc.perform(post("/api/slots/"+second+"/book").header("Authorization","Bearer "+candidate)).andReturn().getResponse().getStatus(); });
            gate.countDown(); org.assertj.core.api.Assertions.assertThat(java.util.List.of(a.get(),b.get())).containsExactlyInAnyOrder(200,409);
        } finally { pool.shutdownNow(); }
    }
    @Test void listsSlotsInBoundedPagesSortedByStartTime() throws Exception {
        String recruiter=register("RECRUITER"); Instant start=Instant.now().plusSeconds(36000);
        createSlot(recruiter,start.plusSeconds(3600),start.plusSeconds(5400));
        createSlot(recruiter,start,start.plusSeconds(1800));
        mvc.perform(get("/api/slots?page=0&size=1").header("Authorization","Bearer "+recruiter))
            .andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(1));
        String body=mvc.perform(get("/api/slots?size=100").header("Authorization","Bearer "+recruiter))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var starts=java.util.stream.StreamSupport.stream(json.readTree(body).get("content").spliterator(),false)
            .map(node -> Instant.parse(node.get("startsAt").asText())).toList();
        org.assertj.core.api.Assertions.assertThat(starts).isSorted();
        mvc.perform(get("/api/slots?size=101").header("Authorization","Bearer "+recruiter)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/slots?page=-1").header("Authorization","Bearer "+recruiter)).andExpect(status().isBadRequest());
    }
    @Test void registrationRejectsPasswordsLongerThanBcryptByteLimit() throws Exception {
        String body=json.writeValueAsString(Map.of("email",UUID.randomUUID()+"@example.com","password","я".repeat(40),"role","CANDIDATE"));
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
    }
    @Test void loginAndRegistrationRejectInvalidCredentialsAndDuplicateEmails() throws Exception {
        String email=UUID.randomUUID()+"@example.com";
        String registration=json.writeValueAsString(Map.of("email",email,"password","password123","role","CANDIDATE"));
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(registration)).andExpect(status().isCreated());
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(registration)).andExpect(status().isConflict());
        String invalid=json.writeValueAsString(Map.of("email",email,"password","incorrect"));
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(invalid)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/slots").header("Authorization","Bearer invalid-token")).andExpect(status().isUnauthorized());
    }
    @Test void rescheduleRejectsCandidateConflictAndPreservesOriginal() throws Exception {
        String recruiter=register("RECRUITER"), otherRecruiter=register("RECRUITER"), candidate=register("CANDIDATE");
        Instant start=Instant.now().plusSeconds(40000);
        String source=createSlot(recruiter,start,start.plusSeconds(1800));
        String target=createSlot(recruiter,start.plusSeconds(3600),start.plusSeconds(5400));
        String other=createSlot(otherRecruiter,start.plusSeconds(3600),start.plusSeconds(5400));
        mvc.perform(post("/api/slots/"+source+"/book").header("Authorization","Bearer "+candidate)).andExpect(status().isOk());
        mvc.perform(post("/api/slots/"+other+"/book").header("Authorization","Bearer "+candidate)).andExpect(status().isOk());
        mvc.perform(post("/api/slots/"+source+"/reschedule").header("Authorization","Bearer "+candidate).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("targetSlotId",Long.valueOf(target))))).andExpect(status().isConflict());
        mvc.perform(delete("/api/slots/"+source+"/booking").header("Authorization","Bearer "+candidate)).andExpect(status().isNoContent());
    }
    @Test void candidateIdentityIsHiddenFromOtherUsers() throws Exception {
        String recruiter=register("RECRUITER"), candidate=register("CANDIDATE"), stranger=register("CANDIDATE");
        Instant start=Instant.now().plusSeconds(43000);
        String id=createSlot(recruiter,start,start.plusSeconds(1800));
        mvc.perform(post("/api/slots/"+id+"/book").header("Authorization","Bearer "+candidate)).andExpect(status().isOk());
        String body=mvc.perform(get("/api/slots?size=100").header("Authorization","Bearer "+stranger))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var content=json.readTree(body).get("content");
        var item=java.util.stream.StreamSupport.stream(content.spliterator(),false)
            .filter(node -> node.get("id").asText().equals(id)).findFirst().orElseThrow();
        org.assertj.core.api.Assertions.assertThat(item.get("booked").asBoolean()).isTrue();
        org.assertj.core.api.Assertions.assertThat(item.get("candidateId").isNull()).isTrue();
    }
}
