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

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Converts a stored FHIR R4 JSON payload into a Discord webhook embed message.
 *
 * <p>Handles both payloads produced by the fhir2 translator (standard FHIR Encounter)
 * and the fallback JSON emitted by {@link org.openmrs.module.appointmentnotifier.fhir.FhirEncounterSerializer}
 * when fhir2 is not available (contains a non-standard {@code _patientContact} block).
 *
 * <p>This class is stateless and uses only Jackson (available in the OpenMRS runtime).
 */
public final class DiscordPayloadBuilder {

	private static final Log log = LogFactory.getLog(DiscordPayloadBuilder.class);

	private static final ObjectMapper MAPPER = new ObjectMapper();

	// Discord embed colours (decimal RGB)
	private static final int COLOR_BLUE   = 3_447_003;  // Encounter created
	private static final int COLOR_YELLOW = 16_776_960; // Encounter updated
	private static final int COLOR_RED    = 15_158_332; // Encounter voided

	private DiscordPayloadBuilder() {}

	// ── Public API ────────────────────────────────────────────────────────────

	/**
	 * Builds a Discord embed JSON string from a FHIR payload stored in the outbox.
	 *
	 * @param encounterUuid the encounter UUID (used as fallback identifier)
	 * @param fhirPayload   the JSON string from {@code saas_integration_queue.fhir_payload}
	 * @return Discord webhook JSON with one embed
	 */
	public static String build(String encounterUuid, String fhirPayload) {
		try {
			JsonNode root = MAPPER.readTree(fhirPayload);
			return buildEmbed(encounterUuid, root);
		}
		catch (Exception e) {
			log.warn("Could not parse FHIR payload for Discord embed, using minimal message: " + e.getMessage());
			return buildMinimalMessage(encounterUuid);
		}
	}

	// ── Embed construction ────────────────────────────────────────────────────

	private static String buildEmbed(String encounterUuid, JsonNode root) {
		String patientDisplay = root.path("subject").path("display").asText(null);
		String start          = root.path("period").path("start").asText(null);
		String status         = root.path("status").asText("unknown");

		// Location: FHIR Encounter.location[0].location.display
		String location = null;
		JsonNode locationArr = root.path("location");
		if (locationArr.isArray() && locationArr.size() > 0) {
			location = locationArr.get(0).path("location").path("display").asText(null);
		}

		// Patient contact — present in fallback JSON under _patientContact;
		// in real FHIR R4 the phone would be on the Patient resource.
		String phone = null;
		String email = null;
		JsonNode contact = root.path("_patientContact");
		if (!contact.isMissingNode()) {
			phone = nullIfEmpty(contact.path("phone").asText(null));
			email = nullIfEmpty(contact.path("email").asText(null));
		}

		// Try to also extract from FHIR Patient.telecom if this is a Bundle
		if (phone == null && root.path("resourceType").asText("").equals("Bundle")) {
			phone = extractTelecomFromBundle(root, "phone");
			email = extractTelecomFromBundle(root, "email");
		}

		int color = deriveColor(status);
		String readableDate = formatReadable(start);

		StringBuilder fields = new StringBuilder();
		appendField(fields, "Patient",  or(patientDisplay, "Unknown"), true,  false);
		appendField(fields, "Status",   status,                         true,  true);
		appendField(fields, "Start",    readableDate,                   true,  true);
		if (location != null) appendField(fields, "Location", location, true,  true);
		if (phone    != null) appendField(fields, "Phone",    phone,    true,  true);
		if (email    != null) appendField(fields, "Email",    email,    true,  true);
		appendField(fields, "Encounter UUID", or(encounterUuid, "—"),   false, true);

		String nowIso = formatIso(new Date());

		return "{\"embeds\":[{"
		        + q("title") + ":" + q("📋 Encounter Event") + ","
		        + "\"color\":" + color + ","
		        + "\"fields\":[" + fields + "],"
		        + "\"footer\":{\"text\":\"OpenMRS Appointment Notifier — SaaS Outbox\"},"
		        + q("timestamp") + ":" + q(nowIso)
		        + "}]}";
	}

	private static String buildMinimalMessage(String encounterUuid) {
		return "{\"content\":\"📋 Encounter event queued for delivery: `" + encounterUuid + "`\"}";
	}

	// ── Field helpers ─────────────────────────────────────────────────────────

	private static void appendField(StringBuilder sb, String name, String value,
	        boolean inline, boolean comma) {
		if (comma && sb.length() > 0) sb.append(",");
		sb.append("{")
		        .append(q("name")).append(":").append(q(name)).append(",")
		        .append(q("value")).append(":").append(q(or(value, "—"))).append(",")
		        .append("\"inline\":").append(inline)
		        .append("}");
	}

	// ── FHIR Bundle patient telecom extraction ────────────────────────────────

	private static String extractTelecomFromBundle(JsonNode bundle, String system) {
		JsonNode entries = bundle.path("entry");
		if (!entries.isArray()) return null;
		for (JsonNode entry : entries) {
			JsonNode resource = entry.path("resource");
			if ("Patient".equals(resource.path("resourceType").asText(""))) {
				JsonNode telecoms = resource.path("telecom");
				if (telecoms.isArray()) {
					for (JsonNode t : telecoms) {
						if (system.equals(t.path("system").asText(""))) {
							return nullIfEmpty(t.path("value").asText(null));
						}
					}
				}
			}
		}
		return null;
	}

	// ── Formatting utilities ──────────────────────────────────────────────────

	private static int deriveColor(String status) {
		if (status == null) return COLOR_BLUE;
		switch (status.toLowerCase()) {
			case "cancelled":
			case "entered-in-error":
				return COLOR_RED;
			case "finished":
			case "in-progress":
				return COLOR_YELLOW;
			default:
				return COLOR_BLUE;
		}
	}

	private static String formatIso(Date date) {
		SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
		sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
		return sdf.format(date);
	}

	private static String formatReadable(String iso) {
		if (iso == null || iso.isEmpty()) return "Unknown";
		try {
			SimpleDateFormat in  = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
			in.setTimeZone(TimeZone.getTimeZone("UTC"));
			SimpleDateFormat out = new SimpleDateFormat("d MMM yyyy, HH:mm 'UTC'", java.util.Locale.ENGLISH);
			out.setTimeZone(TimeZone.getTimeZone("UTC"));
			// strip trailing Z/offset before parsing
			String clean = iso.replaceAll("[Z+].*$", "").replace("T", "T");
			return out.format(in.parse(iso.substring(0, Math.min(iso.length(), 19))));
		}
		catch (Exception ignored) { return iso; }
	}

	private static String q(String value) {
		if (value == null) return "null";
		return "\"" + value
		        .replace("\\", "\\\\")
		        .replace("\"", "\\\"")
		        .replace("\n", "\\n")
		        .replace("\r", "\\r")
		        + "\"";
	}

	private static String or(String value, String fallback) {
		return (value != null && !value.isEmpty()) ? value : fallback;
	}

	private static String nullIfEmpty(String s) {
		return (s == null || s.isEmpty()) ? null : s;
	}
}
