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
import java.util.List;

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

@Resource(name = RestConstants.VERSION_1 + "/appointmentnotifier", supportedClass = AppointmentInfo.class, supportedOpenmrsVersions = { "2.0.* - 9.*" })
public class AppointmentNotifierResource extends DelegatingCrudResource<AppointmentInfo> {
	
	private static final String OPENMRS_BASE_URL = "http://localhost:8080/openmrs";
	
	private static final String AUTH_HEADER = System.getenv("AUTH_HEADER");
	
	private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneOffset.UTC);
	
	private final RestTemplate restTemplate = new RestTemplate();
	
	private final ObjectMapper objectMapper = new ObjectMapper();
	
	@Override
	public DelegatingResourceDescription getRepresentationDescription(Representation rep) {
		DelegatingResourceDescription description = new DelegatingResourceDescription();
		description.addProperty("uuid");
		description.addProperty("appointmentUuid");
		description.addProperty("patientUuid");
		description.addProperty("patientName");
		description.addProperty("startDateTime");
		description.addProperty("endDateTime");
		description.addProperty("serviceName");
		description.addProperty("status");
		description.addProperty("phoneNumber");
		description.addProperty("email");
		return description;
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
	
	private List<AppointmentInfo> fetchUpcomingScheduled() throws Exception {
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.AUTHORIZATION, AUTH_HEADER);
		headers.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
		HttpEntity<String> request = new HttpEntity<String>(headers);
		
		ResponseEntity<String> response = restTemplate.exchange(OPENMRS_BASE_URL + "/ws/rest/v1/appointments",
		    HttpMethod.GET, request, String.class);
		
		JsonNode root = objectMapper.readTree(response.getBody());
		
		List<AppointmentInfo> results = new ArrayList<AppointmentInfo>();
		long now = System.currentTimeMillis();
		
		if (root != null && root.isArray()) {
			for (JsonNode appt : root) {
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
				info.setPatientName(textOrNull(patient.path("name")));
				
				if (patientUuid != null) {
					enrichWithPatientContact(info, patientUuid);
					if (info.getPhoneNumber() == null || info.getEmail() == null) {
						enrichWithPersonAttributes(info, patientUuid);
					}
				}
				
				results.add(info);
			}
		}
		return results;
	}
	
	private void enrichWithPatientContact(AppointmentInfo info, String patientUuid) {
		try {
			HttpHeaders headers = new HttpHeaders();
			headers.set(HttpHeaders.AUTHORIZATION, AUTH_HEADER);
			headers.set(HttpHeaders.ACCEPT, "application/fhir+json");
			HttpEntity<String> request = new HttpEntity<String>(headers);
			
			ResponseEntity<String> response = restTemplate.exchange(
			    OPENMRS_BASE_URL + "/ws/fhir2/R4/Patient/" + patientUuid, HttpMethod.GET, request, String.class);
			
			JsonNode fhir = objectMapper.readTree(response.getBody());
			JsonNode telecoms = fhir.path("telecom");
			if (telecoms.isArray()) {
				for (JsonNode tel : telecoms) {
					String system = tel.path("system").asText("");
					String value = textOrNull(tel.path("value"));
					if (value == null) {
						continue;
					}
					if ("phone".equalsIgnoreCase(system) && info.getPhoneNumber() == null) {
						info.setPhoneNumber(value);
					} else if ("email".equalsIgnoreCase(system) && info.getEmail() == null) {
						info.setEmail(value);
					}
				}
			}
		}
		catch (Exception ignored) {
			// FHIR lookup failed — leave phoneNumber/email null, fallback may fill them
		}
	}
	
	private void enrichWithPersonAttributes(AppointmentInfo info, String patientUuid) {
		try {
			HttpHeaders headers = new HttpHeaders();
			headers.set(HttpHeaders.AUTHORIZATION, AUTH_HEADER);
			headers.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
			HttpEntity<String> request = new HttpEntity<String>(headers);
			
			ResponseEntity<String> response = restTemplate.exchange(OPENMRS_BASE_URL + "/ws/rest/v1/patient/" + patientUuid
			        + "?v=full", HttpMethod.GET, request, String.class);
			
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
		catch (Exception ignored) {
			// Person attribute lookup failed — leave fields as-is
		}
	}
	
	private static String formatEpochMillis(long epochMillis) {
		if (epochMillis <= 0) {
			return null;
		}
		return ISO_FORMATTER.format(Instant.ofEpochMilli(epochMillis));
	}
	
	private static String textOrNull(JsonNode node) {
		if (node == null || node.isNull() || node.isMissingNode()) {
			return null;
		}
		String value = node.asText(null);
		return (value == null || value.isEmpty()) ? null : value;
	}
	
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
