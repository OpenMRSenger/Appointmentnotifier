/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.appointmentnotifier.fhir;

import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.TimeZone;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.Encounter;
import org.openmrs.Patient;
import org.openmrs.api.context.ServiceContext;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

/**
 * Converts an OpenMRS {@link Encounter} to a FHIR R4 JSON string. <h3>Two-tier strategy</h3>
 * <ol>
 * <li><strong>fhir2 path</strong> — if the {@code fhir2} module is loaded, all fhir2 and HAPI FHIR
 * classes are resolved at runtime via reflection. No compile-time dependency on {@code fhir2-api}
 * or any HAPI JAR is required; this is essential because fhir2 is declared {@code aware_of_module}
 * (not {@code require_module}).</li>
 * <li><strong>Fallback path</strong> — a hand-built JSON structure that carries the same
 * semantically useful fields (datetime, location, patient contact) needed by the SaaS.</li>
 * </ol>
 * <p>
 * The produced JSON string is stored verbatim in {@code saas_integration_queue.fhir_payload} and
 * POSTed by {@link org.openmrs.module.appointmentnotifier.task.SaasQueueTask}.
 */
@Component("fhirEncounterSerializer")
public class FhirEncounterSerializer {
	
	private static final Log log = LogFactory.getLog(FhirEncounterSerializer.class);
	
	private static final String ENCOUNTER_TRANSLATOR_CLASS = "org.openmrs.module.fhir2.api.translators.EncounterTranslator";
	
	private static final String FHIR_CONTEXT_CLASS = "ca.uhn.fhir.context.FhirContext";
	
	/** Cached HAPI FhirContext object — expensive to create, reused across calls. */
	private static volatile Object fhirContextCache;
	
	// ── Public API ────────────────────────────────────────────────────────────
	
	/**
	 * Serializes the encounter to FHIR R4 JSON. Never throws; falls back to minimal JSON on any
	 * error.
	 */
	public String toFhirJson(Encounter encounter) {
		try {
			return tryFhirConversionViaReflection(encounter);
		}
		catch (ClassNotFoundException e) {
			log.debug("fhir2 module not on classpath, using fallback JSON for encounter " + encounter.getUuid());
		}
		catch (Exception e) {
			log.warn("fhir2 serialization failed for encounter " + encounter.getUuid() + ": " + e.getMessage()
			        + " — using fallback JSON.");
		}
		return buildFallbackJson(encounter);
	}
	
	// ── fhir2 reflection path ─────────────────────────────────────────────────
	
	/**
	 * Loads fhir2 and HAPI classes dynamically so that this module compiles and deploys even when
	 * fhir2 is absent.
	 */
	@SuppressWarnings({ "unchecked", "rawtypes" })
	private String tryFhirConversionViaReflection(Encounter encounter) throws Exception {
		
		ClassLoader cl = Thread.currentThread().getContextClassLoader();
		
		// 1. Locate EncounterTranslator — ClassNotFoundException if fhir2 not loaded
		Class<?> translatorInterface = cl.loadClass(ENCOUNTER_TRANSLATOR_CLASS);
		
		// 2. Get all EncounterTranslator beans from the shared Spring context
		ApplicationContext appCtx = ServiceContext.getInstance().getApplicationContext();
		Map<String, ?> beans = appCtx.getBeansOfType((Class) translatorInterface);
		if (beans.isEmpty()) {
			throw new IllegalStateException("No EncounterTranslator bean found — "
			        + "fhir2 may be installed but not fully started.");
		}
		
		Object translator = beans.values().iterator().next();
		
		// 3. Call translator.toFhirResource(encounter) — find the method reflectively
		//    because the concrete return type is generic (org.hl7.fhir.r4.model.Encounter)
		Method toFhirResource = findMethod(translator.getClass(), "toFhirResource");
		if (toFhirResource == null) {
			throw new NoSuchMethodException("toFhirResource not found on " + translator.getClass().getName());
		}
		Object fhirEncounter = toFhirResource.invoke(translator, encounter);
		
		// 4. Serialize with HAPI FhirContext
		Object ctx = getOrCreateFhirContext(cl);
		
		// ctx.newJsonParser()
		Object parser = ctx.getClass().getMethod("newJsonParser").invoke(ctx);
		
		// parser.setPrettyPrint(false)  — method returns IParser (same instance or new)
		Method setPP = findMethod(parser.getClass(), "setPrettyPrint");
		if (setPP != null) {
			Object result = setPP.invoke(parser, false);
			// setPrettyPrint returns IParser (fluent); use that if non-null
			if (result != null && result != parser) {
				parser = result;
			}
		}
		
		// parser.encodeResourceToString(fhirEncounter)
		Method encode = findMethod(parser.getClass(), "encodeResourceToString");
		if (encode == null) {
			throw new NoSuchMethodException("encodeResourceToString not found on parser.");
		}
		return (String) encode.invoke(parser, fhirEncounter);
	}
	
