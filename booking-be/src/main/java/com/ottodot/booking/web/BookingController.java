package com.ottodot.booking.web;

import com.ottodot.booking.domain.Booking;
import com.ottodot.booking.error.ApiException;
import com.ottodot.booking.repo.BookingEventRepository;
import com.ottodot.booking.repo.BookingRepository;
import com.ottodot.booking.service.BookingService;
import com.ottodot.booking.service.PaymentResult;
import com.ottodot.booking.service.PaymentService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    private final BookingService bookingService;
    private final PaymentService paymentService;
    private final BookingRepository bookings;
    private final BookingEventRepository events;
    private final int priceCents;

    public BookingController(BookingService bookingService, PaymentService paymentService,
                             BookingRepository bookings, BookingEventRepository events,
                             @Value("${booking.trial-price-cents}") int priceCents) {
        this.bookingService = bookingService;
        this.paymentService = paymentService;
        this.bookings = bookings;
        this.events = events;
        this.priceCents = priceCents;
    }

    /** 409 CLASS_FULL or 409 DUPLICATE_BOOKING are the interesting responses. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Dtos.BookingView create(@Valid @RequestBody Dtos.CreateBookingRequest req) {
        Booking b = bookingService.createBooking(req.studentId(), req.trialClassId());
        return view(b);
    }

    @GetMapping("/{id}")
    public Dtos.BookingView get(@PathVariable long id) {
        return bookings.findById(id).map(this::view)
                .orElseThrow(() -> ApiException.notFound("booking", id));
    }

    /**
     * A declined payment is a successful request with an unsuccessful outcome,
     * so the service returns a result rather than throwing — throwing would
     * roll back the PAYMENT_FAILED transition it needs to persist. The HTTP
     * status is chosen here, after that transaction has committed.
     */
    @PostMapping("/{id}/payment")
    public ResponseEntity<Dtos.PaymentResponse> pay(@PathVariable long id,
                                                    @Valid @RequestBody Dtos.PaymentRequest req) {
        boolean requestDecline = req.outcome() == Dtos.PaymentRequest.Outcome.FAILURE;
        PaymentResult result = paymentService.pay(id, requestDecline, req.idempotencyKey());

        HttpStatus status = switch (result.outcome()) {
            case CONFIRMED, ALREADY_CONFIRMED -> HttpStatus.OK;
            case DECLINED -> HttpStatus.PAYMENT_REQUIRED;
            case SEAT_UNAVAILABLE -> HttpStatus.CONFLICT;
        };

        return ResponseEntity.status(status).body(new Dtos.PaymentResponse(
                result.booking().id(), result.booking().status(),
                result.paymentStatus().name(), result.outcome().name(), result.message()));
    }

    @PostMapping("/{id}/cancel")
    public Dtos.BookingView cancel(@PathVariable long id) {
        return view(bookingService.cancel(id));
    }

    private Dtos.BookingView view(Booking b) {
        return Dtos.BookingView.of(b, priceCents,
                events.findByBooking(b.id()).stream().map(Dtos.EventView::of).toList());
    }
}
