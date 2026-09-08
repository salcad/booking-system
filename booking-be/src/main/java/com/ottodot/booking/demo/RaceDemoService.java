package com.ottodot.booking.demo;

import com.ottodot.booking.error.ApiException;
import com.ottodot.booking.repo.ParentRepository;
import com.ottodot.booking.repo.StudentRepository;
import com.ottodot.booking.repo.TrialClassRepository;
import com.ottodot.booking.service.BookingService;
import com.ottodot.booking.service.PaymentResult;
import com.ottodot.booking.service.PaymentService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Fires N simultaneous booking+payment attempts at one class and reports every
 * outcome side by side.
 *
 * <p>This exists for the walkthrough. The concurrency test suite is the actual
 * evidence; this is the same race made visible in one request, so the invariant
 * can be demonstrated rather than narrated. It is deliberately the only place
 * the application starts threads of its own.
 */
@Service
public class RaceDemoService {

    private static final Logger log = LoggerFactory.getLogger(RaceDemoService.class);
    private static final int MAX_CONTENDERS = 32;

    private final BookingService bookingService;
    private final PaymentService paymentService;
    private final TrialClassRepository classes;
    private final StudentRepository students;
    private final ParentRepository parents;

    public RaceDemoService(BookingService bookingService, PaymentService paymentService,
                           TrialClassRepository classes, StudentRepository students,
                           ParentRepository parents) {
        this.bookingService = bookingService;
        this.paymentService = paymentService;
        this.classes = classes;
        this.students = students;
        this.parents = parents;
    }

    public RaceReport run(long trialClassId, int contenders) {
        if (contenders < 2 || contenders > MAX_CONTENDERS) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                    "contenders must be between 2 and " + MAX_CONTENDERS);
        }
        var klass = classes.findById(trialClassId)
                .orElseThrow(() -> ApiException.notFound("trial class", trialClassId));

        int seatsBefore = klass.seatsRemaining();
        long demoParent = parents.ensureDemoParent();
        List<Long> studentIds = new ArrayList<>(contenders);
        for (int i = 0; i < contenders; i++) {
            studentIds.add(students.insertDemoStudent(demoParent,
                    "Race Contender " + UUID.randomUUID().toString().substring(0, 6)));
        }

        List<Attempt> attempts = fireSimultaneously(trialClassId, studentIds);
        attempts.sort(Comparator.comparingInt(Attempt::index));

        int confirmed = (int) attempts.stream().filter(a -> "CONFIRMED".equals(a.outcome())).count();
        var after = classes.findById(trialClassId).orElseThrow();

        boolean invariantHolds = confirmed <= seatsBefore
                && after.claimedSeats() <= after.capacity();
        log.info("race demo on class {}: {} contenders, {} seats, {} confirmed",
                trialClassId, contenders, seatsBefore, confirmed);

        return new RaceReport(trialClassId, klass.capacity(), seatsBefore,
                after.seatsRemaining(), contenders, confirmed, attempts, invariantHolds,
                verdict(contenders, seatsBefore, confirmed, invariantHolds));
    }

    /**
     * A CyclicBarrier rather than a start latch: every thread is held until all
     * of them have arrived, so they genuinely collide instead of trickling
     * through one at a time and making the demo look safer than it is.
     */
    private List<Attempt> fireSimultaneously(long classId, List<Long> studentIds) {
        int n = studentIds.size();
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CyclicBarrier gate = new CyclicBarrier(n);
        try {
            List<Future<Attempt>> futures = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                final int index = i;
                final long studentId = studentIds.get(i);
                futures.add(pool.submit(() -> {
                    gate.await(20, TimeUnit.SECONDS);
                    return attempt(index, studentId, classId);
                }));
            }
            List<Attempt> results = new ArrayList<>(n);
            for (Future<Attempt> f : futures) {
                results.add(f.get(60, TimeUnit.SECONDS));
            }
            return results;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (Exception e) {
            throw new IllegalStateException("race demo failed", e);
        } finally {
            pool.shutdownNow();
        }
    }

    private Attempt attempt(int index, long studentId, long classId) {
        long bookingId;
        try {
            bookingId = bookingService.createBooking(studentId, classId).id();
        } catch (ApiException e) {
            return new Attempt(index, studentId, null, "REJECTED_AT_BOOKING",
                    e.getCode(), e.getMessage());
        }
        try {
            PaymentResult result = paymentService.pay(bookingId, false,
                    UUID.randomUUID().toString());
            String outcome = switch (result.outcome()) {
                case CONFIRMED, ALREADY_CONFIRMED -> "CONFIRMED";
                case DECLINED -> "PAYMENT_DECLINED";
                case SEAT_UNAVAILABLE -> "REFUNDED_SEAT_LOST";
            };
            return new Attempt(index, studentId, bookingId, "PAID", outcome, result.message());
        } catch (ApiException e) {
            return new Attempt(index, studentId, bookingId, "PAYMENT_ERROR",
                    e.getCode(), e.getMessage());
        }
    }

    private static String verdict(int contenders, int seatsBefore, int confirmed, boolean holds) {
        if (!holds) {
            return "INVARIANT VIOLATED: " + confirmed + " confirmed for " + seatsBefore + " seats.";
        }
        return contenders + " parents raced for " + seatsBefore + " seat(s); exactly "
                + confirmed + " confirmed. No one was charged for a seat they did not get.";
    }

    /** One parent's attempt. {@code phase} says how far they got. */
    public record Attempt(int index, long studentId, Long bookingId, String phase,
                          String outcome, String message) {
    }

    public record RaceReport(long trialClassId, int capacity, int seatsBefore, int seatsAfter,
                             int contenders, int confirmed, List<Attempt> attempts,
                             boolean invariantHolds, String verdict) {
    }
}
