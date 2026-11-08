package com.fourbytees.indexing.service;

import java.io.Serializable;

public record BookClaim(
        String claimantId,
        long claimedAtMs
) implements Serializable {
}
