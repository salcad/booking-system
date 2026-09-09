package com.ottodot.booking;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ottodot.booking.auth.LoginThrottle;
import com.ottodot.booking.support.AbstractIntegrationTest;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * The gate itself.
 *
 * <p>The rest of the suite shares a TestRestTemplate that authenticates every
 * request, which is convenient but means no other test would notice if the
 * filter stopped rejecting anything. These send what an outsider sends.
 *
 * <p>Deliberately the JDK's own HTTP client rather than a RestTemplate:
 * HttpURLConnection, which RestTemplate falls back to here, has its own
 * handling for 401 responses and drops the body of one answering a POST. That
 * would hide exactly the field these tests are about.
 */
class AuthTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "644k1n9";

    @Autowired
    TestRestTemplate authenticated;

    @Autowired
    LoginThrottle throttle;

    @LocalServerPort
    int port;

    private final HttpClient anonymous = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    /**
     * Every test here logs in from the same address, and the throttle counts
     * failures per address across the shared context - so without this the
     * lockout one test provokes would leak into whichever runs next.
     */
    @BeforeEach
    void clearLoginAttempts() {
        throttle.clear();
    }

    private HttpResponse<String> send(HttpRequest.Builder request)
            throws IOException, InterruptedException {
        return anonymous.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder get(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
    }

    private HttpRequest.Builder post(String path, String body) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
    }

    private JsonNode body(HttpResponse<String> res) throws IOException {
        return json.readTree(res.body());
    }

    @Test
    @DisplayName("reads are refused without a session")
    void unauthenticatedReadIsRejected() throws Exception {
        HttpResponse<String> res = send(get("/api/trial-classes"));

        assertThat(res.statusCode()).isEqualTo(401);
        assertThat(body(res).path("code").asText()).isEqualTo("UNAUTHENTICATED");
    }

    @Test
    @DisplayName("writes are refused without a session, so the gate is not read-only")
    void unauthenticatedWriteIsRejected() throws Exception {
        long classId = fixtures.trialClassWithConfirmed(4, 0);
        long studentId = fixtures.student(fixtures.parent());

        HttpResponse<String> res = send(post("/api/bookings",
                """
                {"studentId":%d,"trialClassId":%d}""".formatted(studentId, classId)));

        assertThat(res.statusCode()).isEqualTo(401);
        // The booking must not have been created on the way to being rejected.
        assertThat(fixtures.countByStatus(classId, "PENDING_PAYMENT")).isZero();
    }

    @Test
    @DisplayName("a forged token is refused: the signature is what counts, not the shape")
    void forgedTokenIsRejected() throws Exception {
        // Well-formed and expiring far in the future, but not signed by us.
        String forged = "v1." + (System.currentTimeMillis() / 1000 + 86_400) + ".not-a-signature";

        HttpResponse<String> res =
                send(get("/api/trial-classes").header("Authorization", "Bearer " + forged));

        assertThat(res.statusCode()).isEqualTo(401);
        assertThat(body(res).path("code").asText()).isEqualTo("UNAUTHENTICATED");
    }

    @Test
    @DisplayName("the right password issues a token that opens the API")
    void loginIssuesAWorkingToken() throws Exception {
        HttpResponse<String> login =
                send(post("/api/auth/login", """
                        {"password":"%s"}""".formatted(PASSWORD)));

        assertThat(login.statusCode()).isEqualTo(200);
        String token = body(login).path("token").asText();
        assertThat(token).isNotBlank();

        HttpResponse<String> res =
                send(get("/api/trial-classes").header("Authorization", "Bearer " + token));

        assertThat(res.statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("the token also works as the cookie a browser would send")
    void tokenIsAcceptedAsACookie() throws Exception {
        HttpResponse<String> login =
                send(post("/api/auth/login", """
                        {"password":"%s"}""".formatted(PASSWORD)));
        String token = body(login).path("token").asText();

        HttpResponse<String> res =
                send(get("/api/trial-classes").header("Cookie", "booking_session=" + token));

        assertThat(res.statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("the wrong password is 401 and issues nothing")
    void wrongPasswordIsRejected() throws Exception {
        HttpResponse<String> res =
                send(post("/api/auth/login", """
                        {"password":"not-the-password"}"""));

        assertThat(res.statusCode()).isEqualTo(401);
        assertThat(body(res).path("code").asText()).isEqualTo("INVALID_CREDENTIALS");
        assertThat(body(res).has("token")).isFalse();
    }

    @Test
    @DisplayName("guessing is capped, so the one shared password is not brute-forceable")
    void repeatedFailuresAreThrottled() throws Exception {
        // The allowance is per client address and this test shares one with the
        // rest of the suite, so it asserts that the cap arrives - not where.
        HttpStatus last = null;
        for (int i = 0; i < 40; i++) {
            HttpResponse<String> res = send(post("/api/auth/login", """
                    {"password":"wrong-%d"}""".formatted(i)));
            last = HttpStatus.valueOf(res.statusCode());
            if (last == HttpStatus.TOO_MANY_REQUESTS) {
                assertThat(body(res).path("code").asText()).isEqualTo("TOO_MANY_ATTEMPTS");
                break;
            }
        }
        assertThat(last).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        // And the cap is not a way in: the right password is still refused
        // while the client is locked out.
        HttpResponse<String> correct = send(post("/api/auth/login", """
                {"password":"%s"}""".formatted(PASSWORD)));
        assertThat(correct.statusCode()).isEqualTo(429);

        // The lockout lifts on its own, so a mistyped password does not shut a
        // legitimate user out for good.
        clock.advance(Duration.ofMinutes(16));
        HttpResponse<String> afterWindow = send(post("/api/auth/login", """
                {"password":"%s"}""".formatted(PASSWORD)));
        assertThat(afterWindow.statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("the session probe confirms a good token")
    void sessionProbeAnswersForAValidToken() {
        ResponseEntity<JsonNode> res =
                authenticated.getForEntity("/api/auth/session", JsonNode.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().path("authenticated").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("actuator health stays open, so probes do not need a password")
    void healthIsNotBehindTheGate() throws Exception {
        HttpResponse<String> res = send(get("/actuator/health"));

        assertThat(res.statusCode()).isEqualTo(200);
    }
}
