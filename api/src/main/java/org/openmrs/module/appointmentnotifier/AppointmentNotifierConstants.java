/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.appointmentnotifier;

/** Central registry of Global Property keys and module-wide constants. */
public final class AppointmentNotifierConstants {
	
	// ── Global Property keys ──────────────────────────────────────────────────
	
	public static final String GP_SAAS_ENDPOINT = "appointmentnotifier.saasEndpoint";
	
	public static final String GP_MESSAGING_PROVIDER = "appointmentnotifier.messagingProvider";
	
	public static final String GP_OPENMRS_URL = "appointmentnotifier.openmrsBaseUrl";
	
	public static final String GP_USERNAME = "appointmentnotifier.fhirUsername";
	
	public static final String GP_PASSWORD = "appointmentnotifier.fhirPassword";
	
	public static final String GP_ENABLED = "appointmentnotifier.enabled";
	
	public static final String GP_MAX_RETRIES = "appointmentnotifier.maxRetries";
	
	// ── Scheduler task ────────────────────────────────────────────────────────
	
	/** Human-readable name stored in the OpenMRS scheduler table. */
	public static final String TASK_NAME = "SaaS Queue Dispatcher";
	
	/** Dispatch interval: every 5 minutes. */
	public static final long TASK_INTERVAL_SECONDS = 300L;
	
	// ── Defaults ─────────────────────────────────────────────────────────────
	
	public static final String DEFAULT_ENDPOINT = "http://host.docker.internal:8888/api/v1/appointment";
	
	public static final String DEFAULT_PROVIDER = "Generic";
	
	public static final int DEFAULT_MAX_RETRIES = 5;
	
	private AppointmentNotifierConstants() {
	}
}
