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

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.api.EncounterService;
import org.openmrs.api.context.Context;
import org.openmrs.module.BaseModuleActivator;
import org.openmrs.module.appointmentnotifier.event.EncounterEventListener;
import org.openmrs.module.appointmentnotifier.task.SaasQueueTask;
import org.openmrs.scheduler.SchedulerException;
import org.openmrs.scheduler.SchedulerService;
import org.openmrs.scheduler.TaskDefinition;

/**
 * Module lifecycle hooks.
 * <p>
 * On start:
 * <ol>
 * <li>Registers {@link EncounterEventListener} as AOP advice on {@link EncounterService} so every
 * {@code saveEncounter} call triggers an outbox enqueue.</li>
 * <li>Registers and starts {@link SaasQueueTask} in the OpenMRS scheduler (5-min interval).</li>
 * <li>Optionally registers AOP advice on Bahmni AppointmentsService when present
 * (backward-compatible fire-and-forget webhook for Bahmni appointments).</li>
 * </ol>
 * <p>
 * On stop: removes AOP advice and shuts down executor pools.
 */
public class AppointmentNotifierActivator extends BaseModuleActivator {
	
	private static final Log log = LogFactory.getLog(AppointmentNotifierActivator.class);
	
	private static final String APPOINTMENTS_SERVICE = "org.openmrs.module.appointments.service.AppointmentsService";
	
	/** Kept to allow clean removal on stop(). */
	private Class<?> bahmniAdvicedClass;
	
	/** The EncounterService AOP advice — stored for removal on stop(). */
	private EncounterEventListener encounterAdvice;
	
	// ── Lifecycle ─────────────────────────────────────────────────────────────
	
	@Override
	public void started() {
		log.info("AppointmentNotifier: starting module.");
		
		registerEncounterAdvice();
		registerSchedulerTask();
		registerBahmniAdvice();
		
		log.info("AppointmentNotifier: started. Endpoint="
		        + Context.getAdministrationService().getGlobalProperty(AppointmentNotifierConstants.GP_SAAS_ENDPOINT,
		            "(not configured)"));
	}
	
	@Override
	public void stopped() {
		unregisterEncounterAdvice();
		unregisterBahmniAdvice();
		log.info("AppointmentNotifier: module stopped.");
	}
	
	// ── Encounter AOP advice ──────────────────────────────────────────────────
	
	private void registerEncounterAdvice() {
		try {
			encounterAdvice = new EncounterEventListener();
			Context.addAdvice(EncounterService.class, encounterAdvice);
			log.info("AppointmentNotifier: EncounterEventListener advice registered on EncounterService.");
		}
		catch (Exception e) {
			log.error("AppointmentNotifier: failed to register EncounterEventListener advice", e);
		}
	}
	
	private void unregisterEncounterAdvice() {
		if (encounterAdvice == null)
			return;
		try {
			Context.removeAdvice(EncounterService.class, encounterAdvice);
			encounterAdvice.shutdown();
		}
		catch (Exception e) {
			log.warn("AppointmentNotifier: could not remove EncounterEventListener advice", e);
		}
	}
	
	// ── Scheduler task ────────────────────────────────────────────────────────
	
	private void registerSchedulerTask() {
		try {
			SchedulerService scheduler = Context.getSchedulerService();
			TaskDefinition existing = scheduler.getTaskByName(AppointmentNotifierConstants.TASK_NAME);
			
			if (existing != null) {
				if (!existing.getStarted()) {
					scheduler.scheduleTask(existing);
					log.info("AppointmentNotifier: re-started existing SaasQueueTask.");
				} else {
					log.info("AppointmentNotifier: SaasQueueTask already running.");
				}
				return;
			}
			
			TaskDefinition taskDef = new TaskDefinition();
			taskDef.setName(AppointmentNotifierConstants.TASK_NAME);
			taskDef.setDescription("Dispatches pending entries from saas_integration_queue to the SaaS webhook endpoint.");
			taskDef.setTaskClass(SaasQueueTask.class.getName());
			taskDef.setRepeatInterval(AppointmentNotifierConstants.TASK_INTERVAL_SECONDS);
			taskDef.setStartOnStartup(true);
			taskDef.setStarted(true);
			
			scheduler.scheduleTask(taskDef);
			log.info("AppointmentNotifier: SaasQueueTask registered (interval="
			        + AppointmentNotifierConstants.TASK_INTERVAL_SECONDS + "s).");
		}
		catch (SchedulerException e) {
			log.error("AppointmentNotifier: could not register SaasQueueTask", e);
		}
	}
	
	// ── Optional Bahmni advice ────────────────────────────────────────────────
	
	private void registerBahmniAdvice() {
		try {
			bahmniAdvicedClass = Context.loadClass(APPOINTMENTS_SERVICE);
			Context.addAdvice(bahmniAdvicedClass, new AppointmentServiceAdvice());
			log.info("AppointmentNotifier: AOP advice registered for Bahmni AppointmentsService.");
		}
		catch (ClassNotFoundException e) {
			log.info("AppointmentNotifier: Bahmni appointments module absent — Bahmni advice skipped.");
		}
	}
	
	private void unregisterBahmniAdvice() {
		if (bahmniAdvicedClass != null && AppointmentServiceAdvice.getInstance() != null) {
			Context.removeAdvice(bahmniAdvicedClass, AppointmentServiceAdvice.getInstance());
		}
		if (AppointmentServiceAdvice.getInstance() != null) {
			AppointmentServiceAdvice.getInstance().shutdown();
		}
	}
}
