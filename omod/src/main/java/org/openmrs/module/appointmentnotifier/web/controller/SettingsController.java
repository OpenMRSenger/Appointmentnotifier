/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.appointmentnotifier.web.controller;

import static org.openmrs.module.appointmentnotifier.AppointmentNotifierConstants.*;

import javax.servlet.http.HttpServletRequest;

import org.openmrs.GlobalProperty;
import org.openmrs.api.AdministrationService;
import org.openmrs.api.context.Context;
import org.springframework.stereotype.Controller;
import org.springframework.ui.ModelMap;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

@Controller("appointmentnotifier.SettingsController")
@RequestMapping("/module/appointmentnotifier/settings.form")
public class SettingsController {
	
	private static final String VIEW = "/module/appointmentnotifier/settings";
	
	@RequestMapping(method = RequestMethod.GET)
	public String get(ModelMap model) {
		populateModel(model);
		return VIEW;
	}
	
	@RequestMapping(method = RequestMethod.POST)
	public String post(HttpServletRequest request, ModelMap model) {
		AdministrationService admin = Context.getAdministrationService();
		
		saveGp(admin, GP_SAAS_ENDPOINT, param(request, "saasEndpoint"));
		saveGp(admin, GP_SAAS_WEBHOOK_TOKEN, param(request, "saasWebhookToken"));
		saveGp(admin, GP_ENABLED, param(request, "enabled"));
		saveGp(admin, GP_MAX_RETRIES, param(request, "maxRetries"));
		saveGp(admin, GP_HOSPITAL_NAME, param(request, "hospitalName"));
		saveGp(admin, GP_OPENMRS_URL, param(request, "openmrsBaseUrl"));
		saveGp(admin, GP_USERNAME, param(request, "openmrsUsername"));
		saveGp(admin, GP_PASSWORD, param(request, "openmrsPassword"));
		saveGp(admin, GP_MESSAGING_PROVIDER, param(request, "messagingProvider"));
		saveGp(admin, GP_MESSAGING_PROVIDER_TOKEN, param(request, "messagingProviderToken"));
		saveGp(admin, GP_MESSAGING_PROVIDER_USERNAME, param(request, "messagingProviderUsername"));
		saveGp(admin, GP_MESSAGING_PROVIDER_PASSWORD, param(request, "messagingProviderPassword"));
		saveGp(admin, GP_MESSAGING_PROVIDER_CLIENT_ID, param(request, "messagingProviderClientId"));
		saveGp(admin, GP_MESSAGING_PROVIDER_CLIENT_SECRET, param(request, "messagingProviderClientSecret"));
		
		model.addAttribute("saved", true);
		populateModel(model);
		return VIEW;
	}
	
	private void populateModel(ModelMap model) {
		AdministrationService admin = Context.getAdministrationService();
		model.addAttribute("saasEndpoint", gp(admin, GP_SAAS_ENDPOINT));
		model.addAttribute("saasWebhookToken", gp(admin, GP_SAAS_WEBHOOK_TOKEN));
		model.addAttribute("enabled", gp(admin, GP_ENABLED));
		model.addAttribute("maxRetries", gp(admin, GP_MAX_RETRIES));
		model.addAttribute("hospitalName", gp(admin, GP_HOSPITAL_NAME));
		model.addAttribute("openmrsBaseUrl", gp(admin, GP_OPENMRS_URL));
		model.addAttribute("openmrsUsername", gp(admin, GP_USERNAME));
		model.addAttribute("openmrsPassword", gp(admin, GP_PASSWORD));
		model.addAttribute("messagingProvider", gp(admin, GP_MESSAGING_PROVIDER));
		model.addAttribute("messagingProviderToken", gp(admin, GP_MESSAGING_PROVIDER_TOKEN));
		model.addAttribute("messagingProviderUsername", gp(admin, GP_MESSAGING_PROVIDER_USERNAME));
		model.addAttribute("messagingProviderPassword", gp(admin, GP_MESSAGING_PROVIDER_PASSWORD));
		model.addAttribute("messagingProviderClientId", gp(admin, GP_MESSAGING_PROVIDER_CLIENT_ID));
		model.addAttribute("messagingProviderClientSecret", gp(admin, GP_MESSAGING_PROVIDER_CLIENT_SECRET));
	}
	
	private String gp(AdministrationService admin, String key) {
		String val = admin.getGlobalProperty(key);
		return val != null ? val : "";
	}
	
	private void saveGp(AdministrationService admin, String key, String value) {
		GlobalProperty gp = admin.getGlobalPropertyObject(key);
		if (gp == null) {
			gp = new GlobalProperty(key, value);
		} else {
			gp.setPropertyValue(value);
		}
		admin.saveGlobalProperty(gp);
	}
	
	private String param(HttpServletRequest request, String name) {
		String val = request.getParameter(name);
		return val != null ? val.trim() : "";
	}
}
