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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.api.AdministrationService;
import org.openmrs.api.context.Context;
import org.openmrs.module.appointmentnotifier.AppointmentNotifierConstants;
import org.springframework.stereotype.Component;

import static org.openmrs.module.appointmentnotifier.AppointmentNotifierConstants.*;

/**
 * Low-level HTTP client that POSTs outbox entries to the configured SaaS endpoint.
 *
 * <p>Provider credentials are read directly from OpenMRS global properties so the SaaS backend
 * can forward them to the appropriate messaging provider:
 * <ul>
 *   <li><strong>SWIFTSEND / ASYNCFLOW</strong> — {@code X-Messaging-Provider-Token} (API key)</li>
 *   <li><strong>LEGACYLINK</strong> — {@code X-Messaging-Provider-Username} / {@code X-Messaging-Provider-Password} (BASIC auth)</li>
 *   <li><strong>SECUREPOST</strong> — {@code X-Messaging-Provider-Client-Id} / {@code X-Messaging-Provider-Client-Secret} (JWT)</li>
 * </ul>
 * Headers are omitted when the corresponding global property is blank.
 *
 * <p>This class is intentionally thin — no retry logic, no thread pool.
 * Retry orchestration is the responsibility of {@link org.openmrs.module.appointmentnotifier.task.SaasQueueTask}.
 */
@Component("saasHttpClient")
public class SaasHttpClient {

	private static final Log log = LogFactory.getLog(SaasHttpClient.class);

	private static final int CONNECT_TIMEOUT_MS = 5_000;

	private static final int READ_TIMEOUT_MS    = 15_000;

	// ── Public API ────────────────────────────────────────────────────────────

	/**
	 * Dispatches one outbox entry.
	 *
	 * @param endpoint      target SaaS endpoint URL
	 * @param webhookToken  secret key for the SaaS endpoint; sent as-is in the {@code Authorization} header (skipped if blank)
	 * @param encounterUuid the encounter UUID (used for logging)
	 * @param fhirPayload   the JSON string stored in the outbox
	 * @return {@code true} if the server responded with HTTP 2xx
	 */
	public boolean sendPayload(String endpoint, String webhookToken,
	        String encounterUuid, String fhirPayload) {

		return doPost(endpoint, fhirPayload, webhookToken, encounterUuid);
	}

	// ── HTTP mechanics ────────────────────────────────────────────────────────

	private boolean doPost(String endpoint, String body, String webhookToken, String encounterUuid) {

		HttpURLConnection conn = null;
		try {
			conn = (HttpURLConnection) new URL(endpoint).openConnection();
			conn.setRequestMethod("POST");
			conn.setDoOutput(true);
			conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
			conn.setReadTimeout(READ_TIMEOUT_MS);
			conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
			conn.setRequestProperty("X-Source", "appointmentnotifier-omod");
			AdministrationService admin = Context.getAdministrationService();
			String hospitalName = admin.getGlobalProperty(GP_HOSPITAL_NAME, "");
			if (hospitalName == null || hospitalName.isEmpty()) {
				hospitalName = admin.getGlobalProperty("hospitalName", "Unknown Hospital");
			}
			log.debug("Setting X-Hospital-Name header to: '" + hospitalName + "'");
			conn.setRequestProperty("X-Hospital-Name", hospitalName);

			applyProviderHeaders(conn);

			if (webhookToken != null && !webhookToken.isEmpty()) {
				conn.setRequestProperty("Authorization", webhookToken);
			}

			byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
			conn.setRequestProperty("Content-Length", String.valueOf(bytes.length));

			try (OutputStream os = conn.getOutputStream()) {
				os.write(bytes);
			}

			int httpStatus = conn.getResponseCode();

			if (httpStatus == 200) {
				log.info("Dispatched encounter " + encounterUuid + " → HTTP 200");
				return true;
			}

			String errorBody = readStream(conn.getErrorStream());
			log.warn("Endpoint returned HTTP " + httpStatus + " for encounter "
			        + encounterUuid + ". Body: " + abbreviate(errorBody, 300));
			return false;

		}
		catch (IOException e) {
			log.error("POST failed for encounter " + encounterUuid
			        + " endpoint=" + endpoint + ": " + e.getMessage());
			return false;
		}
		finally {
			if (conn != null) conn.disconnect();
		}
	}

	/**
	 * Reads provider identity and credentials from global properties and sets the appropriate
	 * request headers. Headers for blank values are omitted so the SaaS ignores them.
	 *
	 * <pre>
	 * Provider          Headers set
	 * ──────────────    ──────────────────────────────────────────────────────
	 * SWIFTSEND         X-Messaging-Provider, X-Messaging-Provider-Token
	 * ASYNCFLOW         X-Messaging-Provider, X-Messaging-Provider-Token
	 * LEGACYLINK        X-Messaging-Provider, X-Messaging-Provider-Username,
	 *                                          X-Messaging-Provider-Password
	 * SECUREPOST        X-Messaging-Provider, X-Messaging-Provider-Client-Id,
	 *                                          X-Messaging-Provider-Client-Secret
	 * </pre>
	 */
	private void applyProviderHeaders(HttpURLConnection conn) {
		AdministrationService admin = Context.getAdministrationService();

		String provider = admin.getGlobalProperty(GP_MESSAGING_PROVIDER, DEFAULT_PROVIDER);
		conn.setRequestProperty("X-Messaging-Provider", provider);

		setIfPresent(conn, "X-Messaging-Provider-Token",
		    admin.getGlobalProperty(GP_MESSAGING_PROVIDER_TOKEN, ""));

		setIfPresent(conn, "X-Messaging-Provider-Username",
		    admin.getGlobalProperty(GP_MESSAGING_PROVIDER_USERNAME, ""));

		setIfPresent(conn, "X-Messaging-Provider-Password",
		    admin.getGlobalProperty(GP_MESSAGING_PROVIDER_PASSWORD, ""));

		setIfPresent(conn, "X-Messaging-Provider-Client-Id",
		    admin.getGlobalProperty(GP_MESSAGING_PROVIDER_CLIENT_ID, ""));

		setIfPresent(conn, "X-Messaging-Provider-Client-Secret",
		    admin.getGlobalProperty(GP_MESSAGING_PROVIDER_CLIENT_SECRET, ""));
	}

	private static void setIfPresent(HttpURLConnection conn, String header, String value) {
		if (value != null && !value.isEmpty()) {
			conn.setRequestProperty(header, value);
		}
	}

	// ── Utilities ─────────────────────────────────────────────────────────────

	private static String readStream(InputStream is) {
		if (is == null) return "";
		try {
			return new String(is.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (Exception ignored) { return ""; }
	}

	private static String abbreviate(String s, int maxLen) {
		if (s == null) return "";
		return s.length() > maxLen ? s.substring(0, maxLen) + "…" : s;
	}
}
