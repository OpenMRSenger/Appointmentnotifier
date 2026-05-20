/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.appointmentnotifier.client;

/**
 * Abstracts the transport layer for dispatching outbox entries to the SaaS endpoint. Callers depend
 * on this interface, not on the concrete HTTP implementation.
 */
public interface SaasDispatcher {
	
	/**
	 * Dispatches one outbox entry.
	 * 
	 * @param endpoint target SaaS endpoint URL
	 * @param webhookToken secret sent as-is in the {@code Authorization} header (skipped if blank)
	 * @param encounterUuid the encounter UUID (used for logging)
	 * @param fhirPayload the JSON string stored in the outbox
	 * @return {@code true} if the server responded with HTTP 2xx
	 */
	boolean sendPayload(String endpoint, String webhookToken, String encounterUuid, String fhirPayload);
}
