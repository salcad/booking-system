package com.ottodot.booking;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.ottodot.booking.support.AbstractIntegrationTest;
import com.ottodot.booking.support.Concurrency;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * The brief's required scenario, driven over real HTTP.
 *
 * <p>The service-level tests prove the invariant inside one JVM. This proves it
 * at the boundary an actual client uses, with separate requests, separate
 * connections and separate transactions — which is where a design that only
 * works because everything shared a transaction would fall apart.
 */
class LastSeatRaceHttpTest extends AbstractIntegrationTest {

    @Autowired
    TestRestTemplate http;

    @Test
    @DisplayName("A takes the last seat; B is refused before ever reaching payment")
    void loserIsRejectedAtBookingTimeAndNeverCharged() {
        long classId = fixtures.trialClassWithConfirmed(4, 3);
        long childA = fixtures.student(fixtures.parent());
        long childB = fixtures.student(fixtures.parent());

        // 1. User A selects the last available slot and moves to payment.
        ResponseEntity<JsonNode> a = book(childA, classId);
        assertThat(a.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(a.getBody().get("status").asText()).isEqualTo("PENDING_PAYMENT");

        // 2. User B selects the same slot. The seat is already claimed, so B is
        //    turned away here rather than after being charged.
        ResponseEntity<JsonNode> b = book(childB, classId);
        assertThat(b.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(b.getBody().get("code").asText()).isEqualTo("CLASS_FULL");

        // 3. A completes payment.
        long bookingA = a.getBody().get("id").asLong();
        ResponseEntity<JsonNode> pay = pay(bookingA, "SUCCESS");
        assertThat(pay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(pay.getBody().get("status").asText()).isEqualTo("CONFIRMED");

        // At most one user ended up confirmed for the last seat.
        assertThat(fixtures.countByStatus(classId, "CONFIRMED")).isEqualTo(4);
        assertThat(roster(classId).get("confirmed")).hasSize(4);
        fixtures.assertInvariants(classId);
    }

    @RepeatedTest(10)
    @DisplayName("10 simultaneous HTTP bookings for one seat: exactly one 201")
    void concurrentHttpBookingsYieldOneWinner() {
        int contenders = 10;
        long classId = fixtures.trialClassWithConfirmed(4, 3);
        long parentId = fixtures.parent();
        List<Long> children = java.util.stream.IntStream.range(0, contenders)
                .mapToObj(i -> fixtures.student(parentId)).toList();

        List<HttpStatus> statuses = Concurrency.inParallel(contenders,
                i -> (HttpStatus) book(children.get(i), classId).getStatusCode());

        assertThat(statuses.stream().filter(s -> s == HttpStatus.CREATED).count()).isEqualTo(1);
        assertThat(statuses.stream().filter(s -> s == HttpStatus.CONFLICT).count())
                .isEqualTo(contenders - 1);
        assertThat(fixtures.claimedSeats(classId)).isEqualTo(4);
        fixtures.assertInvariants(classId);
    }

    @Test
    @DisplayName("the full parent journey: browse, book, pay, see status")
    void happyPathEndToEnd() {
        long classId = fixtures.trialClass(4);
        long childId = fixtures.student(fixtures.parent());

        ResponseEntity<JsonNode> classes = http.getForEntity("/api/trial-classes", JsonNode.class);
        assertThat(classes.getStatusCode()).isEqualTo(HttpStatus.OK);

        long bookingId = book(childId, classId).getBody().get("id").asLong();
        assertThat(pay(bookingId, "SUCCESS").getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<JsonNode> status =
                http.getForEntity("/api/bookings/" + bookingId, JsonNode.class);
        assertThat(status.getBody().get("status").asText()).isEqualTo("CONFIRMED");
        assertThat(status.getBody().get("history"))
                .as("the booking carries its own audit trail")
                .hasSizeGreaterThanOrEqualTo(2);
        fixtures.assertInvariants(classId);
    }

    @Test
    @DisplayName("a declined payment answers 402 and leaves the roster untouched")
    void declinedPaymentOverHttp() {
        long classId = fixtures.trialClass(4);
        long childId = fixtures.student(fixtures.parent());
        long bookingId = book(childId, classId).getBody().get("id").asLong();

        ResponseEntity<JsonNode> res = pay(bookingId, "FAILURE");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);
        assertThat(res.getBody().get("status").asText()).isEqualTo("PAYMENT_FAILED");
        assertThat(roster(classId).get("confirmed")).isEmpty();
        fixtures.assertInvariants(classId);
    }

    @Test
    @DisplayName("a duplicate booking answers 409 DUPLICATE_BOOKING")
    void duplicateOverHttp() {
        long classId = fixtures.trialClass(4);
        long childId = fixtures.student(fixtures.parent());
        book(childId, classId);

        ResponseEntity<JsonNode> second = book(childId, classId);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getBody().get("code").asText()).isEqualTo("DUPLICATE_BOOKING");
        fixtures.assertInvariants(classId);
    }

    @Test
    @DisplayName("a malformed request is a 400, not a 500")
    void validationFailureIsBadRequest() {
        ResponseEntity<JsonNode> res = http.postForEntity(
                "/api/bookings", Map.of("studentId", 1), JsonNode.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().get("code").asText()).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    @DisplayName("an unknown booking is a 404")
    void unknownBookingIsNotFound() {
        ResponseEntity<JsonNode> res =
                http.getForEntity("/api/bookings/99999999", JsonNode.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(res.getBody().get("code").asText()).isEqualTo("NOT_FOUND");
    }

    private ResponseEntity<JsonNode> book(long studentId, long classId) {
        return http.postForEntity("/api/bookings",
                Map.of("studentId", studentId, "trialClassId", classId), JsonNode.class);
    }

    private ResponseEntity<JsonNode> pay(long bookingId, String outcome) {
        return http.postForEntity("/api/bookings/" + bookingId + "/payment",
                Map.of("outcome", outcome, "idempotencyKey", UUID.randomUUID().toString()),
                JsonNode.class);
    }

    private JsonNode roster(long classId) {
        return http.getForEntity("/api/trial-classes/" + classId + "/roster", JsonNode.class)
                .getBody();
    }
}
