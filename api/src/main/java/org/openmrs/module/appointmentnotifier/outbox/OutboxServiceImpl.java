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

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.hibernate.SessionFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Hibernate-backed implementation of {@link OutboxService}. Uses native SQL via the shared OpenMRS
 * {@link SessionFactory} — no extra Hibernate mapping is needed for this simple queue table.
 */
@Service("appointmentNotifierOutboxService")
public class OutboxServiceImpl implements OutboxService {
	
	private static final Log log = LogFactory.getLog(OutboxServiceImpl.class);
	
	private static final String INSERT_SQL = "INSERT INTO saas_integration_queue "
	        + "(encounter_uuid, fhir_payload, status, retry_count, created_at, updated_at) "
	        + "VALUES (:encounterUuid, :fhirPayload, 'PENDING', 0, NOW(), NOW())";
	
	private static final String SELECT_SQL = "SELECT id, encounter_uuid, fhir_payload, status, retry_count, created_at, updated_at "
	        + "FROM saas_integration_queue "
	        + "WHERE status IN ('PENDING','FAILED') AND retry_count < :maxRetries "
	        + "ORDER BY id ASC LIMIT 100";
	
	private static final String MARK_SENT_SQL = "UPDATE saas_integration_queue SET status = 'SENT', updated_at = NOW() WHERE id = :id";
	
	private static final String MARK_FAILED_SQL = "UPDATE saas_integration_queue SET status = 'FAILED', retry_count = retry_count + 1, updated_at = NOW() WHERE id = :id";
	
	@Autowired
	private SessionFactory sessionFactory;
	
	// ── OutboxService ─────────────────────────────────────────────────────────
	
	@Override
	@Transactional
	public void enqueue(String encounterUuid, String fhirPayload) {
		sessionFactory.getCurrentSession().createNativeQuery(INSERT_SQL).setParameter("encounterUuid", encounterUuid)
		        .setParameter("fhirPayload", fhirPayload).executeUpdate();
		log.debug("Enqueued encounter " + encounterUuid + " in saas_integration_queue.");
	}
	
	@Override
	@Transactional(readOnly = true)
	@SuppressWarnings("unchecked")
	public List<OutboxEntry> findDispatchable(int maxRetries) {
		List<Object[]> rows = sessionFactory.getCurrentSession()
		        .createNativeQuery(SELECT_SQL)
		        .setParameter("maxRetries", maxRetries)
		        .getResultList();

		List<OutboxEntry> result = new ArrayList<>(rows.size());
		for (Object[] row : rows) {
			OutboxEntry e = new OutboxEntry();
			e.setId(((Number) row[0]).intValue());
			e.setEncounterUuid((String) row[1]);
			e.setFhirPayload((String) row[2]);
			e.setStatus((String) row[3]);
			e.setRetryCount(((Number) row[4]).intValue());
			if (row[5] != null) {
				e.setCreatedAt((java.util.Date) row[5]);
			}
			if (row[6] != null) {
				e.setUpdatedAt((java.util.Date) row[6]);
			}
			result.add(e);
		}
		return result;
	}
	
	@Override
	@Transactional
	public void markSent(int id) {
		sessionFactory.getCurrentSession().createNativeQuery(MARK_SENT_SQL).setParameter("id", id).executeUpdate();
	}
	
	@Override
	@Transactional
	public void markFailed(int id) {
		sessionFactory.getCurrentSession().createNativeQuery(MARK_FAILED_SQL).setParameter("id", id).executeUpdate();
	}
}
