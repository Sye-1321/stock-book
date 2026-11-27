package io.github.sye1321.stockbook.finance;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record JournalPostingRequest(
        JournalSourceType sourceType,
        UUID sourceId,
        PostingPurpose postingPurpose,
        LocalDate effectiveDate,
        UUID actorId,
        UUID reversesJournalId,
        List<JournalLineRequest> lines) {}
