/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.appointmentnotifier.web.rest;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.api.context.Context;
import org.openmrs.module.appointmentnotifier.AppointmentServiceAdvice;
import org.openmrs.module.appointmentnotifier.api.AppointmentInfo;
import org.openmrs.module.webservices.rest.web.RequestContext;
import org.openmrs.module.webservices.rest.web.RestConstants;
import org.openmrs.module.webservices.rest.web.annotation.Resource;
import org.openmrs.module.webservices.rest.web.representation.Representation;
import org.openmrs.module.webservices.rest.web.resource.impl.DelegatingCrudResource;
import org.openmrs.module.webservices.rest.web.resource.impl.DelegatingResourceDescription;
import org.openmrs.module.webservices.rest.web.resource.impl.NeedsPaging;
import org.openmrs.module.webservices.rest.web.response.ResourceDoesNotSupportOperationException;
import org.openmrs.module.webservices.rest.web.response.ResponseException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Read-only REST resource that lists upcoming Scheduled appointments, enriched with patient contact
 * information. Endpoint: GET /ws/rest/v1/appointmentnotifier Uses the same global properties as
 * AppointmentServiceAdvice for base URL and credentials — no hardcoded values.
 */
@Resource(name = RestConstants.VERSION_1 + "/appointmentnotifier", supportedClass = AppointmentInfo.class, supportedOpenmrsVersions = { "2.0.* - 9.*" })
public class AppointmentNotifierResource extends DelegatingCrudResource<AppointmentInfo> {
	
	private static final Log log = LogFactory.getLog(AppointmentNotifierResource.class);
	
	// Bahmni appointments REST endpoint (confirmed valid)
	private static final String APPOINTMENTS_PATH = "/ws/rest/v1/appointment/all";
	
