package com.fusionsolar.monitoring.service.sync;

import com.fusionsolar.monitoring.persistence.document.SiteHistoryDocument;

import java.time.Instant;

/** Metadonnees techniques de collecte conservees uniquement dans MongoDB/backend. */
public final class HistoryCollectionStateSupport {
    private HistoryCollectionStateSupport() {
    }

    public static void markSuccessful(SiteHistoryDocument document, Instant now) {
        if (document == null) {
            return;
        }
        Instant safeNow = now != null ? now : Instant.now();
        document.setCollectionStatus("SUCCESS");
        document.setConsecutiveEmptyAttempts(0);
        document.setNextRetryAt(null);
        document.setLastSuccessfulSyncAt(safeNow);
        document.setLastCollectionError(null);
    }
}
