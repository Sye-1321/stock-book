package io.github.sye1321.stockbook.identity;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

final class MutableClock extends Clock {

    private final AtomicReference<Instant> instant = new AtomicReference<>();

    MutableClock(Instant initialInstant) {
        instant.set(initialInstant);
    }

    void reset(Instant value) {
        instant.set(value);
    }

    void advance(Duration duration) {
        instant.updateAndGet(current -> current.plus(duration));
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return instant.get();
    }
}
