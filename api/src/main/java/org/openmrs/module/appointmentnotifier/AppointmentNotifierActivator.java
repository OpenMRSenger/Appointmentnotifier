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
import org.openmrs.api.context.Context;
import org.openmrs.module.BaseModuleActivator;
import org.openmrs.module.appointmentnotifier.task.SaasQueueTask;
import org.openmrs.scheduler.SchedulerException;
import org.openmrs.scheduler.SchedulerService;
import org.openmrs.scheduler.TaskDefinition;

/**
 * Module lifecycle hooks.
 * <p>
 * On start:
 * <ol>
 * <li>Registers {@link AppointmentServiceAdvice} as AOP advice on the Bahmni AppointmentsService so
 * every appointment save/cancel triggers an outbox enqueue.</li>
 * <li>Registers and starts {@link SaasQueueTask} in the OpenMRS scheduler (configurable interval).</li>
 * </ol>
 * On stop: removes AOP advice and scheduler task.
 */
public class AppointmentNotifierActivator extends BaseModuleActivator {
	
	private static final Log log = LogFactory.getLog(AppointmentNotifierActivator.class);
	
	private static final String APPOINTMENTS_SERVICE = "org.openmrs.module.appointments.service.AppointmentsService";
	
	private Class<?> bahmniAdvicedClass;
	
	// ── Lifecycle ──────────────────────────────────────────────────────────────
	
	@Override
	public void started() {
		log.info("AppointmentNotifier: starting module.");
		
		registerBahmniAdvice();
		registerSchedulerTask();
		
		log.info("AppointmentNotifier: started. Endpoint="
		        + Context.getAdministrationService().getGlobalProperty(AppointmentNotifierConstants.GP_SAAS_ENDPOINT,
		            "(not configured)"));
	}
	
	@Override
	public void stopped() {
		unregisterBahmniAdvice();
		log.info("AppointmentNotifier: module stopped.");
	}
	
	// ── Bahmni appointment AOP advice ──────────────────────────────────────────
	
	private void registerBahmniAdvice() {
		try {
			bahmniAdvicedClass = Context.loadClass(APPOINTMENTS_SERVICE);
			Context.addAdvice(bahmniAdvicedClass, new AppointmentServiceAdvice());
			log.info("AppointmentNotifier: AOP advice registered for Bahmni AppointmentsService.");
		}
		catch (ClassNotFoundException e) {
			log.info("AppointmentNotifier: Bahmni appointments module absent — advice skipped.");
		}
	}
	
	private void unregisterBahmniAdvice() {
		if (bahmniAdvicedClass != null && AppointmentServiceAdvice.getInstance() != null) {
			try {
				Context.removeAdvice(bahmniAdvicedClass, AppointmentServiceAdvice.getInstance());
			}
			catch (Exception e) {
				log.warn("AppointmentNotifier: could not remove Bahmni advice", e);
			}
		}
	}
	
	// ── Scheduler task ─────────────────────────────────────────────────────────
	
	private void registerSchedulerTask() {
		try {
			SchedulerService scheduler = Context.getSchedulerService();
			long intervalSeconds = resolveTaskInterval();
			TaskDefinition existing = scheduler.getTaskByName(AppointmentNotifierConstants.TASK_NAME);
			
			if (existing != null) {
				if (existing.getRepeatInterval() != intervalSeconds) {
					existing.setRepeatInterval(intervalSeconds);
					scheduler.saveTaskDefinition(existing);
					log.info("AppointmentNotifier: updated SaasQueueTask interval to " + intervalSeconds + "s.");
				}
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
			taskDef.setRepeatInterval(intervalSeconds);
			taskDef.setStartOnStartup(true);
			taskDef.setStarted(true);
			
			scheduler.scheduleTask(taskDef);
			log.info("AppointmentNotifier: SaasQueueTask registered (interval=" + intervalSeconds + "s).");
		}
		catch (SchedulerException e) {
			log.error("AppointmentNotifier: could not register SaasQueueTask", e);
		}
	}
	
	private static long resolveTaskInterval() {
		String raw = Context.getAdministrationService().getGlobalProperty(
		    AppointmentNotifierConstants.GP_TASK_INTERVAL_SECONDS, "");
		if (raw != null && !raw.trim().isEmpty()) {
			try {
				long v = Long.parseLong(raw.trim());
				if (v > 0)
					return v;
			}
			catch (NumberFormatException ignored) {}
		}
		return AppointmentNotifierConstants.TASK_INTERVAL_SECONDS;
	}
}
