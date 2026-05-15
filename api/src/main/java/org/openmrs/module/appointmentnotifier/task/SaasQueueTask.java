/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.appointmentnotifier.task;

import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.api.context.Context;
import org.openmrs.module.appointmentnotifier.AppointmentNotifierConstants;
import org.openmrs.module.appointmentnotifier.client.SaasHttpClient;
import org.openmrs.module.appointmentnotifier.outbox.OutboxEntry;
import org.openmrs.module.appointmentnotifier.outbox.OutboxService;
import org.openmrs.scheduler.tasks.AbstractTask;

/**
 * OpenMRS scheduled task that polls {@code saas_integration_queue} every 5 minutes and dispatches
 * PENDING / FAILED entries to the configured SaaS endpoint.
 * <p>
 * Registration: the task is registered programmatically by
 * {@link org.openmrs.module.appointmentnotifier.AppointmentNotifierActivator#started()} on first
 * module load; subsequent starts reuse the persisted {@code TaskDefinition}.
 * <p>
 * <strong>Idempotency guard:</strong> the inherited {@code isExecuting} flag prevents overlapping
 * runs if a dispatch cycle takes longer than the repeat interval.
 */
public class SaasQueueTask extends AbstractTask {
	
	private static final Log log = LogFactory.getLog(SaasQueueTask.class);
	
	@Override
	public void execute() {
		if (isExecuting) {
			log.warn("SaasQueueTask: previous run still in progress, skipping this cycle.");
			return;
		}
		
		isExecuting = true;
		Context.openSession();
		try {
			Context.addProxyPrivilege("Get Encounters");
			runDispatchCycle();
		}
		catch (Exception e) {
			log.error("SaasQueueTask: dispatch cycle failed", e);
		}
		finally {
			Context.removeProxyPrivilege("Get Encounters");
			Context.closeSession();
			isExecuting = false;
		}
	}
	
	// ── Dispatch logic ────────────────────────────────────────────────────────
	
	private void runDispatchCycle() {
		String endpoint = Context.getAdministrationService().getGlobalProperty(
		    AppointmentNotifierConstants.GP_SAAS_ENDPOINT, "");
		
		if (endpoint == null || endpoint.trim().isEmpty()) {
			log.warn("SaasQueueTask: " + AppointmentNotifierConstants.GP_SAAS_ENDPOINT
			        + " is not configured — skipping dispatch.");
			return;
		}
		
		String provider = Context.getAdministrationService().getGlobalProperty(
		    AppointmentNotifierConstants.GP_MESSAGING_PROVIDER, AppointmentNotifierConstants.DEFAULT_PROVIDER);
		
		int maxRetries = parseMaxRetries(Context.getAdministrationService().getGlobalProperty(
		    AppointmentNotifierConstants.GP_MAX_RETRIES, String.valueOf(AppointmentNotifierConstants.DEFAULT_MAX_RETRIES)));
		
		OutboxService outboxService = Context
		        .getRegisteredComponent("appointmentNotifierOutboxService", OutboxService.class);
		
		SaasHttpClient httpClient = Context.getRegisteredComponent("saasHttpClient", SaasHttpClient.class);
		
		List<OutboxEntry> entries = outboxService.findDispatchable(maxRetries);
		if (entries.isEmpty()) {
			log.debug("SaasQueueTask: no dispatchable entries.");
			return;
		}
		
		log.info("SaasQueueTask: dispatching " + entries.size() + " entries to " + endpoint);
		
		int sent = 0;
		int failed = 0;
		
		for (OutboxEntry entry : entries) {
			try {
				boolean success = httpClient.sendPayload(endpoint, provider, entry.getEncounterUuid(),
				    entry.getFhirPayload());
				
				if (success) {
					outboxService.markSent(entry.getId());
					sent++;
				} else {
					outboxService.markFailed(entry.getId());
					failed++;
				}
			}
			catch (Exception e) {
				log.error("SaasQueueTask: error dispatching entry id=" + entry.getId(), e);
				outboxService.markFailed(entry.getId());
				failed++;
			}
		}
		
		log.info("SaasQueueTask: cycle complete — sent=" + sent + " failed=" + failed);
	}
	
	// ── Utilities ─────────────────────────────────────────────────────────────
	
	private static int parseMaxRetries(String value) {
		try {
			return Integer.parseInt(value.trim());
		}
		catch (NumberFormatException e) {
			return AppointmentNotifierConstants.DEFAULT_MAX_RETRIES;
		}
	}
}
