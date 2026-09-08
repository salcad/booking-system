package com.ottodot.booking;

import static org.assertj.core.api.Assertions.assertThat;

import com.ottodot.booking.error.ApiException;
import com.ottodot.booking.service.BookingService;
import com.ottodot.booking.service.PaymentResult;
import com.ottodot.booking.service.PaymentService;
import com.ottodot.booking.support.AbstractIntegrationTest;
import com.ottodot.booking.support.Concurrency;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The overbooking invariant (I1) under real contention.
 *
 * <p>This is the test that has to be true for anything else to matter: no
 * matter how many parents book simultaneously, a class of capacity N ends with
 * exactly N confirmed students and never one more.
 */
class SeatClaimConcurrencyTest extends AbstractIntegrationTest {

    @Autowired
    BookingService bookingService;

    @Autowired
    PaymentService paymentService;

    private enum Outcome { CONFIRMED, CLASS_FULL, DUPLICATE, DECLINED, SEAT_UNAVAILABLE }

    /** One parent's full journey: claim a seat, then pay for it. */
    private Outcome bookAndPay(long studentId, long classId) {
        long bookingId;
        try {
            bookingId = bookingService.createBooking(studentId, classId).id();
        } catch (ApiException e) {
            return switch (e.getCode()) {
                case "CLASS_FULL" -> Outcome.CLASS_FULL;
                case "DUPLICATE_BOOKING" -> Outcome.DUPLICATE;
                default -> throw e;
            };
        }
        PaymentResult result = paymentService.pay(bookingId, false, UUID.randomUUID().toString());
        return switch (result.outcome()) {
            case CONFIRMED, ALREADY_CONFIRMED -> Outcome.CONFIRMED;
            case DECLINED -> Outcome.DECLINED;
            case SEAT_UNAVAILABLE -> Outcome.SEAT_UNAVAILABLE;
        };
    }

    @RepeatedTest(10)
    @DisplayName("20 parents storm an empty 4-seat class: exactly 4 get in")
    void neverOverbooksUnderLoad() {
        int capacity = 4;
        int contenders = 20;
        long classId = fixtures.trialClass(capacity);
        long parentId = fixtures.parent();
        List<Long> studentIds = java.util.stream.IntStream.range(0, contenders)
                .mapToObj(i -> fixtures.student(parentId)).toList();

        List<Outcome> outcomes = Concurrency.inParallel(contenders,
                i -> bookAndPay(studentIds.get(i), classId));

        assertThat(outcomes.stream().filter(o -> o == Outcome.CONFIRMED).count())
                .as("exactly capacity students may be confirmed")
                .isEqualTo(capacity);
        assertThat(outcomes.stream().filter(o -> o == Outcome.CLASS_FULL).count())
                .as("everyone else is turned away at booking time, before being charged")
                .isEqualTo(contenders - capacity);
        assertThat(fixtures.countByStatus(classId, "CONFIRMED")).isEqualTo(capacity);
        fixtures.assertInvariants(classId);
    }

    @ParameterizedTest(name = "capacity {0}, {1} concurrent parents")
    @CsvSource({
            "1, 2", "1, 8", "1, 20",
            "2, 8", "3, 12", "4, 8", "4, 20", "4, 32"
    })
    @DisplayName("the invariant holds across capacities and contention levels")
    void neverOverbooksAtAnyCapacity(int capacity, int contenders) {
        long classId = fixtures.trialClass(capacity);
        long parentId = fixtures.parent();
        List<Long> studentIds = java.util.stream.IntStream.range(0, contenders)
                .mapToObj(i -> fixtures.student(parentId)).toList();

        List<Outcome> outcomes = Concurrency.inParallel(contenders,
                i -> bookAndPay(studentIds.get(i), classId));

        int expectedWinners = Math.min(capacity, contenders);
        assertThat(outcomes.stream().filter(o -> o == Outcome.CONFIRMED).count())
                .isEqualTo(expectedWinners);
        assertThat(fixtures.claimedSeats(classId)).isEqualTo(expectedWinners);
        fixtures.assertInvariants(classId);
    }

    @RepeatedTest(10)
    @DisplayName("12 parents race for one remaining seat: exactly one wins")
    void exactlyOneParentWinsTheLastSeat() {
        int contenders = 12;
        // 3 of 4 seats already confirmed - the fixture the brief describes.
        long classId = fixtures.trialClassWithConfirmed(4, 3);
        long parentId = fixtures.parent();
        List<Long> studentIds = java.util.stream.IntStream.range(0, contenders)
                .mapToObj(i -> fixtures.student(parentId)).toList();

        List<Outcome> outcomes = Concurrency.inParallel(contenders,
                i -> bookAndPay(studentIds.get(i), classId));

        assertThat(outcomes.stream().filter(o -> o == Outcome.CONFIRMED).count())
                .as("at most one user may end up confirmed for the last available seat")
                .isEqualTo(1);
        assertThat(fixtures.countByStatus(classId, "CONFIRMED")).isEqualTo(4);
        assertThat(fixtures.claimedSeats(classId)).isEqualTo(4);
        fixtures.assertInvariants(classId);
    }
}