	private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneOffset.UTC);
	
	private final RestTemplate restTemplate = new RestTemplate();
	
	private final ObjectMapper objectMapper = new ObjectMapper();
	
	// ── Helpers to read global properties ────────────────────────────────────────
	
	private String openmrsBaseUrl() {
		return Context.getAdministrationService().getGlobalProperty(AppointmentServiceAdvice.GP_OPENMRS_URL,
		    "http://localhost:8080/openmrs");
	}
	
	private String basicAuthHeader() {
		String username = Context.getAdministrationService()
		        .getGlobalProperty(AppointmentServiceAdvice.GP_USERNAME);
		String password = Context.getAdministrationService().getGlobalProperty(AppointmentServiceAdvice.GP_PASSWORD);
		String credentials = username + ":" + password;
		return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(java.nio.charset.StandardCharsets.UTF_8));
	}
	
	// ── REST resource implementation ─────────────────────────────────────────────
	
	@Override
	public DelegatingResourceDescription getRepresentationDescription(Representation rep) {
		DelegatingResourceDescription desc = new DelegatingResourceDescription();
		desc.addProperty("uuid");
		desc.addProperty("appointmentUuid");
		desc.addProperty("patientUuid");
		desc.addProperty("patientName");
		desc.addProperty("startDateTime");
		desc.addProperty("endDateTime");
		desc.addProperty("serviceName");
		desc.addProperty("status");
		desc.addProperty("phoneNumber");
		desc.addProperty("email");
		return desc;
	}
	
	@Override
	protected NeedsPaging<AppointmentInfo> doGetAll(RequestContext context) throws ResponseException {
		try {
			return new NeedsPaging<AppointmentInfo>(fetchUpcomingScheduled(), context);
		}
		catch (Exception e) {
			throw new RuntimeException("Failed to fetch appointments: " + e.getMessage(), e);
		}
	}
	
	// ── Data fetching ─────────────────────────────────────────────────────────────
	
	private List<AppointmentInfo> fetchUpcomingScheduled() throws Exception {
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.AUTHORIZATION, basicAuthHeader());
		headers.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
		HttpEntity<String> request = new HttpEntity<String>(headers);
		
		String url = openmrsBaseUrl() + APPOINTMENTS_PATH;
		ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, request, String.class);
		
		JsonNode root = objectMapper.readTree(response.getBody());
		
		List<AppointmentInfo> results = new ArrayList<AppointmentInfo>();
		long now = System.currentTimeMillis();
		
		// The Bahmni /appointment/all endpoint returns a flat array
		JsonNode array = root.isArray() ? root : root.path("results");
		
		if (array.isArray()) {
			for (JsonNode appt : array) {
				String status = appt.path("status").asText("");
				if (!"Scheduled".equalsIgnoreCase(status)) {
					continue;
				}
				
				long startDateTime = appt.path("startDateTime").asLong(0L);
				if (startDateTime <= now) {
					continue;
				}
				
				AppointmentInfo info = new AppointmentInfo();
				String uuid = appt.path("uuid").asText(null);
				info.setUuid(uuid);
				info.setAppointmentUuid(uuid);
				info.setStatus(status);
				info.setStartDateTime(formatEpochMillis(startDateTime));
				info.setEndDateTime(formatEpochMillis(appt.path("endDateTime").asLong(0L)));
				
				JsonNode service = appt.path("service");
				info.setServiceName(textOrNull(service.path("name")));
				
				JsonNode patient = appt.path("patient");
				String patientUuid = textOrNull(patient.path("uuid"));
				info.setPatientUuid(patientUuid);
				// Bahmni returns display name under "name" inside the patient node
				info.setPatientName(textOrNull(patient.path("name")));
				
				if (patientUuid != null) {
					enrichWithPersonAttributes(info, patientUuid);
				}
				
				results.add(info);
			}
		}
		
		return results;
	}
	
	/**
	 * Looks up phone number and email via the standard OpenMRS REST person-attributes endpoint.
	 */
	private void enrichWithPersonAttributes(AppointmentInfo info, String patientUuid) {
		try {
			HttpHeaders headers = new HttpHeaders();
			headers.set(HttpHeaders.AUTHORIZATION, basicAuthHeader());
			headers.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
			HttpEntity<String> request = new HttpEntity<String>(headers);
			
			String url = openmrsBaseUrl() + "/ws/rest/v1/patient/" + patientUuid + "?v=full";
			ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, request, String.class);
			
			JsonNode attributes = objectMapper.readTree(response.getBody()).path("person").path("attributes");
			if (!attributes.isArray()) {
				return;
			}
			for (JsonNode attr : attributes) {
				String typeName = attr.path("attributeType").path("display").asText("");
				String value = textOrNull(attr.path("value"));
				if (value == null) {
					continue;
				}
				if (info.getPhoneNumber() == null && "Telephone Number".equalsIgnoreCase(typeName)) {
					info.setPhoneNumber(value);
				} else if (info.getEmail() == null && "email".equalsIgnoreCase(typeName)) {
					info.setEmail(value);
				}
			}
		}
		catch (Exception e) {
			log.debug("Could not enrich patient attributes for " + patientUuid + ": " + e.getMessage());
		}
	}
	
	// ── Utilities ─────────────────────────────────────────────────────────────────
	
	private static String formatEpochMillis(long epochMillis) {
		if (epochMillis <= 0)
			return null;
		return ISO_FORMATTER.format(Instant.ofEpochMilli(epochMillis));
	}
	
	private static String textOrNull(JsonNode node) {
		if (node == null || node.isNull() || node.isMissingNode())
			return null;
		String value = node.asText(null);
		return (value == null || value.isEmpty()) ? null : value;
	}
	
	// ── Unsupported mutation operations ──────────────────────────────────────────
	
	@Override
	public AppointmentInfo getByUniqueId(String uuid) {
		return null;
	}
	
	@Override
	public AppointmentInfo newDelegate() {
		return new AppointmentInfo();
	}
	
	@Override
	public AppointmentInfo save(AppointmentInfo delegate) {
		throw new ResourceDoesNotSupportOperationException();
	}
	
	@Override
	protected void delete(AppointmentInfo delegate, String reason, RequestContext context) throws ResponseException {
		throw new ResourceDoesNotSupportOperationException();
	}
	
	@Override
	public void purge(AppointmentInfo delegate, RequestContext context) throws ResponseException {
		throw new ResourceDoesNotSupportOperationException();
	}
}
