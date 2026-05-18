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
import org.openmrs.module.appointmentnotifier.outbox.OutboxService;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;

public class AppointmentServiceAdvice implements MethodInterceptor {
	
	private static final Log log = LogFactory.getLog(AppointmentServiceAdvice.class);
	
	/** Referenced by AppointmentNotifierResource for global property keys. */
	public static final String GP_SAAS_ENDPOINT = AppointmentNotifierConstants.GP_SAAS_ENDPOINT;
	
	public static final String GP_OPENMRS_URL = AppointmentNotifierConstants.GP_OPENMRS_URL;
	
	public static final String GP_USERNAME = AppointmentNotifierConstants.GP_USERNAME;
	
	public static final String GP_PASSWORD = AppointmentNotifierConstants.GP_PASSWORD;
	
	private static AppointmentServiceAdvice instance;
	
	public AppointmentServiceAdvice() {
		instance = this;
	}
	
	public static AppointmentServiceAdvice getInstance() {
		return instance;
	}
	
	// ── AOP intercept ─────────────────────────────────────────────────────────
	
	@Override
	public Object invoke(MethodInvocation invocation) throws Throwable {
		String method = invocation.getMethod().getName();
		
		if (!isTrackedMethod(method)) {
			return invocation.proceed();
		}
		
		String enabled = Context.getAdministrationService().getGlobalProperty("appointmentnotifier.enabled", "true");
		if (!"true".equalsIgnoreCase(enabled.trim())) {
			return invocation.proceed();
		}
		
		Object result = invocation.proceed();
		
		Object appointment = resolveAppointment(result, invocation.getArguments());
		if (appointment == null) {
			log.debug("AppointmentServiceAdvice: no Appointment object found for method=" + method);
			return result;
		}
		
		String eventType = classifyEvent(appointment);
		String uuid = safeUuid(appointment);
		
		try {
			Object patient = extractPatient(appointment);
			String payload = buildJsonPayload(appointment, patient, eventType);
			
			OutboxService outboxService = Context.getRegisteredComponent("appointmentNotifierOutboxService",
			    OutboxService.class);
			outboxService.enqueue(uuid, payload);
			
			log.debug("AppointmentServiceAdvice: enqueued appointment " + uuid + " event=" + eventType);
		}
		catch (Exception e) {
			log.error("AppointmentServiceAdvice: failed to enqueue appointment " + uuid + " event=" + eventType, e);
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
	
	private String classifyEvent(Object appt) {
		String status = apptStatus(appt);
		if ("Cancelled".equalsIgnoreCase(status))
			return "CANCELLED";
		return "SCHEDULED";
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
	
	// ── Payload builder ────────────────────────────────────────────────────────
	
	private String buildJsonPayload(Object appt, Object patient, String event) {
		return "{" + q("event") + ":" + q(event) + "," + q("appointmentUuid") + ":" + q(safeUuid(appt)) + ","
		        + q("patientUuid") + ":" + q(patientUuid(patient)) + "," + q("patientName") + ":" + q(patientName(patient))
		        + "," + q("artsName") + ":" + q(apptProviderName(appt)) + "," + q("status") + ":" + q(apptStatus(appt))
		        + "," + q("phoneNumber") + ":" + q(patientPhone(patient)) + "," + q("service") + ":"
		        + q(apptServiceName(appt)) + "," + q("location") + ":" + q(apptLocation(appt)) + "," + q("startDateTime")
		        + ":" + q(extractDateIso(appt, "getStartDateTime")) + "," + q("endDateTime") + ":"
		        + q(extractDateIso(appt, "getEndDateTime")) + "," + q("comments") + ":" + q(apptComments(appt)) + "}";
	}
	
	// ── Generic reflection helpers ─────────────────────────────────────────────
	
	private String safeUuid(Object obj) {
		if (obj instanceof OpenmrsObject)
			return ((OpenmrsObject) obj).getUuid();
		return null;
	}
	
	private String safeReflect(Object obj, String getter) {
		if (obj == null)
			return null;
		try {
			Object val = obj.getClass().getMethod(getter).invoke(obj);
			return val != null ? val.toString() : null;
		}
		catch (Exception ignored) {
			return null;
		}
	}
	
	private String extractDateIso(Object obj, String getter) {
		try {
			Object val = obj.getClass().getMethod(getter).invoke(obj);
			if (val instanceof Date)
				return formatIso((Date) val);
		}
		catch (Exception ignored) {}
		return null;
	}
	
	// ── Appointment field extractors ───────────────────────────────────────────
	
	private Object extractPatient(Object appt) {
		try {
			return appt.getClass().getMethod("getPatient").invoke(appt);
		}
		catch (Exception ignored) {
			return null;
		}
	}
	
	private String apptStatus(Object appt) {
		return safeReflect(appt, "getStatus");
	}
	
	private String apptComments(Object appt) {
		return safeReflect(appt, "getComments");
	}
	
	private String apptServiceName(Object appt) {
		try {
			return safeReflect(appt.getClass().getMethod("getService").invoke(appt), "getName");
		}
		catch (Exception ignored) {
			return null;
		}
	}
	
	private String apptLocation(Object appt) {
		try {
			return safeReflect(appt.getClass().getMethod("getLocation").invoke(appt), "getName");
		}
		catch (Exception ignored) {
			return null;
		}
	}
	
	private String apptProviderName(Object appt) {
		try {
			Object providers = appt.getClass().getMethod("getProviders").invoke(appt);
			if (!(providers instanceof Iterable))
				return null;
			for (Object ap : (Iterable<?>) providers) {
				Object provider = ap.getClass().getMethod("getProvider").invoke(ap);
				if (provider == null)
					continue;
				try {
					Object name = provider.getClass().getMethod("getName").invoke(provider);
					if (name != null && !name.toString().isEmpty())
						return name.toString();
				}
				catch (Exception ignored) {}
				try {
					Object person = provider.getClass().getMethod("getPerson").invoke(provider);
					if (person != null) {
						Object personName = person.getClass().getMethod("getPersonName").invoke(person);
						if (personName != null) {
							Object fullName = personName.getClass().getMethod("getFullName").invoke(personName);
							if (fullName != null && !fullName.toString().isEmpty())
								return fullName.toString();
						}
					}
				}
				catch (Exception ignored) {}
			}
		}
		catch (Exception ignored) {}
		return null;
	}
	
	// ── Patient field extractors ───────────────────────────────────────────────
	
	private String patientUuid(Object patient) {
		if (patient instanceof OpenmrsObject)
			return ((OpenmrsObject) patient).getUuid();
		return null;
	}
	
	private String patientName(Object patient) {
		if (patient instanceof Patient) {
			Patient p = (Patient) patient;
			return p.getPersonName() != null ? p.getPersonName().getFullName() : null;
		}
		return null;
	}
	
	private String patientPersonAttribute(Object patient, String... keywords) {
		if (!(patient instanceof Patient))
			return null;
		try {
			Object attrs = patient.getClass().getMethod("getActiveAttributes").invoke(patient);
			if (!(attrs instanceof Iterable))
				return null;
			for (Object attr : (Iterable<?>) attrs) {
				Object attrType = attr.getClass().getMethod("getAttributeType").invoke(attr);
				String typeName = safeReflect(attrType, "getName");
				if (typeName == null)
					continue;
				String lower = typeName.toLowerCase();
				for (String kw : keywords) {
					if (lower.contains(kw)) {
						String val = safeReflect(attr, "getValue");
						if (val != null && !val.isEmpty())
							return val;
					}
				}
			}
		}
		catch (Exception ignored) {}
		return null;
	}
	
	private String patientPhone(Object patient) {
		return patientPersonAttribute(patient, "phone", "telephone", "mobile", "contact");
	}
	
	// ── Utilities ──────────────────────────────────────────────────────────────
	
	private static String formatIso(Date date) {
		SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
		sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
		return sdf.format(date);
	}
	
	private static String q(String value) {
		if (value == null)
			return "null";
		return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\"";
	}
	
	private static String or(String value, String fallback) {
		return value != null ? value : fallback;
	}
}
