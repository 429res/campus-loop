package edu.campusloop.web.exchange.service;

import edu.campusloop.exchange.ExchangeCreationCommand;

/**
 * The ONE creation transaction boundary, owned by A-03. No implementation is installed in B-03.
 * Check (initiator,key,digest) replay BEFORE live eligibility: the first creation reserves/version-bumps items.
 * On a new request lock and re-read users, demands, items/holds in the agreed order, invoke B's
 * ExchangeCycleValidator, and atomically persist exchange, participants, demand references and holds.
 * Apply DB UTC +24h, all participants unconfirmed, item version increments and exact demand freeze.
 * Return an existing/new committed exchange ID; never expose a partially persisted result.
 */
public interface ExchangeCreationTransaction {
    long create(long initiatorId, ExchangeCreationCommand command);
}
