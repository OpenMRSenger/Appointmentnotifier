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
import org.springframework.stereotype.Component;

/**
 * Low-level HTTP client that POSTs outbox entries to the configured SaaS endpoint.
 *
 * <p>Routing logic:
 * <ul>
 *   <li>If the endpoint URL contains {@code discord.com/api/webhooks}, the payload is
 *       reformatted as a Discord embed by {@link DiscordPayloadBuilder} before sending.</li>
 *   <li>Otherwise the raw FHIR R4 JSON is POSTed with the {@code X-Messaging-Provider}
 *       header set to the value of the {@code appointmentnotifier.messagingProvider}
 *       global property (e.g. {@code SwiftSend}).</li>
 * </ul>
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
	 * @param endpoint      target URL (Discord webhook or generic SaaS endpoint)
	 * @param provider      value for the {@code X-Messaging-Provider} header
	 * @param encounterUuid the encounter UUID (used for logging and Discord embed)
	 * @param fhirPayload   the JSON string stored in the outbox
	 * @return {@code true} if the server responded with HTTP 2xx
	 */
	public boolean sendPayload(String endpoint, String provider,
	        String encounterUuid, String fhirPayload) {

		boolean isDiscord = endpoint != null && endpoint.contains("discord.com/api/webhooks");

		String body = isDiscord
		        ? DiscordPayloadBuilder.build(encounterUuid, fhirPayload)
		        : fhirPayload;

		return doPost(endpoint, body, provider, encounterUuid, isDiscord);
	}

	// ── HTTP mechanics ────────────────────────────────────────────────────────

	private boolean doPost(String endpoint, String body, String provider,
	        String encounterUuid, boolean isDiscord) {

		HttpURLConnection conn = null;
		try {
			conn = (HttpURLConnection) new URL(endpoint).openConnection();
			conn.setRequestMethod("POST");
			conn.setDoOutput(true);
			conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
			conn.setReadTimeout(READ_TIMEOUT_MS);
			conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");

			if (!isDiscord) {
				// SaaS-specific headers — Discord ignores unknown headers but we skip
				// them for cleanliness to avoid Discord rejecting the request.
				conn.setRequestProperty("X-Messaging-Provider", provider);
				conn.setRequestProperty("X-Source", "appointmentnotifier-omod");
			}

			byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
			conn.setRequestProperty("Content-Length", String.valueOf(bytes.length));

			try (OutputStream os = conn.getOutputStream()) {
				os.write(bytes);
			}

			int httpStatus = conn.getResponseCode();

			if (httpStatus >= 200 && httpStatus < 300) {
				log.info("Dispatched encounter " + encounterUuid + " → HTTP " + httpStatus);
				return true;
			}

			// Read error body for debugging
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
