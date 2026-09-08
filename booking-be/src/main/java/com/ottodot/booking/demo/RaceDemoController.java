package com.ottodot.booking.demo;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Demo-only. Would not ship: it writes throwaway students and starts threads. */
@RestController
@RequestMapping("/api/demo")
public class RaceDemoController {

    private final RaceDemoService raceDemo;

    public RaceDemoController(RaceDemoService raceDemo) {
        this.raceDemo = raceDemo;
    }

    public record RaceRequest(@NotNull Long trialClassId, Integer contenders) {
        public int contendersOrDefault() {
            return contenders == null ? 8 : contenders;
        }
    }

    @PostMapping("/race")
    public RaceDemoService.RaceReport race(@Valid @RequestBody RaceRequest req) {
        return raceDemo.run(req.trialClassId(), req.contendersOrDefault());
    }
}
