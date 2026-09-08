package com.ottodot.booking.scheduler;

import com.ottodot.booking.domain.Booking;
import com.ottodot.booking.domain.BookingStatus;
import com.ottodot.booking.repo.BookingEventRepository;
import com.ottodot.booking.repo.BookingRepository;
import com.ottodot.booking.repo.TrialClassRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Releases seats held by parents who never completed checkout.
 *
 * <p>Without this, one abandoned checkout would hold a seat forever and a
 * 4-seat class could be permanently blocked by people who never paid. This is
 * the "background job" column of the checks-belong-where table.
 */
@Component
public class HoldReaper {

    private static final Logger log = LoggerFactory.getLogger(HoldReaper.class);
    private static final int BATCH_SIZE = 100;

    private final BookingRepository bookings;
    private final TrialClassRepository classes;
    private final BookingEventRepository events;
    private final Clock clock;
    private final Counter holdsExpired;

    public HoldReaper(BookingRepository bookings, TrialClassRepository classes,
                      BookingEventRepository events, Clock clock, MeterRegistry metrics) {
        this.bookings = bookings;
        this.classes = classes;
        this.events = events;
        this.clock = clock;
        this.holdsExpired = Counter.builder("booking.holds_expired")
                .description("Seat holds released after the checkout window lapsed")
                .register(metrics);
    }

    @Scheduled(fixedDelayString = "${booking.reaper-interval-ms}")
    public void sweep() {
        try {
            int released = releaseExpiredHolds();
            if (released > 0) {
                log.info("hold reaper released {} seat(s)", released);
            }
        } catch (RuntimeException e) {
            // Never let a failed sweep kill the scheduler thread.
            log.error("hold reaper sweep failed", e);
        }
    }

    /**
     * One transaction per sweep. lockExpiredHolds uses FOR UPDATE SKIP LOCKED,
     * so a booking that is currently mid-payment (its row locked by
     * PaymentService) is skipped rather than expired underneath the parent.
     */
    @Transactional
    public int releaseExpiredHolds() {
        List<Booking> expired = bookings.lockExpiredHolds(clock.instant(), BATCH_SIZE);
        for (Booking booking : expired) {
            bookings.updateStatus(booking.id(), BookingStatus.EXPIRED, null);
            classes.releaseSeat(booking.trialClassId());
            events.append(booking.id(), BookingStatus.PENDING_PAYMENT, BookingStatus.EXPIRED,
                    "hold expired before payment", "system");
            holdsExpired.increment();
        }
        return expired.size();
    }
}