	/** Lazy double-checked singleton for the HAPI FhirContext. */
	private static Object getOrCreateFhirContext(ClassLoader cl) throws Exception {
		if (fhirContextCache == null) {
			synchronized (FhirEncounterSerializer.class) {
				if (fhirContextCache == null) {
					Class<?> fhirContextClass = cl.loadClass(FHIR_CONTEXT_CLASS);
					// FhirContext.forR4() — static factory
					fhirContextCache = fhirContextClass.getMethod("forR4").invoke(null);
				}
			}
		}
		return fhirContextCache;
	}
	
	/**
	 * Searches the entire class hierarchy (incl. interfaces) for a method with the given name and
	 * exactly one parameter. Returns null if not found.
	 */
	private static Method findMethod(Class<?> clazz, String name) {
		for (Method m : clazz.getMethods()) {
			if (m.getName().equals(name) && m.getParameterCount() == 1) {
				return m;
			}
		}
		return null;
	}
	
	// ── Fallback JSON path ────────────────────────────────────────────────────
	
	/**
	 * Builds a minimal FHIR-shaped JSON when fhir2 is not available. Includes a non-standard
	 * {@code _patientContact} block so the SaaS and
	 * {@link org.openmrs.module.appointmentnotifier.client.DiscordPayloadBuilder} can extract
	 * phone/email without a second REST call.
	 */
	private String buildFallbackJson(Encounter encounter) {
		Patient patient = encounter.getPatient();
		
		String patientRef = patient != null ? "Patient/" + patient.getUuid() : "Patient/unknown";
		String patientDisplay = patient != null && patient.getPersonName() != null ? patient.getPersonName().getFullName()
		        : "Unknown";
		String phone = resolvePhone(patient);
		String email = resolveEmail(patient);
		String locationName = encounter.getLocation() != null ? encounter.getLocation().getName() : null;
		String encounterDate = encounter.getEncounterDatetime() != null ? formatIso(encounter.getEncounterDatetime()) : null;
		
		StringBuilder sb = new StringBuilder();
		sb.append("{");
		sb.append(q("resourceType")).append(":").append(q("Encounter")).append(",");
		sb.append(q("id")).append(":").append(q(encounter.getUuid())).append(",");
		sb.append(q("status")).append(":").append(q("unknown")).append(",");
		
		// subject
		sb.append(q("subject")).append(":{");
		sb.append(q("reference")).append(":").append(q(patientRef)).append(",");
		sb.append(q("display")).append(":").append(q(patientDisplay));
		sb.append("},");
		
		// period
		sb.append(q("period")).append(":{");
		sb.append(q("start")).append(":").append(q(encounterDate));
		sb.append("},");
		
		// location
		if (locationName != null) {
			sb.append(q("location")).append(":[{");
			sb.append(q("location")).append(":{");
			sb.append(q("display")).append(":").append(q(locationName));
			sb.append("}}],");
		}
		
		// Non-standard contact block for Discord / SaaS phone lookup
		sb.append(q("_patientContact")).append(":{");
		sb.append(q("phone")).append(":").append(q(phone)).append(",");
		sb.append(q("email")).append(":").append(q(email));
		sb.append("}");
		
		sb.append("}");
		return sb.toString();
	}
	
	// ── Patient contact resolution ────────────────────────────────────────────
	
	private String resolvePhone(Patient patient) {
		return resolvePersonAttribute(patient, "phone", "telephone", "mobile", "contact");
	}
	
	private String resolveEmail(Patient patient) {
		return resolvePersonAttribute(patient, "email");
	}
	
	private String resolvePersonAttribute(Patient patient, String... keywords) {
		if (patient == null)
			return null;
		try {
			for (Object attr : patient.getActiveAttributes()) {
				Object attrType = attr.getClass().getMethod("getAttributeType").invoke(attr);
				String typeName = (String) attrType.getClass().getMethod("getName").invoke(attrType);
				if (typeName == null)
					continue;
				String lower = typeName.toLowerCase();
				for (String kw : keywords) {
					if (lower.contains(kw)) {
						Object val = attr.getClass().getMethod("getValue").invoke(attr);
						if (val != null && !val.toString().isEmpty())
							return val.toString();
					}
				}
			}
		}
		catch (Exception ignored) {}
		return null;
	}
	
	// ── Utilities ─────────────────────────────────────────────────────────────
	
	private static String formatIso(Date date) {
		SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
		sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
		return sdf.format(date);
	}
	
	/** JSON-safe string literal; returns JSON {@code null} for null input. */
	private static String q(String value) {
		if (value == null)
			return "null";
		return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\"";
	}
}
