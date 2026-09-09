package com.ottodot.booking;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.ottodot.booking.support.AbstractIntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * A client cannot retry sensibly if every mistake looks like a server fault.
 * These pin the statuses Spring's own signalling exceptions must keep.
 */
class ErrorMappingTest extends AbstractIntegrationTest {

    @Autowired
    TestRestTemplate http;

    @Test
    @DisplayName("an unknown route is 404, not 500")
    void unknownRouteIsNotFound() {
        ResponseEntity<JsonNode> res =
                http.postForEntity("/api/demo/no-such-route", Map.of(), JsonNode.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(res.getBody().path("code").asText()).isEqualTo("HTTP_404");
    }

    @Test
    @DisplayName("the wrong method on a real route is 405, not 500")
    void wrongMethodIsMethodNotAllowed() {
        ResponseEntity<JsonNode> res =
                http.postForEntity("/api/trial-classes", Map.of(), JsonNode.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(res.getBody().path("code").asText()).isEqualTo("HTTP_405");
    }

    @Test
    @DisplayName("a missing booking still reports the domain code, not HTTP_404")
    void missingResourceKeepsItsDomainCode() {
        ResponseEntity<JsonNode> res =
                http.getForEntity("/api/bookings/999999", JsonNode.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(res.getBody().path("code").asText()).isEqualTo("NOT_FOUND");
    }

    @Test
    @DisplayName("an unparseable id is 400, not 500")
    void malformedPathVariableIsBadRequest() {
        // Type conversion fails before any handler runs, so this never reaches
        // the controller and is easy to mistake for a server fault.
        ResponseEntity<JsonNode> res =
                http.getForEntity("/api/bookings/not-a-number", JsonNode.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().path("code").asText()).isEqualTo("VALIDATION_ERROR");
    }
}
