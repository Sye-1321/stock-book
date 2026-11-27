package io.github.sye1321.stockbook.finance;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record JournalEntry(
        UUID id,
        UUID periodId,
        JournalSourceType sourceType,
        UUID sourceId,
        PostingPurpose postingPurpose,
        LocalDate effectiveDate,
        Instant recordedAt,
        UUID postedBy,
        UUID reversesJournalId,
        List<JournalLine> lines) {}
