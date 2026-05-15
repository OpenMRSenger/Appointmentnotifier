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

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.OpenmrsObject;
import org.openmrs.Patient;
import org.openmrs.api.context.Context;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class AppointmentServiceAdvice implements MethodInterceptor {

	private static final Log log = LogFactory.getLog(AppointmentServiceAdvice.class);

	/** @deprecated Use {@link AppointmentNotifierConstants} directly. */
	public static final String GP_SAAS_ENDPOINT = AppointmentNotifierConstants.GP_SAAS_ENDPOINT;
	/** @deprecated Use {@link AppointmentNotifierConstants} directly. */
	public static final String GP_OPENMRS_URL   = AppointmentNotifierConstants.GP_OPENMRS_URL;
	/** @deprecated Use {@link AppointmentNotifierConstants} directly. */
	public static final String GP_USERNAME      = AppointmentNotifierConstants.GP_USERNAME;
	/** @deprecated Use {@link AppointmentNotifierConstants} directly. */
	public static final String GP_PASSWORD      = AppointmentNotifierConstants.GP_PASSWORD;

	private static final String DEFAULT_ENDPOINT = AppointmentNotifierConstants.DEFAULT_ENDPOINT;

	private static final int CONNECT_TIMEOUT_MS = 5_000;
	private static final int READ_TIMEOUT_MS    = 10_000;

	private static final int COLOR_CREATED   = 3066993;
	private static final int COLOR_UPDATED   = 15974530;
	private static final int COLOR_CANCELLED = 15158332;
	private static final int COLOR_DELETED   = 9868950;

	private static AppointmentServiceAdvice instance;

	private final ExecutorService executor = Executors.newCachedThreadPool();

	public AppointmentServiceAdvice() {
		instance = this;
	}

	public static AppointmentServiceAdvice getInstance() {
		return instance;
	}

	// ── AOP intercept ────────────────────────────────────────────────────────────

	@Override
	public Object invoke(MethodInvocation invocation) throws Throwable {
		String method = invocation.getMethod().getName();

		if (!isTrackedMethod(method)) {
			return invocation.proceed();
		}

		String enabled = Context.getAdministrationService()
		        .getGlobalProperty("appointmentnotifier.enabled", "true");
		if (!"true".equalsIgnoreCase(enabled.trim())) {
			return invocation.proceed();
		}

		boolean isNew = false;
		if ("validateAndSave".equals(method)) {
			Object[] args = invocation.getArguments();
			if (args != null && args.length > 0 && args[0] != null) {
				try {
					Integer id = (Integer) args[0].getClass().getMethod("getId").invoke(args[0]);
					isNew = (id == null);
				}
				catch (Exception ignored) { }
			}
		}

		Object result = invocation.proceed();

		Object appointment = resolveAppointment(result, invocation.getArguments());
		if (appointment == null) {
			log.debug("AppointmentServiceAdvice: no Appointment object found for method=" + method);
			return result;
		}

		String eventType = classifyEvent(method, isNew);
		String uuid = safeUuid(appointment);
		log.debug("AppointmentServiceAdvice: event=" + eventType + " uuid=" + uuid);

		try {
			String endpoint = Context.getAdministrationService()
			        .getGlobalProperty(GP_SAAS_ENDPOINT, DEFAULT_ENDPOINT);

			final Object capturedAppt  = appointment;
			final String capturedEvent = eventType;
			executor.submit(() -> dispatchWebhook(capturedAppt, capturedEvent, endpoint));
		}
		catch (Exception e) {
			log.error("AppointmentServiceAdvice: failed to schedule webhook for event=" + eventType
			        + " uuid=" + uuid, e);
		}

		return result;
	}

	private boolean isTrackedMethod(String name) {
		switch (name) {
			case "validateAndSave":
			case "rescheduleAppointment":
			case "changeStatus":
			case "cancelAppointment":
			case "voidAppointment":
				return true;
			default:
				return false;
		}
	}

	private String classifyEvent(String method, boolean isNewAppointment) {
		switch (method) {
			case "validateAndSave":       return isNewAppointment ? "CREATED" : "UPDATED";
			case "rescheduleAppointment": return "UPDATED";
			case "changeStatus":          return "UPDATED";
			case "cancelAppointment":     return "CANCELLED";
			case "voidAppointment":       return "DELETED";
			default:                      return "UPDATED";
		}
	}

	private Object resolveAppointment(Object result, Object[] args) {
		if (result instanceof OpenmrsObject) {
			return result;
		}
		if (args != null && args.length > 0 && args[0] instanceof OpenmrsObject) {
			return args[0];
		}
		return null;
	}

	// ── Generic reflection helpers ────────────────────────────────────────────────

	private String safeUuid(Object obj) {
		if (obj instanceof OpenmrsObject) return ((OpenmrsObject) obj).getUuid();
		return null;
	}

	private String safeReflect(Object obj, String getter) {
		if (obj == null) return null;
		try {
			Object val = obj.getClass().getMethod(getter).invoke(obj);
			return val != null ? val.toString() : null;
		}
		catch (Exception ignored) { return null; }
	}

	private String extractDateIso(Object obj, String getter) {
		try {
			Object val = obj.getClass().getMethod(getter).invoke(obj);
			if (val instanceof Date) return formatIso((Date) val);
		}
		catch (Exception ignored) { }
		return null;
	}

	// ── Appointment field extractors ──────────────────────────────────────────────

	private Object extractPatient(Object appt) {
		try { return appt.getClass().getMethod("getPatient").invoke(appt); }
		catch (Exception ignored) { return null; }
	}

	private String apptId(Object appt) {
		try {
			Object id = appt.getClass().getMethod("getId").invoke(appt);
			return id != null ? id.toString() : null;
		}
		catch (Exception ignored) { return null; }
	}

	private String apptNumber(Object appt)     { return safeReflect(appt, "getAppointmentNumber"); }
	private String apptStatus(Object appt)     { return safeReflect(appt, "getStatus"); }
	private String apptKind(Object appt)       { return safeReflect(appt, "getAppointmentKind"); }
	private String apptPriority(Object appt)   { return safeReflect(appt, "getPriority"); }
	private String apptComments(Object appt)   { return safeReflect(appt, "getComments"); }
	private String apptVideoLink(Object appt)  { return safeReflect(appt, "getTeleHealthVideoLink"); }

	private String apptServiceName(Object appt) {
		try { return safeReflect(appt.getClass().getMethod("getService").invoke(appt), "getName"); }
		catch (Exception ignored) { return null; }
	}

	private String apptServiceUuid(Object appt) {
		try {
			Object svc = appt.getClass().getMethod("getService").invoke(appt);
			if (svc instanceof OpenmrsObject) return ((OpenmrsObject) svc).getUuid();
		}
		catch (Exception ignored) { }
		return null;
	}

	private String apptServiceType(Object appt) {
		try { return safeReflect(appt.getClass().getMethod("getServiceType").invoke(appt), "getName"); }
		catch (Exception ignored) { return null; }
	}

	private String apptLocation(Object appt) {
		try { return safeReflect(appt.getClass().getMethod("getLocation").invoke(appt), "getName"); }
		catch (Exception ignored) { return null; }
	}

	// ── Patient field extractors ──────────────────────────────────────────────────

	private String patientId(Object patient) {
		if (patient == null) return null;
		try {
			Object id = patient.getClass().getMethod("getId").invoke(patient);
			return id != null ? id.toString() : null;
		}
		catch (Exception ignored) { return null; }
	}

	private String patientUuid(Object patient) {
		if (patient instanceof OpenmrsObject) return ((OpenmrsObject) patient).getUuid();
		return null;
	}

	private String patientName(Object patient) {
		if (patient instanceof Patient) {
			Patient p = (Patient) patient;
			return p.getPersonName() != null ? p.getPersonName().getFullName() : null;
		}
		return null;
	}

	private String patientIdentifier(Object patient) {
		if (patient instanceof Patient) {
			try {
				Object pi = patient.getClass().getMethod("getPatientIdentifier").invoke(patient);
				return safeReflect(pi, "getIdentifier");
			}
			catch (Exception ignored) { }
		}
		return null;
	}

	private String patientGender(Object patient) {
		if (patient instanceof Patient) return ((Patient) patient).getGender();
		return null;
	}

	private String patientBirthdate(Object patient) {
		if (patient instanceof Patient) {
			Date bd = ((Patient) patient).getBirthdate();
			if (bd != null) {
				SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
				return sdf.format(bd);
			}
		}
		return null;
	}

	/** Searches all active PersonAttributes for the first one whose type name contains any of the given keywords. */
	private String patientPersonAttribute(Object patient, String... keywords) {
		if (!(patient instanceof Patient)) return null;
		try {
			Object attrs = patient.getClass().getMethod("getActiveAttributes").invoke(patient);
			if (!(attrs instanceof Iterable)) return null;
			for (Object attr : (Iterable<?>) attrs) {
				Object attrType = attr.getClass().getMethod("getAttributeType").invoke(attr);
				String typeName = safeReflect(attrType, "getName");
				if (typeName == null) continue;
				String lower = typeName.toLowerCase();
				for (String kw : keywords) {
					if (lower.contains(kw)) {
						String val = safeReflect(attr, "getValue");
						if (val != null && !val.isEmpty()) return val;
					}
				}
			}
		}
		catch (Exception ignored) { }
		return null;
	}

	private String patientPhone(Object patient) {
		return patientPersonAttribute(patient, "phone", "telephone", "mobile", "contact");
	}

	private String patientEmail(Object patient) {
		return patientPersonAttribute(patient, "email");
	}

	// ── Webhook dispatch ──────────────────────────────────────────────────────────

	private void dispatchWebhook(Object appt, String eventType, String endpoint) {
		Object patient = extractPatient(appt);
		String nowIso  = formatIso(new Date());

		boolean isDiscord = endpoint.contains("discord.com/api/webhooks");
		String body = isDiscord
		        ? buildDiscordEmbed(appt, patient, eventType, nowIso)
		        : buildJsonPayload(appt, patient, eventType, nowIso);

		postToEndpoint(endpoint, body, safeUuid(appt), eventType);
	}

	// ── Payload builders ──────────────────────────────────────────────────────────

	private String buildJsonPayload(Object appt, Object patient, String event, String timestamp) {
		String apptBlock = "{"
		        + q("id") + ":" + q(apptId(appt)) + ","
		        + q("uuid") + ":" + q(safeUuid(appt)) + ","
		        + q("appointmentNumber") + ":" + q(apptNumber(appt)) + ","
		        + q("status") + ":" + q(apptStatus(appt)) + ","
		        + q("kind") + ":" + q(apptKind(appt)) + ","
		        + q("priority") + ":" + q(apptPriority(appt)) + ","
		        + q("startDateTime") + ":" + q(extractDateIso(appt, "getStartDateTime")) + ","
		        + q("endDateTime") + ":" + q(extractDateIso(appt, "getEndDateTime")) + ","
		        + q("service") + ":" + q(apptServiceName(appt)) + ","
		        + q("serviceUuid") + ":" + q(apptServiceUuid(appt)) + ","
		        + q("serviceType") + ":" + q(apptServiceType(appt)) + ","
		        + q("location") + ":" + q(apptLocation(appt)) + ","
		        + q("comments") + ":" + q(apptComments(appt)) + ","
		        + q("teleHealthVideoLink") + ":" + q(apptVideoLink(appt))
		        + "}";

		String patientBlock = "{"
		        + q("id") + ":" + q(patientId(patient)) + ","
		        + q("uuid") + ":" + q(patientUuid(patient)) + ","
		        + q("name") + ":" + q(patientName(patient)) + ","
		        + q("identifier") + ":" + q(patientIdentifier(patient)) + ","
		        + q("gender") + ":" + q(patientGender(patient)) + ","
		        + q("birthdate") + ":" + q(patientBirthdate(patient)) + ","
		        + q("phone") + ":" + q(patientPhone(patient)) + ","
		        + q("email") + ":" + q(patientEmail(patient))
		        + "}";

		return "{"
		        + q("event") + ":" + q(event) + ","
		        + q("timestamp") + ":" + q(timestamp) + ","
		        + q("appointment") + ":" + apptBlock + ","
		        + q("patient") + ":" + patientBlock
		        + "}";
	}

	private String buildDiscordEmbed(Object appt, Object patient, String event, String nowIso) {
		String icon;
		int color;
		switch (event) {
			case "CREATED":   icon = "🟢"; color = COLOR_CREATED;   break;
			case "CANCELLED": icon = "🔴"; color = COLOR_CANCELLED; break;
			case "DELETED":   icon = "⚫";        color = COLOR_DELETED;   break;
			default:          icon = "🟡"; color = COLOR_UPDATED;   break;
		}

		String name        = or(patientName(patient),       "Unknown");
		String identifier  = or(patientIdentifier(patient), "-");
		String phone       = patientPhone(patient);
		String email       = patientEmail(patient);
		String service     = or(apptServiceName(appt),      "Unknown");
		String serviceType = apptServiceType(appt);
		String status      = or(apptStatus(appt),           "Unknown");
		String kind        = apptKind(appt);
		String location    = apptLocation(appt);
		String startDt     = or(extractDateIso(appt, "getStartDateTime"), "Unknown");
		String endDt       = or(extractDateIso(appt, "getEndDateTime"),   "Unknown");
		String apptUuid    = or(safeUuid(appt),             "Unknown");
		String apptNum     = apptNumber(appt);
		String patUuid     = patientUuid(patient);
		String patId       = patientId(patient);
		String comments    = apptComments(appt);
		String videoLink   = apptVideoLink(appt);

		StringBuilder fields = new StringBuilder();
		fields.append(field("Patient", name, true));
		fields.append(",").append(field("Status", status, true));
		if (phone != null) fields.append(",").append(field("Phone", phone, true));
		if (email != null) fields.append(",").append(field("Email", email, true));
		fields.append(",").append(field("Service",
		        service + (serviceType != null ? " / " + serviceType : ""), true));
		if (location != null) fields.append(",").append(field("Location", location, true));
		if (kind != null)     fields.append(",").append(field("Kind", kind, true));
		fields.append(",").append(field("Start", formatReadable(extractDateIso(appt, "getStartDateTime")), true));
		fields.append(",").append(field("End",   formatReadable(extractDateIso(appt, "getEndDateTime")),   true));
		if (comments != null && !comments.isEmpty())
			fields.append(",").append(field("Comments", comments, false));
		if (videoLink != null && !videoLink.isEmpty())
			fields.append(",").append(field("Video link", videoLink, false));
		fields.append(",").append(field("Appointment UUID", apptUuid, false));
		if (patUuid != null) fields.append(",").append(field("Patient UUID", patUuid, false));

		return "{\"embeds\":[{"
		        + q("title") + ":" + q(icon + " Appointment " + event) + ","
		        + "\"color\":" + color + ","
		        + "\"fields\":[" + fields + "],"
		        + "\"footer\":{\"text\":\"OpenMRS Appointment Notifier\"},"
		        + q("timestamp") + ":" + q(nowIso)
		        + "}]}";
	}

	private String field(String name, String value, boolean inline) {
		return "{" + q("name") + ":" + q(name) + ","
		        + q("value") + ":" + q(value) + ","
		        + "\"inline\":" + inline + "}";
	}

	// ── HTTP POST ─────────────────────────────────────────────────────────────────

	private void postToEndpoint(String endpoint, String body, String uuid, String eventType) {
		HttpURLConnection conn = null;
		try {
			conn = (HttpURLConnection) new URL(endpoint).openConnection();
			conn.setRequestMethod("POST");
			conn.setDoOutput(true);
			conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
			conn.setReadTimeout(READ_TIMEOUT_MS);
			conn.setRequestProperty("Content-Type", "application/json");

			byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
			conn.setRequestProperty("Content-Length", String.valueOf(bytes.length));

			try (OutputStream os = conn.getOutputStream()) {
				os.write(bytes);
			}

			int httpStatus = conn.getResponseCode();
			if (httpStatus >= 200 && httpStatus < 300) {
				log.info("Appointment [" + eventType + "] " + uuid + " -> webhook OK (HTTP " + httpStatus + ")");
			} else {
				log.warn("Webhook returned HTTP " + httpStatus
				        + " for appointment [" + eventType + "] uuid=" + uuid);
			}
		}
		catch (IOException e) {
			log.error("Webhook POST failed for appointment [" + eventType + "] uuid=" + uuid
			        + " endpoint=" + endpoint + ": " + e.getMessage());
		}
		finally {
			if (conn != null) conn.disconnect();
		}
	}

	// ── Utilities ─────────────────────────────────────────────────────────────────

	private static String formatIso(Date date) {
		SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
		sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
		return sdf.format(date);
	}

	/** Converts an ISO string like "2026-05-16T11:30:00Z" to "16 May 2026, 11:30 UTC". */
	private static String formatReadable(String iso) {
		if (iso == null) return "Unknown";
		try {
			SimpleDateFormat in  = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
			in.setTimeZone(TimeZone.getTimeZone("UTC"));
			SimpleDateFormat out = new SimpleDateFormat("d MMM yyyy, HH:mm 'UTC'", java.util.Locale.ENGLISH);
			out.setTimeZone(TimeZone.getTimeZone("UTC"));
			return out.format(in.parse(iso));
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
		return value != null ? value : fallback;
	}

	public void shutdown() {
		executor.shutdown();
		try {
			if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
				executor.shutdownNow();
			}
		}
		catch (InterruptedException e) {
			executor.shutdownNow();
			Thread.currentThread().interrupt();
		}
	}
}
