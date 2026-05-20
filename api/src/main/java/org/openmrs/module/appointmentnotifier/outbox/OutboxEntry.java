/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.appointmentnotifier.outbox;

import java.util.Date;

/**
 * Represents one row in <code>saas_integration_queue</code>. Plain POJO — mapped via JdbcTemplate,
 * no Hibernate.
 */
public class OutboxEntry {
	
	/** Database primary key. */
	private int id;
	
	/** UUID of the OpenMRS Encounter that triggered this entry. */
	private String encounterUuid;
	
	/**
	 * FHIR R4 JSON payload (Bundle or bare Encounter) that will be POSTed to the SaaS endpoint. Can
	 * be null when the FHIR serializer is unavailable.
	 */
	private String fhirPayload;
	
	/** Current lifecycle status: PENDING, SENT, or FAILED. */
	private String status;
	
	/** Number of failed dispatch attempts so far. */
	private int retryCount;
	
	private Date createdAt;
	
	private Date updatedAt;
	
	// ── Getters & setters ─────────────────────────────────────────────────────
	
	public int getId() {
		return id;
	}
	
	public void setId(int id) {
		this.id = id;
	}
	
	public String getEncounterUuid() {
		return encounterUuid;
	}
	
	public void setEncounterUuid(String encounterUuid) {
		this.encounterUuid = encounterUuid;
	}
	
	public String getFhirPayload() {
		return fhirPayload;
	}
	
	public void setFhirPayload(String fhirPayload) {
		this.fhirPayload = fhirPayload;
	}
	
	public String getStatus() {
		return status;
	}
	
	public void setStatus(String status) {
		this.status = status;
	}
	
	public int getRetryCount() {
		return retryCount;
	}
	
	public void setRetryCount(int retryCount) {
		this.retryCount = retryCount;
	}
	
	public Date getCreatedAt() {
		return createdAt;
	}
	
	public void setCreatedAt(Date createdAt) {
		this.createdAt = createdAt;
	}
	
	public Date getUpdatedAt() {
		return updatedAt;
	}
	
	public void setUpdatedAt(Date updatedAt) {
		this.updatedAt = updatedAt;
	}
	
	@Override
	public String toString() {
		return "OutboxEntry{id=" + id + ", encounterUuid='" + encounterUuid + "', status='" + status + "', retryCount="
		        + retryCount + "}";
	}
}
