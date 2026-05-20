/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.appointmentnotifier.event;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.Encounter;
import org.openmrs.api.context.Context;
import org.openmrs.module.appointmentnotifier.AppointmentNotifierConstants;
import org.openmrs.module.appointmentnotifier.fhir.FhirEncounterSerializer;
import org.openmrs.module.appointmentnotifier.outbox.OutboxService;

/**
 * AOP interceptor on {@code EncounterService} that enqueues Encounters in the transactional outbox
 * whenever an encounter is saved (created or updated).
 * <p>
 * This approach uses the same {@link MethodInterceptor} mechanism as the existing
 * {@link org.openmrs.module.appointmentnotifier.AppointmentServiceAdvice}, which requires no extra
 * Maven dependencies beyond {@code openmrs-api}.
 * <p>
 * Registered / deregistered by
 * {@link org.openmrs.module.appointmentnotifier.AppointmentNotifierActivator}.
 */
public class EncounterEventListener implements MethodInterceptor {
	
	private static final Log log = LogFactory.getLog(EncounterEventListener.class);
	
	/** Small pool — enqueueing is fast (one INSERT) but we keep it async to avoid blocking callers. */
	private final ExecutorService executor = Executors.newFixedThreadPool(2);
	
	private static EncounterEventListener instance;
	
	public EncounterEventListener() {
		instance = this;
	}
	
	public static EncounterEventListener getInstance() {
		return instance;
	}
	
	// ── MethodInterceptor ─────────────────────────────────────────────────────
	
	@Override
	public Object invoke(MethodInvocation invocation) throws Throwable {
		String methodName = invocation.getMethod().getName();

		if (!isSaveMethod(methodName)) {
			return invocation.proceed();
		}

		if (!isEnabled()) {
			return invocation.proceed();
		}

		Object result = invocation.proceed();

		Encounter encounter = resolveEncounter(result, invocation.getArguments());
		if (encounter == null || encounter.isVoided()) {
			return result;
		}

		// Pass only the UUID — the async thread reloads the encounter in its own session
		// to avoid detached-entity issues with Hibernate lazy collections.
		final String uuid = encounter.getUuid();
		executor.submit(() -> enqueueInOwnSession(uuid));

		return result;
	}
	
	// ── Async outbox enqueue ──────────────────────────────────────────────────
	
	private void enqueueInOwnSession(String uuid) {
		Context.openSession();
		try {
			Context.addProxyPrivilege("Get Encounters");
			
			Encounter encounter = Context.getEncounterService().getEncounterByUuid(uuid);
			if (encounter == null || encounter.isVoided()) {
				return;
			}
			
			FhirEncounterSerializer serializer = Context.getRegisteredComponent("fhirEncounterSerializer",
			    FhirEncounterSerializer.class);
			OutboxService outboxService = Context.getRegisteredComponent("appointmentNotifierOutboxService",
			    OutboxService.class);
			
			String fhirPayload = serializer.toFhirJson(encounter);
			outboxService.enqueue(uuid, fhirPayload);
			
			log.info("EncounterEventListener: enqueued encounter " + uuid);
		}
		catch (Exception e) {
			log.error("EncounterEventListener: failed to enqueue encounter " + uuid, e);
		}
		finally {
			Context.removeProxyPrivilege("Get Encounters");
			Context.closeSession();
		}
	}
	
	// ── Helpers ───────────────────────────────────────────────────────────────
	
	private static boolean isSaveMethod(String name) {
		return "saveEncounter".equals(name) || "saveEncounterWithObservations".equals(name);
	}
	
	private static boolean isEnabled() {
		try {
			String val = Context.getAdministrationService().getGlobalProperty(AppointmentNotifierConstants.GP_ENABLED,
			    "true");
			return "true".equalsIgnoreCase(val.trim());
		}
		catch (Exception e) {
			return true;
		}
	}
	
	private static Encounter resolveEncounter(Object result, Object[] args) {
		if (result instanceof Encounter)
			return (Encounter) result;
		if (args != null && args.length > 0 && args[0] instanceof Encounter) {
			return (Encounter) args[0];
		}
		return null;
	}
	
	// ── Lifecycle ─────────────────────────────────────────────────────────────
	
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
