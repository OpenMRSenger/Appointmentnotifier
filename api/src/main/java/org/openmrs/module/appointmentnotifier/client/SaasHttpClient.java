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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Low-level HTTP client that POSTs outbox entries to the configured SaaS endpoint.
 *
 * <p>Dynamic headers (hospital name, provider credentials) are delegated to
 * {@link ConnectionHeadersApplier}. This class owns only the HTTP mechanics.
 *
 * <p>No retry logic or thread pool — retry orchestration belongs to
 * {@link org.openmrs.module.appointmentnotifier.task.SaasQueueTask}.
 */
@Component("saasHttpClient")
public class SaasHttpClient implements SaasDispatcher {

	private static final Log log = LogFactory.getLog(SaasHttpClient.class);

	private static final int CONNECT_TIMEOUT_MS = 5_000;

	private static final int READ_TIMEOUT_MS    = 15_000;

	private final ConnectionHeadersApplier headersApplier;

	@Autowired
	public SaasHttpClient(ConnectionHeadersApplier headersApplier) {
		this.headersApplier = headersApplier;
	}

	// ── SaasDispatcher ────────────────────────────────────────────────────────

	@Override
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

			headersApplier.applyHeaders(conn);

			if (webhookToken != null && !webhookToken.isEmpty()) {
				conn.setRequestProperty("Authorization", webhookToken);
			}

			byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
			conn.setRequestProperty("Content-Length", String.valueOf(bytes.length));

			try (OutputStream os = conn.getOutputStream()) {
				os.write(bytes);
			}

			int httpStatus = conn.getResponseCode();

			if (httpStatus >= 200 && httpStatus < 300) {
				log.info("Dispatched encounter " + encounterUuid + " → HTTP " + httpStatus);
				String responseBody = readStream(conn.getInputStream());
				parseAndLogOperationOutcome(responseBody);
				return true;
			}

			String errorBody = readStream(conn.getErrorStream());
			parseAndLogOperationOutcome(errorBody);
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

	private void parseAndLogOperationOutcome(String responseBody) {
		if (responseBody == null || responseBody.trim().isEmpty()) {
			return;
		}
		try {
			ObjectMapper mapper = new ObjectMapper();
			JsonNode root = mapper.readTree(responseBody);
			if (root != null && "OperationOutcome".equals(root.path("resourceType").asText())) {
				JsonNode issues = root.path("issue");
				if (issues.isArray()) {
					for (JsonNode issue : issues) {
						String severity = issue.path("severity").asText();
						String code = issue.path("code").asText();
						String diagnostics = issue.path("diagnostics").asText();
						if ("error".equalsIgnoreCase(severity) || "fatal".equalsIgnoreCase(severity)) {
							log.error("FHIR OperationOutcome validation error: severity=" + severity 
							        + ", code=" + code + ", diagnostics=" + diagnostics);
						} else {
							log.info("FHIR OperationOutcome issue: severity=" + severity 
							        + ", code=" + code + ", diagnostics=" + diagnostics);
						}
					}
				}
			}
		}
		catch (Exception e) {
			log.debug("Failed to parse response body as OperationOutcome: " + e.getMessage());
		}
	}

	private static String readStream(InputStream is) {
		if (is == null) return "";
		try (java.io.BufferedReader reader = new java.io.BufferedReader(
				new java.io.InputStreamReader(is, StandardCharsets.UTF_8))) {
			StringBuilder sb = new StringBuilder();
			String line;
			while ((line = reader.readLine()) != null) {
				sb.append(line).append("\n");
			}
			return sb.toString();
		}
		catch (Exception e) {
			log.debug("Failed to read stream", e);
			return "";
		}
	}

	private static String abbreviate(String s, int maxLen) {
		if (s == null) return "";
		return s.length() > maxLen ? s.substring(0, maxLen) + "…" : s;
	}
}
