/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.appointmentnotifier.outbox;

import java.util.List;

/**
 * Service facade for the transactional outbox queue. Inject this interface wherever you need to
 * enqueue or process entries.
 */
public interface OutboxService {
	
	/**
	 * Persists a new PENDING entry for the given encounter.
	 * 
	 * @param encounterUuid UUID of the encounter that triggered the event
	 * @param fhirPayload FHIR R4 JSON to be delivered; may be null if FHIR2 is unavailable
	 */
	void enqueue(String encounterUuid, String fhirPayload);
	
	/**
	 * Returns up to 100 entries that are eligible for (re)dispatch, ordered by id ascending. An
	 * entry is eligible when status is PENDING or FAILED AND retry_count is below maxRetries.
	 * 
	 * @param maxRetries ceiling on retry_count (exclusive)
	 */
	List<OutboxEntry> findDispatchable(int maxRetries);
	
	/**
	 * Marks entry as SENT after a successful HTTP 2xx response.
	 */
	void markSent(int id);
	
	/**
	 * Marks entry as FAILED and increments retry_count after an HTTP error or exception.
	 */
	void markFailed(int id);
}
